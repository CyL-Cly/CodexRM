package store_test

import (
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/store"
)

func TestEnrollPairClaim(t *testing.T) {
	dir := t.TempDir()
	st, err := store.Open(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	agent, err := st.EnrollAgent(protocol.EnrollRequest{
		Name: "host", OS: "windows", Arch: "amd64", AppVersion: "0.1.0", InstallationID: "inst-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	pair, err := st.StartPairing(agent.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	if pair.ManualPairingCode == "" {
		t.Fatal("expected manual code")
	}
	client, ag, err := st.ClaimPairing(pair.ManualPairingCode, "web", "browser")
	if err != nil {
		t.Fatal(err)
	}
	if client.AgentID != agent.AgentID || ag.AgentID != agent.AgentID {
		t.Fatal("agent mismatch")
	}
	if _, _, err := st.ClaimPairing(pair.ManualPairingCode, "web", "browser"); err == nil {
		t.Fatal("expected already claimed")
	}
}

func TestPairAliasCannotBeReusedAfterRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	st, e := store.Open(path)
	if e != nil {
		t.Fatal(e)
	}
	a, e := st.EnrollAgent(protocol.EnrollRequest{InstallationID: "persist"})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = st.EnrollAgent(protocol.EnrollRequest{InstallationID: "persist"}); !errors.Is(e, store.ErrUnauthorized) {
		t.Fatal("installation ID is not a credential")
	}
	st.SetAgentOnline(a.AgentID, true)
	p, e := st.StartPairing(a.AgentID, true)
	if e != nil {
		t.Fatal(e)
	}
	st, e = store.Open(path)
	if e != nil {
		t.Fatal(e)
	}
	loaded, _ := st.GetAgent(a.AgentID)
	if loaded.Online {
		t.Fatal("persisted online flag trusted")
	}
	code := strings.ToLower(strings.ReplaceAll(p.ManualPairingCode, "-", ""))
	if _, _, e = st.ClaimPairing(code, "web", "browser"); e != nil {
		t.Fatal(e)
	}
	st, e = store.Open(path)
	if e != nil {
		t.Fatal(e)
	}
	if _, _, e = st.ClaimPairing(p.PairingCode, "web", "browser"); !errors.Is(e, store.ErrAlreadyClaimed) {
		t.Fatalf("reused pair: %v", e)
	}
}
