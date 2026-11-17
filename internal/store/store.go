package store

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
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
	ClientID      string    `json:"clientId"`
	Token         string    `json:"token"`
	EnvironmentID string    `json:"environmentId"`
	AgentID       string    `json:"agentId"`
	ClientName    string    `json:"clientName"`
	DeviceType    string    `json:"deviceType"`
	ExpiresAt     time.Time `json:"expiresAt"`
	CreatedAt     time.Time `json:"createdAt"`
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
	agents   map[string]*AgentRecord
	clients  map[string]*ClientRecord
	pairings map[string]*PairingRecord
}

type persistShape struct {
	Agents   map[string]*AgentRecord   `json:"agents"`
	Clients  map[string]*ClientRecord  `json:"clients"`
	Pairings map[string]*PairingRecord `json:"pairings"`
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
	if err == nil && len(data) > 0 {
		var shape persistShape
		if err := json.Unmarshal(data, &shape); err != nil {
			return nil, err
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
	for _, c := range s.clients {
		c.ExpiresAt = time.Time{}
	}
	canonical := map[string]*PairingRecord{}
	for _, p := range s.pairings {
		canonical[p.PairingCode] = p
	}
	s.pairings = canonical
	return s, nil
}

func (s *Store) persistShapeLocked(shape persistShape) error {
	data, err := json.MarshalIndent(shape, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func randomToken() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func manualCode() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := make([]byte, 8)
	_, _ = rand.Read(b)
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
	updated.Online = false
	agents := maps.Clone(s.agents)
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
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.agents[agentID]
	if !ok {
		return nil, ErrNotFound
	}
	now := time.Now().UTC()
	rec := &PairingRecord{
		PairingCode:   "pair_" + uuid.NewString(),
		EnvironmentID: a.EnvironmentID,
		AgentID:       agentID,
		ExpiresAt:     now.Add(10 * time.Minute),
		CreatedAt:     now,
	}
	if manual {
		rec.ManualPairingCode = manualCode()
	}
	pairings := maps.Clone(s.pairings)
	pairings[rec.PairingCode] = rec
	for code, old := range pairings {
		if now.After(old.ExpiresAt) {
			delete(pairings, code)
		}
	}
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: s.clients, Pairings: pairings}); err != nil {
		return nil, err
	}
	s.pairings = pairings
	cp := *rec
	return &cp, nil
}

func (s *Store) ClaimPairing(code, clientName, deviceType string) (*ClientRecord, *AgentRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	rec, ok := s.findPairingLocked(code)
	if !ok {
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
	claimed := *rec
	claimed.Claimed = true
	client := &ClientRecord{
		ClientID:      "cli_" + uuid.NewString(),
		Token:         randomToken(),
		EnvironmentID: rec.EnvironmentID,
		AgentID:       rec.AgentID,
		ClientName:    clientName,
		DeviceType:    deviceType,
		CreatedAt:     now,
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

func (s *Store) PairingStatus(code string) (bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	rec, ok := s.findPairingLocked(code)
	if !ok {
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
