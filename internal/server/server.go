package server

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/store"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
)

type Config struct {
	Addr, DataDir, WebDir, AdminToken, UpdateDir          string
	ServerUpdateDir                                       string
	PublicEnrollment                                      bool
	P2PSTUN, P2PNATSTUN, P2PVerifyListen, P2PVerifyPublic string
	P2PVerifyMore, P2PVerifyKeyFile                       string
	P2PTrustProxy, P2PLocalTest, P2PLAN                   bool
}
type peer struct {
	id, agentID, clientID string
	role                  protocol.Role
	deviceType            string
	token                 string
	conn                  *websocket.Conn
	send                  chan []byte
	sendMu                sync.Mutex
	queuedBytes           atomic.Int64
	closed                chan struct{}
	once                  sync.Once
	dispatchMu            sync.Mutex
	revoked               bool
	expires               time.Time
	ip                    string
	directIP              netip.Addr
	directCapable         bool
	dualNATPunchCapable   bool
	ipv6Capable           bool
	lanCapable            bool
	direct                *directPair
	lastDirect            time.Time
	lastNATDiagnostic     time.Time
	natDiagnosticCapable  bool
	natDiagnostic         *natDiagnostic

	clientInitiatedCapable bool
}

const maxPeerQueueBytes = 2 * protocol.MaxEnvelopeSize

func (p *peer) close() {
	p.once.Do(func() {
		p.sendMu.Lock()
		close(p.closed)
		for {
			select {
			case frame := <-p.send:
				p.queuedBytes.Add(-int64(len(frame)))
			default:
				p.sendMu.Unlock()
				if p.conn != nil {
					_ = p.conn.Close()
				}
				return
			}
		}
	})
}

type Server struct {
	cfg             Config
	store           *store.Store
	mu              sync.RWMutex
	agents          map[string]*peer
	clients         map[string]*peer
	byAgent         map[string]map[string]*peer
	seq             atomic.Uint64
	maintenanceDone chan struct{}
	closeOnce       sync.Once
	stun            []string
	natSTUN         []string
	verifier        *p2p.UDPVerifier
	verifyPublic    string
	verifyMore      []string

	beforeClientUpgrade  func() // coordinates authorization/revocation interleavings in tests
	beforeClientDispatch func() // coordinates post-heartbeat RPC dispatch interleavings in tests
}

func New(cfg Config) (*Server, error) {
	if cfg.PublicEnrollment && cfg.AdminToken != "" {
		return nil, errors.New("public enrollment cannot be combined with an enrollment key")
	}
	stun, err := stunEndpoints(cfg.P2PSTUN)
	if err != nil {
		return nil, err
	}
	natSTUN := stunForPair(stun, false)
	if cfg.P2PNATSTUN != "" {
		natSTUN, err = stunEndpoints(cfg.P2PNATSTUN)
		if err != nil || len(natSTUN) == 0 || len(natSTUN) != len(stunForPair(natSTUN, false)) {
			return nil, errors.New("NAT diagnostic STUN requires up to four IPv4:port endpoints")
		}
	}
	if cfg.WebDir == "" {
		cfg.WebDir = "web"
	}
	if cfg.UpdateDir == "" {
		cfg.UpdateDir = filepath.Join(cfg.DataDir, "updates")
	}
	st, err := store.OpenExclusive(filepath.Join(cfg.DataDir, "state.json"))
	if err != nil {
		return nil, err
	}
	if cfg.PublicEnrollment {
		st.LimitPublicEnrollment()
	}
	if _, err := st.PruneInactiveClients(time.Now(), nil); err != nil {
		_ = st.Close()
		return nil, err
	}
	s := &Server{cfg: cfg, store: st, agents: map[string]*peer{}, clients: map[string]*peer{}, byAgent: map[string]map[string]*peer{}, maintenanceDone: make(chan struct{}), stun: stun, natSTUN: natSTUN}
	if err := s.startVerifier(); err != nil {
		_ = st.Close()
		return nil, err
	}
	go s.maintainClients()
	return s, nil
}
func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && strings.EqualFold(u.Host, r.Host)
}

