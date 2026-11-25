package store

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"maps"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/google/uuid"
)

var (
	ErrNotFound       = errors.New("not found")
	ErrExpired        = errors.New("expired")
	ErrAlreadyClaimed = errors.New("already claimed")
	ErrUnauthorized   = errors.New("unauthorized")
)

const enrollmentRecoveryWindow = 10 * time.Minute

type AgentRecord struct {
	protocol.AgentInfo
	Token                  string    `json:"token"`
	PreviousToken          string    `json:"previousToken,omitempty"`
	PreviousTokenExpiresAt time.Time `json:"previousTokenExpiresAt,omitempty"`
	ExpiresAt              time.Time `json:"expiresAt"`
	CreatedAt              time.Time `json:"createdAt"`
}

type ClientRecord struct {
	Secure          bool      `json:"secure,omitempty"`
	ClientID        string    `json:"clientId"`
	Token           string    `json:"token"`
	EnvironmentID   string    `json:"environmentId"`
	AgentID         string    `json:"agentId"`
	ClientName      string    `json:"clientName"`
	DeviceType      string    `json:"deviceType"`
	ExpiresAt       time.Time `json:"expiresAt"`
	CreatedAt       time.Time `json:"createdAt"`
	LastConnectedAt time.Time `json:"lastConnectedAt,omitempty"`
	LastIP          string    `json:"lastIP,omitempty"`
	// RetentionStartedAt gives records without historical activity a migration grace period.
	RetentionStartedAt time.Time `json:"retentionStartedAt,omitempty"`
}

type PairingRecord struct {
	PairingCode       string    `json:"pairingCode"`
	ManualPairingCode string    `json:"manualPairingCode"`
	EnvironmentID     string    `json:"environmentId"`
	AgentID           string    `json:"agentId"`
	Claimed           bool      `json:"claimed"`
	ExpiresAt         time.Time `json:"expiresAt"`
	CreatedAt         time.Time `json:"createdAt"`
}

type Store struct {
	mu       sync.RWMutex
	path     string
	lock     *os.File
	closed   bool
	public   bool
	agents   map[string]*AgentRecord
	clients  map[string]*ClientRecord
	pairings map[string]*PairingRecord
}

type persistShape struct {
	Agents   map[string]*AgentRecord   `json:"agents"`
	Clients  map[string]*ClientRecord  `json:"clients"`
	Pairings map[string]*PairingRecord `json:"pairings"`
}

// OpenExclusive holds an operating system lock for the relay's lifetime.
func OpenExclusive(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	lock, err := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err := lockState(lock); err != nil {
		_ = lock.Close()
		return nil, fmt.Errorf("lock relay state directory: %w", err)
	}
	s, err := Open(path)
	if err != nil {
		_ = lock.Close()
		return nil, err
	}
	s.lock = lock
	return s, nil
}

// LimitPublicEnrollment caps JSON rewrite costs on public relays. Expired,
// unpaired installations can be reclaimed without affecting paired clients.
func (s *Store) LimitPublicEnrollment() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.public = true
}

var ErrCapacity = errors.New("relay registration capacity reached")

func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true
	if s.lock == nil {
		return nil
	}
	return s.lock.Close()
}

