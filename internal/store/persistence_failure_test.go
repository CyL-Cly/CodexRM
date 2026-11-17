package store

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestFailedPersistenceDoesNotMutateLiveState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	s, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	agent, err := s.EnrollAgent(protocol.EnrollRequest{InstallationID: "atomic"})
	if err != nil {
		t.Fatal(err)
	}
	agent, err = s.EnrollAgent(protocol.EnrollRequest{InstallationID: "atomic"}, agent.Token)
	if err != nil {
		t.Fatal(err)
	}
	pairing, err := s.StartPairing(agent.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	client, _, err := s.ClaimPairing(pairing.ManualPairingCode, "browser", "browser")
	if err != nil {
		t.Fatal(err)
	}
	unclaimed, err := s.StartPairing(agent.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}

	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	if err = os.WriteFile(blocker, []byte("file"), 0o600); err != nil {
		t.Fatal(err)
	}
	s.path = filepath.Join(blocker, "state.json")
	agentCounts, clientCounts, pairingCounts := len(s.agents), len(s.clients), len(s.pairings)

	if updated, enrollErr := s.EnrollAgent(protocol.EnrollRequest{InstallationID: "atomic"}, agent.Token); enrollErr == nil || updated != nil {
		t.Fatalf("enroll should fail before returning a record: %+v, %v", updated, enrollErr)
	}
	unchangedAgent, err := s.GetAgent(agent.AgentID)
	if err != nil {
		t.Fatal(err)
	}
	if unchangedAgent.Token != agent.Token || unchangedAgent.PreviousToken != agent.PreviousToken || !unchangedAgent.PreviousTokenExpiresAt.Equal(agent.PreviousTokenExpiresAt) || !unchangedAgent.ExpiresAt.Equal(agent.ExpiresAt) {
		t.Fatal("failed enrollment changed live current or recovery credentials")
	}
	if _, err = s.StartPairing(agent.AgentID, true); err == nil {
		t.Fatal("pairing creation unexpectedly persisted")
	}
	if _, _, err = s.ClaimPairing(unclaimed.ManualPairingCode, "other", "browser"); err == nil {
		t.Fatal("pairing claim unexpectedly persisted")
	}
	if s.pairings[unclaimed.PairingCode].Claimed {
		t.Fatal("failed claim consumed the pairing")
	}
	if err = s.UpdateClientProfile(client.Token, "new GPU", "desktop"); err == nil {
		t.Fatal("client profile unexpectedly persisted")
	}
	unchangedClient, err := s.AuthenticateClient(client.Token)
	if err != nil || unchangedClient.ClientName != client.ClientName || unchangedClient.DeviceType != client.DeviceType {
		t.Fatal("failed profile persistence changed the live client")
	}
	if err = s.RevokeClient(client.Token); err == nil {
		t.Fatal("client revocation unexpectedly persisted")
	}
	if err = s.DeleteClient(agent.AgentID, client.ClientID); err == nil {
		t.Fatal("client deletion unexpectedly persisted")
	}
	if len(s.agents) != agentCounts || len(s.clients) != clientCounts || len(s.pairings) != pairingCounts {
		t.Fatal("failed persistence changed the number of live records")
	}
	if s.clients[client.ClientID] == nil {
		t.Fatal("failed revoke/delete removed the live client")
	}

	s.path = path
	recovered, err := s.EnrollAgent(protocol.EnrollRequest{InstallationID: "atomic"}, agent.Token)
	if err != nil {
		t.Fatalf("current token could not recover after a transient write failure: %v", err)
	}
	if recovered.Token == agent.Token || recovered.PreviousToken != agent.Token || !recovered.PreviousTokenExpiresAt.After(time.Now()) {
		t.Fatalf("unexpected credentials after recovery: %+v", recovered)
	}
}
