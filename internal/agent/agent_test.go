package agent

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/protocol"
)

func TestPairingInstructionsIncludeScannableLink(t *testing.T) {
	a := &Agent{cfg: Config{ServerURL: "https://rm.example/base"}}
	output, err := a.PairingInstructions(&protocol.PairStartResponse{ManualPairingCode: "ABCD-EFGH", ExpiresAt: 1700000000})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(output, "https://rm.example/base/#pair=ABCD-EFGH") || !strings.Contains(output, "Scan to pair") {
		t.Fatalf("incomplete pairing instructions: %s", output)
	}
}

func TestResolvedRequestsAreNotReplayed(t *testing.T) {
	a := &Agent{owners: map[string]string{"thread": "client"}, approvals: map[string]approval{}}
	a.forward(mustJSON(map[string]any{"id": 11, "method": "item/commandExecution/requestApproval", "params": map[string]any{"threadId": "thread", "turnId": "turn"}}))
	if len(a.approvals) != 1 {
		t.Fatal("approval not cached")
	}
	a.forward(mustJSON(map[string]any{"method": "serverRequest/resolved", "params": map[string]any{"threadId": "thread", "requestId": 11}}))
	if len(a.approvals) != 0 {
		t.Fatal("resolved approval would be replayed")
	}
	a.forward(mustJSON(map[string]any{"id": "second", "method": "item/fileChange/requestApproval", "params": map[string]any{"threadId": "thread", "turnId": "turn"}}))
	a.forward(mustJSON(map[string]any{"method": "turn/completed", "params": map[string]any{"threadId": "thread", "turn": map[string]any{"id": "turn", "status": "interrupted"}}}))
	if len(a.approvals) != 0 {
		t.Fatal("cancelled turn left stale approval")
	}
}

func TestFailedApprovalWriteRemainsRetryable(t *testing.T) {
	mock, err := appserver.StartMock("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer mock.Close()
	app, err := appserver.Dial(context.Background(), "ws://"+mock.Addr)
	if err != nil {
		t.Fatal(err)
	}
	_ = app.Close()
	a := &Agent{approvals: map[string]approval{"pending": {ID: mustJSON(12), Owner: "owner"}}}
	a.handle(context.Background(), app, nil, protocol.Envelope{ClientID: "owner", Message: mustJSON(map[string]any{"id": "pending", "result": map[string]any{"decision": "accept"}})})
	if p, ok := a.approvals["pending"]; !ok || p.Sending {
		t.Fatal("failed response was lost or left locked")
	}
}

func TestUnsavedRenewalIsPersistedBeforeAnotherEnroll(t *testing.T) {
	var enrollRequests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		enrollRequests.Add(1)
		http.Error(w, "unexpected enroll", http.StatusInternalServerError)
	}))
	defer server.Close()

	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(blocker, []byte("file"), 0o600); err != nil {
		t.Fatal(err)
	}
	a := &Agent{
		cfg:        Config{ServerURL: server.URL, DataDir: filepath.Join(blocker, "state")},
		creds:      creds{Token: "renewed-token", ExpiresAt: time.Now().Add(time.Hour).Unix()},
		credsDirty: true,
	}
	if err := a.ensureEnrolled(context.Background()); err == nil {
		t.Fatal("expected the credential write to fail")
	}
	if got := enrollRequests.Load(); got != 0 {
		t.Fatalf("unsaved credentials triggered %d enrollment requests", got)
	}

	a.cfg.DataDir = t.TempDir()
	a.creds.ExpiresAt = time.Now().Add(48 * time.Hour).Unix()
	if err := a.ensureEnrolled(context.Background()); err != nil {
		t.Fatalf("retry credential write: %v", err)
	}
	if got := enrollRequests.Load(); got != 0 {
		t.Fatalf("successful retry triggered %d enrollment requests", got)
	}
	if a.credsDirty {
		t.Fatal("credentials remained dirty after a successful write")
	}
	data, err := os.ReadFile(filepath.Join(a.cfg.DataDir, "agent.json"))
	if err != nil {
		t.Fatal(err)
	}
	var saved creds
	if err = json.Unmarshal(data, &saved); err != nil {
		t.Fatal(err)
	}
	if saved.Token != "renewed-token" {
		t.Fatalf("saved token %q, want renewed token", saved.Token)
	}
}