func Open(path string) (*Store, error) {
	s := &Store{
		path:     path,
		agents:   map[string]*AgentRecord{},
		clients:  map[string]*ClientRecord{},
		pairings: map[string]*PairingRecord{},
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if err == nil {
		var shape persistShape
		if err := json.Unmarshal(data, &shape); err != nil {
			return nil, fmt.Errorf("read relay state: %w", err)
		}
		if shape.Agents == nil || shape.Clients == nil || shape.Pairings == nil {
			return nil, errors.New("relay state is missing required collections")
		}
		for _, agent := range shape.Agents {
			if agent == nil {
				return nil, errors.New("relay state contains a null agent")
			}
		}
		for _, client := range shape.Clients {
			if client == nil {
				return nil, errors.New("relay state contains a null client")
			}
		}
		for _, pairing := range shape.Pairings {
			if pairing == nil {
				return nil, errors.New("relay state contains a null pairing")
			}
		}
		if shape.Agents != nil {
			s.agents = shape.Agents
		}
		if shape.Clients != nil {
			s.clients = shape.Clients
		}
		if shape.Pairings != nil {
			s.pairings = shape.Pairings
		}
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	for _, a := range s.agents {
		a.Online = false
	}
	migrated := false
	for _, c := range s.clients {
		c.ExpiresAt = time.Time{}
		if c.LastConnectedAt.IsZero() && c.RetentionStartedAt.IsZero() {
			c.RetentionStartedAt = time.Now().UTC()
			migrated = true
		}
	}
	canonical := map[string]*PairingRecord{}
	for _, p := range s.pairings {
		canonical[p.PairingCode] = p
	}
	s.pairings = canonical
	if migrated {
		if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: s.clients, Pairings: s.pairings}); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) persistShapeLocked(shape persistShape) error {
	if s.closed {
		return os.ErrClosed
	}
	data, err := json.MarshalIndent(shape, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".state-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err = tmp.Write(data); err == nil {
		err = tmp.Sync()
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return replaceState(tmp.Name(), s.path)
}

func randomToken() string {
	b := make([]byte, 24)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func manualCode() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 8)
	rand.Read(b)
	chars := make([]byte, 8)
	for i := 0; i < 8; i++ {
		chars[i] = alphabet[int(b[i])%len(alphabet)]
	}
	return string(chars[:4]) + "-" + string(chars[4:])
}

func (s *Store) EnrollAgent(req protocol.EnrollRequest, previousToken ...string) (*AgentRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now().UTC()
	var existing *AgentRecord
	for _, a := range s.agents {
		if a.InstallationID == req.InstallationID {
			existing = a
			break
		}
	}
	recovering := false
	if existing != nil {
		token := ""
		if len(previousToken) > 0 {
			token = previousToken[0]
		}
		switch {
		case token != "" && token == existing.Token:
		case token != "" && token == existing.PreviousToken && now.Before(existing.PreviousTokenExpiresAt):
			recovering = true
		default:
			return nil, ErrUnauthorized
		}
	}
	// Recovery is idempotent: every process holding the previous token receives
	// the same current credential. Rotating here would strand a process that
	// successfully enrolled concurrently but has not saved its response yet.
	if recovering {
		cp := *existing
		return &cp, nil
	}
	updated := &AgentRecord{CreatedAt: now}
	if existing != nil {
		*updated = *existing
	}
	if updated.EnvironmentID == "" {
		updated.EnvironmentID = "env_" + uuid.NewString()
	}
	if updated.AgentID == "" {
		updated.AgentID = "agt_" + uuid.NewString()
	}
	updated.ServerName = req.Name
	updated.OS = req.OS
	updated.Arch = req.Arch
	updated.AppVersion = req.AppVersion
	updated.InstallationID = req.InstallationID
	if existing != nil && !recovering {
		updated.PreviousToken = existing.Token
		updated.PreviousTokenExpiresAt = now.Add(enrollmentRecoveryWindow)
	}
	updated.Token = randomToken()
	updated.ExpiresAt = now.Add(30 * 24 * time.Hour)
	agents := maps.Clone(s.agents)
	if s.public && existing == nil && len(agents) >= 2048 {
		retained := make(map[string]bool)
		for _, client := range s.clients {
			retained[client.AgentID] = true
		}
		for _, pair := range s.pairings {
			if now.Before(pair.ExpiresAt) {
				retained[pair.AgentID] = true
			}
		}
		for key, agent := range agents {
			if !agent.Online && !retained[key] && now.After(agent.ExpiresAt.Add(enrollmentRecoveryWindow)) {
				delete(agents, key)
			}
		}
		if len(agents) >= 2048 {
			return nil, ErrCapacity
		}
	}
	agents[updated.AgentID] = updated
	if err := s.persistShapeLocked(persistShape{Agents: agents, Clients: s.clients, Pairings: s.pairings}); err != nil {
		return nil, err
	}
	s.agents = agents
	cp := *updated
	return &cp, nil
}

func (s *Store) AuthenticateAgent(token string) (*AgentRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, a := range s.agents {
		if a.Token == token {
			if time.Now().After(a.ExpiresAt) {
				return nil, ErrExpired
			}
			cp := *a
			return &cp, nil
		}
	}
	return nil, ErrUnauthorized
}

func (s *Store) SetAgentOnline(agentID string, online bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if a, ok := s.agents[agentID]; ok {
		a.Online = online
	}
}

func normalizeAppVersion(version string) (string, bool) {
	version = strings.TrimSpace(version)
	if version == "" || len(version) > 64 {
		return "", false
	}
	for _, r := range version {
		if r < 0x20 || r == 0x7f {
			return "", false
		}
	}
	return version, true
}

// SetAgentAppVersion records the build reported by a connected agent.
// An empty or invalid value leaves the registered version unchanged.
func (s *Store) SetAgentAppVersion(agentID, version string) error {
	version, ok := normalizeAppVersion(version)
	if !ok {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	existing, found := s.agents[agentID]
	if !found {
		return ErrNotFound
	}
	if existing.AppVersion == version {
		return nil
	}
	updated := *existing
	updated.AppVersion = version
	agents := maps.Clone(s.agents)
	agents[agentID] = &updated
	if err := s.persistShapeLocked(persistShape{Agents: agents, Clients: s.clients, Pairings: s.pairings}); err != nil {
		return err
	}
	s.agents = agents
	return nil
}

func (s *Store) GetAgent(agentID string) (*AgentRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	a, ok := s.agents[agentID]
	if !ok {
		return nil, ErrNotFound
	}
	cp := *a
	return &cp, nil
}

func (s *Store) ListAgents() []protocol.AgentInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]protocol.AgentInfo, 0, len(s.agents))
	for _, a := range s.agents {
		out = append(out, a.AgentInfo)
	}
	return out
}

