package store_test

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/store"
)

func TestExclusiveStateAndPairingOwnership(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	first, err := store.OpenExclusive(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = first.Close() })
	if second, err := store.OpenExclusive(path); err == nil {
		_ = second.Close()
		t.Fatal("second relay acquired a live data directory")
	}
	agent, err := first.EnrollAgent(protocol.EnrollRequest{InstallationID: "fixture"})
	if err != nil {
		t.Fatal(err)
	}
	pair, err := first.StartPairing(agent.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := first.PairingStatus("other", pair.ManualPairingCode); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("other Agent received pairing status: %v", err)
	}
	if _, err := first.PairingStatus(agent.AgentID, pair.ManualPairingCode); err != nil {
		t.Fatal(err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	second, err := store.OpenExclusive(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = second.Close() })
	if _, err = second.AuthenticateAgent(agent.Token); err != nil {
		t.Fatal(err)
	}
}

func TestExistingEmptyStateFailsClosed(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := os.WriteFile(path, nil, 0600); err != nil {
		t.Fatal(err)
	}
	if st, err := store.Open(path); err == nil || st != nil {
		t.Fatal("existing empty file was treated as a fresh relay")
	}
}
