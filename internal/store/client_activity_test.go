package store

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

func activityClient(t *testing.T, s *Store, installation string) *ClientRecord {
	t.Helper()
	a, err := s.EnrollAgent(protocol.EnrollRequest{InstallationID: installation})
	if err != nil {
		t.Fatal(err)
	}
	p, err := s.StartPairing(a.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	c, _, err := s.ClaimPairing(p.PairingCode, "phone", "mobile", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestClientActivityPersistsAndThrottlesHeartbeats(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	c := activityClient(t, s, "activity")
	connected := time.Now().UTC()
	if err := s.RecordClientConnection(c.ClientID, "2001:db8::1", connected, false); err != nil {
		t.Fatal(err)
	}
	if err := s.RecordClientConnection(c.ClientID, "2001:db8::1", connected.Add(20*time.Second), true); err != nil {
		t.Fatal(err)
	}
	if !s.ListClients(c.AgentID)[0].LastConnectedAt.Equal(connected) {
		t.Fatal("heartbeat was not throttled")
	}
	later := connected.Add(time.Minute)
	if err := s.RecordClientConnection(c.ClientID, "2001:db8::1", later, true); err != nil {
		t.Fatal(err)
	}
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	row := s.ListClients(c.AgentID)[0]
	if !row.LastConnectedAt.Equal(later) || row.LastIP != "2001:db8::1" || row.Token != "" {
		t.Fatalf("activity did not survive restart: %+v", row)
	}
}

func TestPruneInactiveClientsBoundaryAndActiveConnections(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	stale := activityClient(t, s, "stale")
	boundary := activityClient(t, s, "boundary")
	active := activityClient(t, s, "active")
	recent := activityClient(t, s, "recent")
	for _, c := range []*ClientRecord{stale, active} {
		if err := s.RecordClientConnection(c.ClientID, c.LastIP, now.Add(-ClientRetention-time.Second), false); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.RecordClientConnection(boundary.ClientID, boundary.LastIP, now.Add(-ClientRetention), false); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AuthenticateClient(stale.Token); !errors.Is(err, ErrExpired) {
		t.Fatalf("inactive token remained valid before sweep: %v", err)
	}
	if err := s.UpdateClientProfile(stale.Token, "renamed", "mobile"); !errors.Is(err, ErrExpired) {
		t.Fatalf("profile revived inactive token: %v", err)
	}
	removed, err := s.PruneInactiveClients(now, map[string]bool{active.ClientID: true})
	if err != nil || removed != 1 {
		t.Fatalf("prune: removed=%d err=%v", removed, err)
	}
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AuthenticateClient(stale.Token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("removed token returned after restart: %v", err)
	}
	for _, c := range []*ClientRecord{boundary, active, recent} {
		if len(s.ListClients(c.AgentID)) != 1 {
			t.Fatalf("kept client missing: %s", c.ClientID)
		}
	}
	removed, err = s.PruneInactiveClients(now.Add(time.Nanosecond), nil)
	if err != nil || removed != 2 {
		t.Fatalf("closed/boundary clients not removed: %d, %v", removed, err)
	}
}

func TestLegacyClientsReceivePersistentGraceWithoutInventingConnectionHistory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	c := activityClient(t, s, "legacy")
	s.clients[c.ClientID].CreatedAt = time.Now().Add(-90 * 24 * time.Hour)
	s.clients[c.ClientID].LastConnectedAt = time.Time{}
	s.clients[c.ClientID].LastIP = ""
	if err := s.persistShapeLocked(persistShape{Agents: s.agents, Clients: s.clients, Pairings: s.pairings}); err != nil {
		t.Fatal(err)
	}
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	legacy, err := s.AuthenticateClient(c.Token)
	if err != nil {
		t.Fatal(err)
	}
	if !legacy.LastConnectedAt.IsZero() || legacy.LastIP != "" || legacy.RetentionStartedAt.IsZero() {
		t.Fatalf("invalid migration: %+v", legacy)
	}
	s, err = Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if !s.clients[c.ClientID].RetentionStartedAt.Equal(legacy.RetentionStartedAt) {
		t.Fatal("restart extended grace period")
	}
	if n, err := s.PruneInactiveClients(legacy.RetentionStartedAt.Add(ClientRetention+time.Second), nil); err != nil || n != 1 {
		t.Fatalf("legacy grace never expired: %d %v", n, err)
	}
}

func TestActivityAndCleanupPersistenceFailureKeepsLiveState(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	c := activityClient(t, s, "failure")
	blocker := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocker, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	s.path = filepath.Join(blocker, "state.json")
	if err := s.RecordClientConnection(c.ClientID, "192.0.2.2", time.Now(), false); err == nil {
		t.Fatal("expected activity persistence failure")
	}
	row := s.ListClients(c.AgentID)[0]
	if row.LastIP != c.LastIP || !row.LastConnectedAt.Equal(c.LastConnectedAt) {
		t.Fatal("failed write changed activity")
	}
	if n, err := s.PruneInactiveClients(time.Now().Add(ClientRetention+time.Hour), nil); err == nil || n != 0 {
		t.Fatalf("expected cleanup persistence failure: %d %v", n, err)
	}
	if _, err := s.AuthenticateClient(c.Token); err != nil {
		t.Fatalf("failed cleanup removed authorization: %v", err)
	}
}