func (s *Store) StartPairing(agentID string, manual bool) (*PairingRecord, error) {
	return s.startPairing(agentID, manual, "pair_")
}

// StartSecurePairing allocates an opaque route; the invitation secret stays on the Agent.
func (s *Store) StartSecurePairing(agentID string) (*PairingRecord, error) {
	return s.startPairing(agentID, false, "route_")
}

func (s *Store) startPairing(agentID string, manual bool, prefix string) (*PairingRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.agents[agentID]
	if !ok {
		return nil, ErrNotFound
	}
	now := time.Now().UTC()
	rec := &PairingRecord{
		PairingCode:   prefix + uuid.NewString(),
		EnvironmentID: a.EnvironmentID,
		AgentID:       agentID,
		ExpiresAt:     now.Add(10 * time.Minute),
		CreatedAt:     now,
	}
	pairings := maps.Clone(s.pairings)
	for code, old := range pairings {
		if now.After(old.ExpiresAt) {
			delete(pairings, code)
		}
	}
	if s.public && len(pairings) >= 4096 {
		return nil, ErrCapacity
	}
	if manual {
		for range 16 {
			candidate := manualCode()
			if _, used := s.findPairingLocked(candidate); !used {
				rec.ManualPairingCode = candidate
				break
			}
		}
		if rec.ManualPairingCode == "" {
			return nil, errors.New("cannot allocate unique manual pairing code")
		}
	}
	pairings[rec.PairingCode] = rec
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: s.clients, Pairings: pairings}); err != nil {
		return nil, err
	}
	s.pairings = pairings
	cp := *rec
	return &cp, nil
}

func (s *Store) ClaimPairing(code, clientName, deviceType string, ip ...string) (*ClientRecord, *AgentRecord, error) {
	return s.claimPairing(code, clientName, deviceType, false, ip...)
}

func (s *Store) ClaimSecureRoute(route, clientName, deviceType, ip string) (*ClientRecord, *AgentRecord, error) {
	return s.claimPairing(route, clientName, deviceType, true, ip)
}