var assetVersionPattern = regexp.MustCompile(`^\d{8}-\d+$`)

func staticResponseHeaders(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	versionedAsset := path == "/app.js" || path == "/styles.css" || path == "/dsh-theme.css" || path == "/pwa.js" || path == "/theme.js"
	switch {
	case strings.HasPrefix(path, "/api/"):
		w.Header().Set("Cache-Control", "no-store")
	case path == "/sw.js":
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Service-Worker-Allowed", "/")
	case path == "/", path == "/index.html", path == "/manifest.webmanifest":
		w.Header().Set("Cache-Control", "no-cache")
	case strings.HasPrefix(path, "/icons/"):
		w.Header().Set("Cache-Control", "public, max-age=86400")
	case versionedAsset && assetVersionPattern.MatchString(r.URL.Query().Get("v")):
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	if path == "/manifest.webmanifest" {
		w.Header().Set("Content-Type", "application/manifest+json; charset=utf-8")
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	enrollLimiter := newIPRateLimiter(30, 10)
	claimLimiter := newIPRateLimiter(10, 5)
	pair := s.pair
	enroll := rateLimit(enrollLimiter, s.enroll)
	claim := rateLimit(claimLimiter, s.claim)
	if s.cfg.PublicEnrollment {
		enrollmentBudget := newIPRateLimiter(90, 30)
		persistenceBudget := newIPRateLimiter(240, 60)
		enroll = rateLimitKey(enrollmentBudget, func(*http.Request) string { return "global" }, enroll)
		pair = rateLimitKey(persistenceBudget, func(*http.Request) string { return "global" }, rateLimit(newIPRateLimiter(12, 6), pair))
		claim = rateLimitKey(persistenceBudget, func(*http.Request) string { return "global" }, claim)
	}
	mux.HandleFunc("GET /update.json", s.updateManifest)
	mux.HandleFunc("GET /android-update.json", s.androidUpdateManifest)
	mux.HandleFunc("GET /linux-update.json", s.linuxUpdateManifest)
	mux.HandleFunc("GET /"+release.ServerManifestName, s.serverUpdateManifest)
	mux.HandleFunc("GET /updates/{file}", s.updatePackage)
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusOK, release.ServerHealth{
			OK: true, Service: "codexrm", ProtocolVersion: protocol.Version,
			VersionCode: release.ServerVersionCode, VersionName: release.ServerVersionName,
		})
	})
	mux.HandleFunc("POST /api/v1/agent/enroll", enroll)
	mux.HandleFunc("POST /api/v1/agent/pair", pair)
	mux.HandleFunc("GET /api/v1/agent/pair/status", s.pairStatus)
	mux.HandleFunc("GET /api/v1/agent/clients", s.listClients)
	mux.HandleFunc("DELETE /api/v1/agent/clients/{clientID}", s.deleteClient)
	mux.HandleFunc("GET /api/v1/agent/ws", s.agentWS)
	mux.HandleFunc("POST /api/v1/client/claim", claim)
	mux.HandleFunc("POST /api/v1/client/profile", s.clientProfile)
	mux.HandleFunc("POST /api/v1/client/revoke", s.revoke)
	mux.HandleFunc("GET /api/v1/client/ws", s.clientWS)
	mux.HandleFunc("GET /api/v1/agents", s.list)
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) { fail(w, 404, "API not found") })
	mux.Handle("/", http.FileServer(http.Dir(s.cfg.WebDir)))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self' ws: wss:; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'")
		staticResponseHeaders(w, r)
		if !sameOrigin(r) {
			fail(w, 403, "origin not allowed")
			return
		}
		if !strings.HasPrefix(r.URL.Path, "/api/") {
			controller := http.NewResponseController(w)
			_ = controller.SetWriteDeadline(time.Now().Add(2 * time.Minute))
			defer controller.SetWriteDeadline(time.Time{})
		}
		mux.ServeHTTP(w, r)
	})
}
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
func fail(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]any{"error": msg})
}
func readJSON(w http.ResponseWriter, r *http.Request, v any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	if err := dec.Decode(v); err != nil {
		return err
	}
	var trailing any
	if dec.Decode(&trailing) != io.EOF {
		return errors.New("expected one JSON object")
	}
	return nil
}
func bearer(r *http.Request) string {
	parts := strings.Fields(r.Header.Get("Authorization"))
	if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
		return parts[1]
	}
	for _, value := range websocket.Subprotocols(r) {
		if strings.HasPrefix(value, "codexrm.token.") {
			return strings.TrimPrefix(value, "codexrm.token.")
		}
	}
	return r.URL.Query().Get("token")
}
func secureEqual(a, b string) bool {
	return a != "" && b != "" && subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}
