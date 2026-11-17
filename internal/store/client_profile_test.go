package store_test

import (
	"errors"
	"path/filepath"
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/store"
)

func TestClientProfileUpdateIsScopedAndPersistent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	s, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	a, err := s.EnrollAgent(protocol.EnrollRequest{InstallationID: "profile-host"})
	if err != nil {
		t.Fatal(err)
	}
	var clients []*store.ClientRecord
	for range 2 {
		pair, err := s.StartPairing(a.AgentID, true)
		if err != nil {
			t.Fatal(err)
		}
		c, _, err := s.ClaimPairing(pair.PairingCode, "CodexRM Web", "browser")
		if err != nil {
			t.Fatal(err)
		}
		clients = append(clients, c)
	}
	name := "ANGLE (AMD, AMD Radeon 780M Graphics (0x00001900) Direct3D11 vs_5_0 ps_5_0, D3D11)"
	if err := s.UpdateClientProfile(clients[0].Token, name, "desktop"); err != nil {
		t.Fatal(err)
	}
	s, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	updated, err := s.AuthenticateClient(clients[0].Token)
	if err != nil {
		t.Fatal(err)
	}
	if updated.ClientName != name || updated.DeviceType != "desktop" || updated.ClientID != clients[0].ClientID || updated.AgentID != clients[0].AgentID || !updated.CreatedAt.Equal(clients[0].CreatedAt) {
		t.Fatalf("profile update changed identity or lost the GPU: %+v", updated)
	}
	other, err := s.AuthenticateClient(clients[1].Token)
	if err != nil || other.ClientName != "CodexRM Web" {
		t.Fatal("profile update changed another client")
	}
	if err := s.UpdateClientProfile("invalid", name, "desktop"); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("unauthorized update accepted: %v", err)
	}
	if err := s.RevokeClient(clients[0].Token); err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateClientProfile(clients[0].Token, name, "desktop"); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("revoked client updated its profile: %v", err)
	}
}