func (s *Store) claimPairing(code, clientName, deviceType string, secure bool, ip ...string) (*ClientRecord, *AgentRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	rec, ok := s.findPairingLocked(code)
	if !ok || strings.HasPrefix(rec.PairingCode, "route_") != secure {
		return nil, nil, ErrNotFound
	}
	if rec.Claimed {
		return nil, nil, ErrAlreadyClaimed
	}
	if now.After(rec.ExpiresAt) {
		return nil, nil, ErrExpired
	}
	a, ok := s.agents[rec.AgentID]
	if !ok {
		return nil, nil, ErrNotFound
	}
	if s.public && len(s.clients) >= 8192 {
		return nil, nil, ErrCapacity
	}
	claimed := *rec
	claimed.Claimed = true
	client := &ClientRecord{
		Secure:          secure,
		ClientID:        "cli_" + uuid.NewString(),
		Token:           randomToken(),
		EnvironmentID:   rec.EnvironmentID,
		AgentID:         rec.AgentID,
		ClientName:      clientName,
		DeviceType:      deviceType,
		CreatedAt:       now,
		LastConnectedAt: now,
	}
	if len(ip) > 0 {
		client.LastIP = ip[0]
	}
	pairings := maps.Clone(s.pairings)
	pairings[claimed.PairingCode] = &claimed
	clients := maps.Clone(s.clients)
	clients[client.ClientID] = client
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: clients, Pairings: pairings}); err != nil {
		return nil, nil, err
	}
	s.pairings, s.clients = pairings, clients
	ccp := *client
	acp := *a
	return &ccp, &acp, nil
}

func (s *Store) AuthenticateClient(token string) (*ClientRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.clients {
		if c.Token == token {
			if clientInactive(c, time.Now()) {
				return nil, ErrExpired
			}
			if !c.ExpiresAt.IsZero() && time.Now().After(c.ExpiresAt) {
				return nil, ErrExpired
			}
			cp := *c
			return &cp, nil
		}
	}
	return nil, ErrUnauthorized
}

// UpdateClientProfile can only change the client identified by its own token.
func (s *Store) UpdateClientProfile(token, name, deviceType string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, c := range s.clients {
		if c.Token != token {
			continue
		}
		if clientInactive(c, time.Now()) {
			return ErrExpired
		}
		if !c.ExpiresAt.IsZero() && time.Now().After(c.ExpiresAt) {
			return ErrExpired
		}
		if c.ClientName == name && c.DeviceType == deviceType {
			return nil
		}
		updated := *c
		updated.ClientName, updated.DeviceType = name, deviceType
		clients := maps.Clone(s.clients)
		clients[id] = &updated
		if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: clients, Pairings: s.pairings}); err != nil {
			return err
		}
		s.clients = clients
		return nil
	}
	return ErrUnauthorized
}

func (s *Store) PairingStatus(agentID, code string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	rec, ok := s.findPairingLocked(code)
	if !ok || rec.AgentID != agentID || time.Now().After(rec.ExpiresAt) {
		return false, ErrNotFound
	}
	return rec.Claimed, nil
}

func (s *Store) findPairingLocked(code string) (*PairingRecord, bool) {
	code = strings.TrimSpace(code)
	if rec, ok := s.pairings[code]; ok {
		return rec, true
	}
	normalized := strings.ReplaceAll(strings.ToUpper(code), "-", "")
	for _, rec := range s.pairings {
		if rec.ManualPairingCode != "" && strings.ReplaceAll(rec.ManualPairingCode, "-", "") == normalized {
			return rec, true
		}
	}
	return nil, false
}

func (s *Store) RevokeClient(token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, c := range s.clients {
		if c.Token == token {
			clients := maps.Clone(s.clients)
			delete(clients, id)
			if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: clients, Pairings: s.pairings}); err != nil {
				return err
			}
			s.clients = clients
			return nil
		}
	}
	return ErrUnauthorized
}

func (s *Store) ListClients(agentID string) []ClientRecord {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]ClientRecord, 0)
	for _, c := range s.clients {
		if c.AgentID == agentID {
			cp := *c
			cp.Token = ""
			out = append(out, cp)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out
}

func (s *Store) DeleteClient(agentID, clientID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	c, ok := s.clients[clientID]
	if !ok || c.AgentID != agentID {
		return ErrNotFound
	}
	clients := maps.Clone(s.clients)
	delete(clients, clientID)
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: clients, Pairings: s.pairings}); err != nil {
		return err
	}
	s.clients = clients
	return nil
}
