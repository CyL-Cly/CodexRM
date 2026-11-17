package store_test

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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
	if !client.ExpiresAt.IsZero() {
		t.Fatalf("client authorization must not expire: %v", client.ExpiresAt)
	}
	if _, err := st.AuthenticateClient(client.Token); err != nil {
		t.Fatalf("permanent client authorization rejected: %v", err)
	}
	if _, _, err := st.ClaimPairing(pair.ManualPairingCode, "web", "browser"); err == nil {
		t.Fatal("expected already claimed")
	}
}

func TestClientManagementIsScopedAndPersistent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	a, err := st.EnrollAgent(protocol.EnrollRequest{InstallationID: "host-a"})
	if err != nil {
		t.Fatal(err)
	}
	other, err := st.EnrollAgent(protocol.EnrollRequest{InstallationID: "host-b"})
	if err != nil {
		t.Fatal(err)
	}
	pair, err := st.StartPairing(a.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	client, _, err := st.ClaimPairing(pair.ManualPairingCode, "phone", "browser")
	if err != nil {
		t.Fatal(err)
	}
	st, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.AuthenticateClient(client.Token); err != nil {
		t.Fatalf("authorization did not survive restart: %v", err)
	}
	devices := st.ListClients(a.AgentID)
	if len(devices) != 1 || devices[0].ClientID != client.ClientID || devices[0].Token != "" {
		t.Fatalf("unexpected device list: %+v", devices)
	}
	if err = st.DeleteClient(other.AgentID, client.ClientID); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("cross-agent deletion accepted: %v", err)
	}
	if err = st.DeleteClient(a.AgentID, client.ClientID); err != nil {
		t.Fatal(err)
	}
	if _, err = st.AuthenticateClient(client.Token); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("deleted authorization still valid: %v", err)
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

func TestEnrollmentRecoveryTokenIsLimitedAndPersistent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	st, err := store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	req := protocol.EnrollRequest{InstallationID: "recoverable"}
	first, err := st.EnrollAgent(req)
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.EnrollAgent(req, first.Token)
	if err != nil {
		t.Fatal(err)
	}
	if second.PreviousToken != first.Token || !second.PreviousTokenExpiresAt.After(time.Now()) {
		t.Fatalf("missing enrollment recovery window: %+v", second)
	}
	if _, err = st.AuthenticateAgent(first.Token); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("previous token retained ordinary API access: %v", err)
	}

	st, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	third, err := st.EnrollAgent(req, first.Token)
	if err != nil {
		t.Fatalf("recovery did not survive a server restart: %v", err)
	}
	fourth, err := st.EnrollAgent(req, first.Token)
	if err != nil {
		t.Fatalf("repeated recovery with a still-unsaved token failed: %v", err)
	}
	if fourth.PreviousToken != first.Token || !fourth.PreviousTokenExpiresAt.Equal(second.PreviousTokenExpiresAt) {
		t.Fatalf("recovery replaced or extended the original grace window: first=%v fourth=%v", second.PreviousTokenExpiresAt, fourth.PreviousTokenExpiresAt)
	}
	if _, err = st.AuthenticateAgent(fourth.Token); err != nil {
		t.Fatalf("current recovered token rejected: %v", err)
	}
	if third.Token == fourth.Token {
		t.Fatal("test requires distinct rotated tokens")
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var shape map[string]any
	if err = json.Unmarshal(data, &shape); err != nil {
		t.Fatal(err)
	}
	for _, value := range shape["agents"].(map[string]any) {
		value.(map[string]any)["previousTokenExpiresAt"] = time.Now().Add(-time.Minute).UTC().Format(time.RFC3339Nano)
	}
	data, err = json.Marshal(shape)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	st, err = store.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.EnrollAgent(req, first.Token); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("expired recovery token accepted: %v", err)
	}
}

func TestCurrentTokenRenewalReplacesRecoveryToken(t *testing.T) {
	st, err := store.Open(filepath.Join(t.TempDir(), "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	req := protocol.EnrollRequest{InstallationID: "rotation"}
	first, err := st.EnrollAgent(req)
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.EnrollAgent(req, first.Token)
	if err != nil {
		t.Fatal(err)
	}
	third, err := st.EnrollAgent(req, second.Token)
	if err != nil {
		t.Fatal(err)
	}
	if third.PreviousToken != second.Token {
		t.Fatalf("recovery token %q, want immediately replaced current token", third.PreviousToken)
	}
	if _, err = st.EnrollAgent(req, first.Token); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("superseded recovery token accepted: %v", err)
	}
}
