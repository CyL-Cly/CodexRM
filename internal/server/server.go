package server

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/store"
	webui "github.com/codexrm/codexrm/web"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Config struct{ Addr, DataDir, WebDir, AdminToken string }
type peer struct {
	id, agentID, clientID string
	role                  protocol.Role
	conn                  *websocket.Conn
	send                  chan protocol.Envelope
	closed                chan struct{}
	once                  sync.Once
	expires               time.Time
}

func (p *peer) close() { p.once.Do(func() { close(p.closed); _ = p.conn.Close() }) }

type Server struct {
	cfg     Config
	store   *store.Store
	mu      sync.RWMutex
	agents  map[string]*peer
	clients map[string]*peer
	byAgent map[string]map[string]*peer
	seq     atomic.Uint64
}

func New(cfg Config) (*Server, error) {
	st, err := store.Open(filepath.Join(cfg.DataDir, "state.json"))
	if err != nil {
		return nil, err
	}
	return &Server{cfg: cfg, store: st, agents: map[string]*peer{}, clients: map[string]*peer{}, byAgent: map[string]map[string]*peer{}}, nil
}
func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	return err == nil && (u.Scheme == "http" || u.Scheme == "https") && strings.EqualFold(u.Host, r.Host)
}
func staticResponseHeaders(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
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
	case r.URL.Query().Get("v") == protocol.Version && (path == "/app.js" || path == "/styles.css" || path == "/pwa.js"):
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	}
	if path == "/manifest.webmanifest" {
		w.Header().Set("Content-Type", "application/manifest+json; charset=utf-8")
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true, "service": "codexrm", "version": protocol.Version})
	})
	mux.HandleFunc("POST /api/v1/agent/enroll", s.enroll)
	mux.HandleFunc("POST /api/v1/agent/pair", s.pair)
	mux.HandleFunc("GET /api/v1/agent/pair/status", s.pairStatus)
	mux.HandleFunc("GET /api/v1/agent/ws", s.agentWS)
	mux.HandleFunc("POST /api/v1/client/claim", s.claim)
	mux.HandleFunc("POST /api/v1/client/revoke", s.revoke)
	mux.HandleFunc("GET /api/v1/client/ws", s.clientWS)
	mux.HandleFunc("GET /api/v1/agents", s.list)
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) { fail(w, 404, "API not found") })
	var fs http.Handler = http.FileServer(http.FS(webui.Files))
	if s.cfg.WebDir != "" {
		fs = http.FileServer(http.Dir(s.cfg.WebDir))
	}
	mux.Handle("/", fs)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' ws: wss:; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'")
		staticResponseHeaders(w, r)
		if !sameOrigin(r) {
			fail(w, 403, "origin not allowed")
			return
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
func (s *Server) enroll(w http.ResponseWriter, r *http.Request) {
	if s.cfg.AdminToken != "" {
		if !secureEqual(r.Header.Get("X-Enrollment-Key"), s.cfg.AdminToken) {
			fail(w, 401, "invalid enrollment key")
			return
		}
	} else {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsLoopback() || r.Header.Get("X-Forwarded-For") != "" {
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
		if errors.Is(err, store.ErrUnauthorized) {
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
		fail(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, protocol.PairStartResponse{PairingCode: rec.PairingCode, ManualPairingCode: rec.ManualPairingCode, EnvironmentID: rec.EnvironmentID, ExpiresAt: rec.ExpiresAt.Unix()})
}
func (s *Server) pairStatus(w http.ResponseWriter, r *http.Request) {
	if _, e := s.store.AuthenticateAgent(bearer(r)); e != nil {
		fail(w, 401, "unauthorized")
		return
	}
	claimed, err := s.store.PairingStatus(r.URL.Query().Get("code"))
	if err != nil {
		fail(w, 404, "pairing not found")
		return
	}
	writeJSON(w, 200, map[string]any{"claimed": claimed})
}
func (s *Server) claim(w http.ResponseWriter, r *http.Request) {
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
	c, a, err := s.store.ClaimPairing(req.Code, req.ClientName, req.DeviceType)
	if err != nil {
		status := 400
		switch {
		case errors.Is(err, store.ErrNotFound):
			status = 404
		case errors.Is(err, store.ErrExpired):
			status = 410
		case errors.Is(err, store.ErrAlreadyClaimed):
			status = 409
		}
		fail(w, status, err.Error())
		return
	}
	writeJSON(w, 200, protocol.PairClaimResponse{ClientID: c.ClientID, Token: c.Token, EnvironmentID: c.EnvironmentID, Agent: a.AgentInfo, ExpiresAt: c.ExpiresAt.Unix()})
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
func (s *Server) revoke(w http.ResponseWriter, r *http.Request) {
	c, err := s.store.AuthenticateClient(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	if err = s.store.RevokeClient(c.Token); err != nil {
		fail(w, 500, err.Error())
		return
	}
	s.mu.RLock()
	p := s.clients[c.ClientID]
	s.mu.RUnlock()
	if p != nil {
		p.close()
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (s *Server) agentWS(w http.ResponseWriter, r *http.Request) {
	a, err := s.store.AuthenticateAgent(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	s.upgrade(w, r, protocol.RoleAgent, a.AgentID, "", a.ExpiresAt)
}
func (s *Server) clientWS(w http.ResponseWriter, r *http.Request) {
	c, err := s.store.AuthenticateClient(bearer(r))
	if err != nil {
		fail(w, 401, "unauthorized")
		return
	}
	s.upgrade(w, r, protocol.RoleClient, c.AgentID, c.ClientID, c.ExpiresAt)
}
func (s *Server) upgrade(w http.ResponseWriter, r *http.Request, role protocol.Role, agentID, clientID string, expires time.Time) {
	up := websocket.Upgrader{CheckOrigin: sameOrigin, Subprotocols: []string{"codexrm"}}
	conn, err := up.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	p := &peer{id: uuid.NewString(), role: role, agentID: agentID, clientID: clientID, conn: conn, send: make(chan protocol.Envelope, 256), closed: make(chan struct{}), expires: expires}
	s.mu.Lock()
	if role == protocol.RoleAgent {
		if old := s.agents[agentID]; old != nil {
			old.close()
		}
		s.agents[agentID] = p
		s.store.SetAgentOnline(agentID, true)
	} else {
		if old := s.clients[clientID]; old != nil {
			old.close()
		}
		s.clients[clientID] = p
		if s.byAgent[agentID] == nil {
			s.byAgent[agentID] = map[string]*peer{}
		}
		s.byAgent[agentID][clientID] = p
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
func (s *Server) send(p *peer, env protocol.Envelope) {
	select {
	case <-p.closed:
		return
	default:
	}
	select {
	case <-p.closed:
	case p.send <- env:
	default:
		log.Printf("disconnect slow peer %s", p.id)
		p.close()
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
		case env := <-p.send:
			_ = p.conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
			if p.conn.WriteJSON(env) != nil {
				return
			}
		case <-ticker.C:
			if time.Now().After(p.expires) {
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
				delete(s.agents, p.agentID)
				s.store.SetAgentOnline(p.agentID, false)
				changed = true
			}
		} else {
			if s.clients[p.clientID] == p {
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
	p.conn.SetReadLimit(16 << 20)
	_ = p.conn.SetReadDeadline(time.Now().Add(65 * time.Second))
	p.conn.SetPongHandler(func(string) error { return p.conn.SetReadDeadline(time.Now().Add(65 * time.Second)) })
	for {
		var env protocol.Envelope
		if p.conn.ReadJSON(&env) != nil {
			return
		}
		if time.Now().After(p.expires) {
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
		if p.role == protocol.RoleClient {
			if env.Type != protocol.TypeClientMessage || !json.Valid(env.Message) {
				s.send(p, protocol.Envelope{Type: protocol.TypeError, Error: "invalid RPC envelope"})
				continue
			}
			s.mu.RLock()
			a := s.agents[p.agentID]
			s.mu.RUnlock()
			if a == nil {
				s.send(p, protocol.Envelope{Type: protocol.TypeError, Error: "agent offline", StreamID: env.StreamID})
				continue
			}
			env.ClientID = p.clientID
			env.SeqID = s.seq.Add(1)
			s.send(a, env)
		} else if env.Type == protocol.TypeServerMessage {
			env.SeqID = s.seq.Add(1)
			s.mu.RLock()
			list := []*peer{}
			if env.ClientID != "" {
				if c := s.clients[env.ClientID]; c != nil && c.agentID == p.agentID {
					list = append(list, c)
				}
			} else {
				for _, c := range s.byAgent[p.agentID] {
					list = append(list, c)
				}
			}
			s.mu.RUnlock()
			for _, c := range list {
				s.send(c, env)
			}
		}
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
	s.send(p, protocol.Envelope{Type: kind, Status: status, Agent: &a.AgentInfo})
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
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, p := range s.agents {
		p.close()
	}
	for _, p := range s.clients {
		p.close()
	}
}