func forwardedEnrollment(r *http.Request) bool {
	for _, key := range []string{"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP", "Via"} {
		if len(r.Header.Values(key)) != 0 {
			return true
		}
	}
	return false
}

func (s *Server) enroll(w http.ResponseWriter, r *http.Request) {
	switch {
	case s.cfg.PublicEnrollment:
		r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
	case s.cfg.AdminToken != "":
		if !secureEqual(r.Header.Get("X-Enrollment-Key"), s.cfg.AdminToken) {
			fail(w, 401, "invalid enrollment key")
			return
		}
	default:
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsLoopback() || forwardedEnrollment(r) {
			fail(w, 403, "remote enrollment requires server --enroll-key")
			return
		}
	}
	var req protocol.EnrollRequest
	if readJSON(w, r, &req) != nil {
		fail(w, 400, "invalid enrollment JSON")
		return
	}
	if req.Name == "" {
		req.Name = "Codex Host"
	}
	if req.InstallationID == "" {
		req.InstallationID = uuid.NewString()
	}
	rec, err := s.store.EnrollAgent(req, bearer(r))
	if err != nil {
		if errors.Is(err, store.ErrCapacity) {
			fail(w, http.StatusTooManyRequests, err.Error())
		} else if errors.Is(err, store.ErrUnauthorized) {
			fail(w, 401, "installation already registered; existing agent token required")
		} else {
			fail(w, 500, err.Error())
		}
		return
	}
	writeJSON(w, 200, protocol.EnrollResponse{AgentID: rec.AgentID, EnvironmentID: rec.EnvironmentID, Token: rec.Token, ExpiresAt: rec.ExpiresAt.Unix()})
}
func (s *Server) pair(w http.ResponseWriter, r *http.Request) {
	a, err := s.store.AuthenticateAgent(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	rec, err := s.store.StartPairing(a.AgentID, true)
	if err != nil {
		if errors.Is(err, store.ErrCapacity) {
			fail(w, http.StatusTooManyRequests, err.Error())
			return
		}
		fail(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, protocol.PairStartResponse{PairingCode: rec.PairingCode, ManualPairingCode: rec.ManualPairingCode, EnvironmentID: rec.EnvironmentID, ExpiresAt: rec.ExpiresAt.Unix()})
}
func (s *Server) pairStatus(w http.ResponseWriter, r *http.Request) {
	agent, e := s.store.AuthenticateAgent(bearer(r))
	if e != nil {
		fail(w, 401, "unauthorized")
		return
	}
	claimed, err := s.store.PairingStatus(agent.AgentID, r.URL.Query().Get("code"))
	if err != nil {
		fail(w, 404, "pairing not found")
		return
	}
	writeJSON(w, 200, map[string]any{"claimed": claimed})
}
func (s *Server) claim(w http.ResponseWriter, r *http.Request) {
	if s.cfg.PublicEnrollment {
		r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
	}
	var req protocol.PairClaimRequest
	if readJSON(w, r, &req) != nil || strings.TrimSpace(req.Code) == "" {
		fail(w, 400, "pairing code required")
		return
	}
	if req.ClientName == "" {
		req.ClientName = "CodexRM Web"
	}
	if req.DeviceType == "" {
		req.DeviceType = "browser"
	}
	c, a, err := s.store.ClaimPairing(req.Code, req.ClientName, req.DeviceType, clientIP(r))
	if err != nil {
		status := 400
		switch {
		case errors.Is(err, store.ErrNotFound):
			status = 404
		case errors.Is(err, store.ErrExpired):
			status = 410
		case errors.Is(err, store.ErrAlreadyClaimed):
			status = 409
		case errors.Is(err, store.ErrCapacity):
			status = http.StatusTooManyRequests
		}
		fail(w, status, err.Error())
		return
	}
	writeJSON(w, 200, protocol.PairClaimResponse{ClientID: c.ClientID, Token: c.Token, EnvironmentID: c.EnvironmentID, Agent: a.AgentInfo, ExpiresAt: 0})
}
func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	token := bearer(r)
	if secureEqual(token, s.cfg.AdminToken) {
		writeJSON(w, 200, map[string]any{"data": s.store.ListAgents()})
		return
	}
	c, err := s.store.AuthenticateClient(token)
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	a, err := s.store.GetAgent(c.AgentID)
	if err != nil {
		fail(w, 404, "agent not found")
		return
	}
	writeJSON(w, 200, map[string]any{"data": []protocol.AgentInfo{a.AgentInfo}})
}
func (s *Server) clientProfile(w http.ResponseWriter, r *http.Request) {
	token := bearer(r)
	current, err := s.store.AuthenticateClient(token)
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	var req protocol.ClientProfileRequest
	if readJSON(w, r, &req) != nil {
		fail(w, 400, "invalid client profile JSON")
		return
	}
	req.ClientName, req.DeviceType = strings.TrimSpace(req.ClientName), strings.TrimSpace(req.DeviceType)
	allowed := req.DeviceType == "desktop" || req.DeviceType == "mobile" || req.DeviceType == "browser" || (current.DeviceType == protocol.DeviceTypeRobot && req.DeviceType == protocol.DeviceTypeRobot)
	if req.ClientName == "" || len(req.ClientName) > 1024 || !allowed || (current.DeviceType == protocol.DeviceTypeRobot) != (req.DeviceType == protocol.DeviceTypeRobot) {
		fail(w, 400, "clientName and a valid deviceType are required")
		return
	}
	if err := s.store.UpdateClientProfile(token, req.ClientName, req.DeviceType); err != nil {
		if errors.Is(err, store.ErrUnauthorized) || errors.Is(err, store.ErrExpired) {
			fail(w, 401, "unauthorized")
		} else {
			fail(w, 500, err.Error())
		}
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// removeClient linearizes revocation with the final RPC authorization check
// and enqueue. For a live peer, lock order is dispatchMu, s.mu, then store.mu.
func (s *Server) removeClient(clientID, agentID string, remove func() error) error {
	for {
		s.mu.RLock()
		p := s.clients[clientID]
		s.mu.RUnlock()
		if p != nil {
			p.dispatchMu.Lock()
		}
		s.mu.Lock()
		if s.clients[clientID] != p {
			s.mu.Unlock()
			if p != nil {
				p.dispatchMu.Unlock()
			}
			continue
		}
		if p != nil && p.agentID != agentID {
			s.mu.Unlock()
			p.dispatchMu.Unlock()
			return store.ErrNotFound
		}
		if p != nil {
			p.revoked = true
			p.close()
		}
		err := remove()
		s.mu.Unlock()
		if p != nil {
			p.dispatchMu.Unlock()
		}
		return err
	}
}

func (s *Server) revoke(w http.ResponseWriter, r *http.Request) {
	c, err := s.store.AuthenticateClient(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	err = s.removeClient(c.ClientID, c.AgentID, func() error {
		return s.store.RevokeClient(c.Token)
	})
	if err != nil {
		fail(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (s *Server) listClients(w http.ResponseWriter, r *http.Request) {
	a, err := s.store.AuthenticateAgent(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	rows := make([]protocol.ClientInfo, 0)
	for _, c := range s.store.ListClients(a.AgentID) {
		row := protocol.ClientInfo{ClientID: c.ClientID, ClientName: c.ClientName, DeviceType: c.DeviceType, CreatedAt: c.CreatedAt.Format(time.RFC3339Nano), LastIP: c.LastIP}
		if !c.LastConnectedAt.IsZero() {
			row.LastConnectedAt = c.LastConnectedAt.Format(time.RFC3339Nano)
		}
		rows = append(rows, row)
	}
	writeJSON(w, 200, map[string]any{"data": rows})
}

func (s *Server) deleteClient(w http.ResponseWriter, r *http.Request) {
	a, err := s.store.AuthenticateAgent(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	clientID := r.PathValue("clientID")
	err = s.removeClient(clientID, a.AgentID, func() error {
		return s.store.DeleteClient(a.AgentID, clientID)
	})
	if err != nil {
		fail(w, 404, "client not found")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) agentWS(w http.ResponseWriter, r *http.Request) {
	token := bearer(r)
	a, err := s.store.AuthenticateAgent(token)
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	if err := s.store.SetAgentAppVersion(a.AgentID, r.Header.Get(protocol.AppVersionHeader)); err != nil {
		log.Printf("update agent version: %v", err)
	}
	s.upgrade(w, r, protocol.RoleAgent, a.AgentID, "", a.ExpiresAt, token, "")
}
func (s *Server) clientWS(w http.ResponseWriter, r *http.Request) {
	token := bearer(r)
	c, err := s.store.AuthenticateClient(token)
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	if s.beforeClientUpgrade != nil {
		s.beforeClientUpgrade()
	}
	s.upgrade(w, r, protocol.RoleClient, c.AgentID, c.ClientID, c.ExpiresAt, token, c.DeviceType)
}
func (s *Server) upgrade(w http.ResponseWriter, r *http.Request, role protocol.Role, agentID, clientID string, expires time.Time, token, deviceType string) {
	up := websocket.Upgrader{CheckOrigin: sameOrigin, Subprotocols: []string{"codexrm"}}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	p := &peer{id: uuid.NewString(), role: role, agentID: agentID, clientID: clientID, deviceType: deviceType, token: token, conn: conn, send: make(chan []byte, 256), closed: make(chan struct{}), expires: expires}
	p.ip = clientIP(r)
	p.directIP = s.directIP(r)
	s.mu.Lock()
	select {
	case <-s.maintenanceDone:
		s.mu.Unlock()
		p.close()
		return
	default:
	}
	if role == protocol.RoleAgent {
		a, authErr := s.store.AuthenticateAgent(token)
		if authErr != nil || a.AgentID != agentID {
			s.mu.Unlock()
			p.close()
			return
		}
	} else {
		c, authErr := s.store.AuthenticateClient(token)
		if authErr != nil || c.AgentID != agentID || c.ClientID != clientID {
			s.mu.Unlock()
			p.close()
			return
		}
		if err := s.store.RecordClientConnection(clientID, p.ip, time.Now(), false); err != nil {
			s.mu.Unlock()
			log.Printf("record client connection: %v", err)
			p.close()
			return
		}
	}
	if role == protocol.RoleAgent {
		if old := s.agents[agentID]; old != nil {
			old.close()
			for _, c := range s.byAgent[agentID] {
				s.stopDirect(c)
			}
		}
		s.agents[agentID] = p
		s.store.SetAgentOnline(agentID, true)
	} else {
		if old := s.clients[clientID]; old != nil {
			old.close()
			s.stopDirect(old)
		}
		s.clients[clientID] = p
		if s.byAgent[agentID] == nil {
			s.byAgent[agentID] = map[string]*peer{}
		}
		s.byAgent[agentID][clientID] = p
		if p.deviceType != protocol.DeviceTypeRobot {
			if a := s.agents[agentID]; a != nil && a.directCapable {
				s.sendDirect(a, p, protocol.P2P("join", ""))
				if s.directAvailable(a, p) {
					s.sendDirect(p, p, protocol.P2P("available", ""))
				}
			}
		}
	}
	s.mu.Unlock()
	if role == protocol.RoleAgent {
		s.broadcastStatus(agentID)
	} else {
		s.sendStatus(p, protocol.TypeWelcome)
	}
	go s.writePump(p)
	s.readPump(p)
}

// send reports whether the envelope fits the wire limit. When a client
// request does not fit, its sender must receive the error, not the Agent.
func (s *Server) send(p *peer, env protocol.Envelope) bool {
	frame, err := json.Marshal(env)
	if err != nil || len(frame) > protocol.MaxEnvelopeSize {
		return false
	}
	if !p.enqueue(frame) {
		log.Printf("disconnect slow peer %s", p.id)
		p.close()
	}
	return true
}

func (p *peer) enqueue(frame []byte) bool {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	select {
	case <-p.closed:
		return true
	default:
	}
	size := int64(len(frame))
	if p.queuedBytes.Add(size) > maxPeerQueueBytes {
		p.queuedBytes.Add(-size)
		return false
	}
	select {
	case p.send <- frame:
		return true
	default:
		p.queuedBytes.Add(-size)
		return false
	}
}

func (s *Server) writePump(p *peer) {
	defer p.close()
	ticker := time.NewTicker(20 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-p.closed:
			return
		case frame := <-p.send:
			_ = p.conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
			err := p.conn.WriteMessage(websocket.TextMessage, frame)
			p.queuedBytes.Add(-int64(len(frame)))
			if err != nil {
				return
			}
		case <-ticker.C:
			if !p.expires.IsZero() && time.Now().After(p.expires) {
				return
			}
			if p.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(10*time.Second)) != nil {
				return
			}
		}
	}
}
func (s *Server) readPump(p *peer) {
	defer func() {
		s.mu.Lock()
		changed := false
		if p.role == protocol.RoleAgent {
			if s.agents[p.agentID] == p {
				for _, c := range s.byAgent[p.agentID] {
					s.stopDirect(c)
				}
				delete(s.agents, p.agentID)
				s.store.SetAgentOnline(p.agentID, false)
				changed = true
			}
		} else {
			if s.clients[p.clientID] == p {
				s.stopDirect(p)
				if a := s.agents[p.agentID]; a != nil && a.directCapable {
					s.sendDirect(a, p, protocol.P2P("leave", ""))
				}
				delete(s.clients, p.clientID)
				delete(s.byAgent[p.agentID], p.clientID)
			}
		}
		s.mu.Unlock()
		p.close()
		if changed {
			s.broadcastStatus(p.agentID)
		}
	}()
	p.conn.SetReadLimit(protocol.MaxEnvelopeSize)
	_ = p.conn.SetReadDeadline(time.Now().Add(65 * time.Second))
	p.conn.SetPongHandler(func(string) error {
		if err := s.recordClientHeartbeat(p); err != nil {
			return err
		}
		return p.conn.SetReadDeadline(time.Now().Add(65 * time.Second))
	})
	for {
		// Read the bounded frame before parsing JSON so a receiver spending
		// time decoding a large payload does not extend the sender's write.
		_, frame, err := p.conn.ReadMessage()
		if err != nil {
			return
		}
		var env protocol.Envelope
		if err := json.Unmarshal(frame, &env); err != nil {
			return
		}
		if err := s.recordClientHeartbeat(p); err != nil {
			log.Printf("record client heartbeat: %v", err)
			return
		}
		if !p.expires.IsZero() && time.Now().After(p.expires) {
			return
		}
		_ = p.conn.SetReadDeadline(time.Now().Add(65 * time.Second))
		if env.Type == protocol.TypePing {
			s.send(p, protocol.Envelope{Type: protocol.TypePong, PingID: env.PingID})
			continue
		}
		if env.Type == protocol.TypePong {
			continue
		}
		if env.Type == protocol.TypeP2P {
			if p.deviceType == protocol.DeviceTypeRobot {
				continue
			}
			s.handleDirect(p, env)
			continue
		}
		if p.role == protocol.RoleClient {
			if env.Type != protocol.TypeClientMessage || !json.Valid(env.Message) {
				s.send(p, protocol.Envelope{Type: protocol.TypeError, Error: "invalid RPC envelope"})
				continue
			}
			var rpc struct{ Method string }
			_ = json.Unmarshal(env.Message, &rpc)
			if p.deviceType == protocol.DeviceTypeRobot && !protocol.RobotMethodAllowed(rpc.Method) {
				s.send(p, protocol.Envelope{Type: protocol.TypeError, Error: "method not allowed", StreamID: env.StreamID})
				continue
			}
			if protocol.RequiresDirectFileTransfer(rpc.Method, env.Message) {
				s.send(p, protocol.Envelope{Type: protocol.TypeError, Error: protocol.FileTransferDirectRequired, StreamID: env.StreamID})
				continue
			}
			env.ClientID = p.clientID
			env.ClientKind = ""
			if p.deviceType == protocol.DeviceTypeRobot {
				env.ClientKind = protocol.ClientKindRobot
			}
			env.SkipDirect, env.P2P = nil, nil
			env.SeqID = s.seq.Add(1)
			if s.beforeClientDispatch != nil {
				s.beforeClientDispatch()
			}
			s.dispatchClientRPC(p, env)
		} else if env.Type == protocol.TypeServerMessage {
			env.Message, _ = protocol.WithoutInlineFiles(env.Message)
			env.SeqID = s.seq.Add(1)
			s.mu.RLock()
			if s.agents[p.agentID] != p {
				s.mu.RUnlock()
				return
			}
			skip := func(c *peer) bool {
				if c.direct == nil || c.direct.agent != p || !c.direct.active || time.Now().After(c.direct.deadline) {
					return false
				}
				for _, id := range env.SkipDirect {
					if id == c.direct.id {
						return true
					}
				}
				return false
			}
			list := []*peer{}
			if env.ClientID != "" {
				if c := s.clients[env.ClientID]; c != nil && c.agentID == p.agentID && !skip(c) {
					list = append(list, c)
				}
			} else {
				for _, c := range s.byAgent[p.agentID] {
					if c.deviceType == protocol.DeviceTypeRobot || skip(c) {
						continue
					}
					list = append(list, c)
				}
			}
			s.mu.RUnlock()
			env.SkipDirect, env.P2P = nil, nil
			for _, c := range list {
				if !s.send(c, env) && env.StreamID != "" {
					s.send(c, protocol.Envelope{Type: protocol.TypeError, StreamID: env.StreamID, Error: "relay response is too large"})
				}
			}
		}
	}
}
func (s *Server) dispatchClientRPC(p *peer, env protocol.Envelope) {
	p.dispatchMu.Lock()
	defer p.dispatchMu.Unlock()
	s.mu.RLock()
	defer s.mu.RUnlock()
	if p.revoked || s.clients[p.clientID] != p {
		return
	}
	select {
	case <-p.closed:
		return
	default:
	}
	agent := s.agents[p.agentID]
	if agent == nil {
		s.send(p, protocol.Envelope{Type: protocol.TypeError, Error: "agent offline", StreamID: env.StreamID})
		return
	}
	if !s.send(agent, env) {
		s.send(p, protocol.Envelope{Type: protocol.TypeError, StreamID: env.StreamID, Error: "relay request is too large"})
	}
}

func (s *Server) sendStatus(p *peer, kind string) {
	a, err := s.store.GetAgent(p.agentID)
	if err != nil {
		return
	}
	status := "connecting"
	if a.Online {
		status = "connected"
	}
	s.send(p, protocol.Envelope{Type: kind, Status: status, Agent: &a.AgentInfo, FileTransferDirectOnly: true})
}
func (s *Server) broadcastStatus(agentID string) {
	s.mu.RLock()
	list := []*peer{}
	for _, p := range s.byAgent[agentID] {
		list = append(list, p)
	}
	s.mu.RUnlock()
	for _, p := range list {
		s.sendStatus(p, protocol.TypeAgentStatus)
	}
}

// Close releases hijacked WebSockets, which http.Server.Shutdown does not own.
func (s *Server) Close() {
	s.closeOnce.Do(func() {
		close(s.maintenanceDone)
		if s.verifier != nil {
			s.verifier.Close()
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		for _, p := range s.agents {
			p.close()
		}
		for _, p := range s.clients {
			p.close()
		}
		if err := s.store.Close(); err != nil {
			log.Printf("close relay state: %v", err)
		}
	})
}
