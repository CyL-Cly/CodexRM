package store

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
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

type AgentRecord struct {
	protocol.AgentInfo
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expiresAt"`
	CreatedAt time.Time `json:"createdAt"`
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
	canonical := map[string]*PairingRecord{}
	for _, p := range s.pairings {
		canonical[p.PairingCode] = p
	}
	s.pairings = canonical
	return s, nil
}

func (s *Store) persistLocked() error {
	shape := persistShape{Agents: s.agents, Clients: s.clients, Pairings: s.pairings}
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
	if existing != nil && (len(previousToken) == 0 || previousToken[0] == "" || previousToken[0] != existing.Token) {
		return nil, ErrUnauthorized
	}
	if existing == nil {
		existing = &AgentRecord{
			AgentInfo: protocol.AgentInfo{
				AgentID:        "agt_" + uuid.NewString(),
				InstallationID: req.InstallationID,
				EnvironmentID:  "env_" + uuid.NewString(),
			},
			CreatedAt: now,
		}
	}
	if existing.EnvironmentID == "" {
		existing.EnvironmentID = "env_" + uuid.NewString()
	}
	if existing.AgentID == "" {
		existing.AgentID = "agt_" + uuid.NewString()
	}
	existing.ServerName = req.Name
	existing.OS = req.OS
	existing.Arch = req.Arch
	existing.AppVersion = req.AppVersion
	existing.InstallationID = req.InstallationID
	existing.Token = randomToken()
	existing.ExpiresAt = now.Add(30 * 24 * time.Hour)
	if existing.CreatedAt.IsZero() {
		existing.CreatedAt = now
	}
	existing.Online = false
	s.agents[existing.AgentID] = existing
	if err := s.persistLocked(); err != nil {
		return nil, err
	}
	cp := *existing
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
	s.pairings[rec.PairingCode] = rec
	for code, old := range s.pairings {
		if now.After(old.ExpiresAt) {
			delete(s.pairings, code)
		}
	}
	if err := s.persistLocked(); err != nil {
		return nil, err
	}
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
	rec.Claimed = true
	client := &ClientRecord{
		ClientID:      "cli_" + uuid.NewString(),
		Token:         randomToken(),
		EnvironmentID: rec.EnvironmentID,
		AgentID:       rec.AgentID,
		ClientName:    clientName,
		DeviceType:    deviceType,
		ExpiresAt:     now.Add(30 * 24 * time.Hour),
		CreatedAt:     now,
	}
	s.clients[client.ClientID] = client
	if err := s.persistLocked(); err != nil {
		return nil, nil, err
	}
	ccp := *client
	acp := *a
	return &ccp, &acp, nil
}

func (s *Store) AuthenticateClient(token string) (*ClientRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.clients {
		if c.Token == token {
			if time.Now().After(c.ExpiresAt) {
				return nil, ErrExpired
			}
			cp := *c
			return &cp, nil
		}
	}
	return nil, ErrUnauthorized
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
			delete(s.clients, id)
			return s.persistLocked()
		}
	}
	return ErrUnauthorized
}
