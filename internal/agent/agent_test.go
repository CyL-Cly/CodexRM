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
	"github.com/codexrm/codexrm/internal/release"
	"github.com/gorilla/websocket"
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
	a.handle(context.Background(), app, nil, protocol.Envelope{ClientID: "owner", Message: mustJSON(map[string]any{"id": "pending", "result": map[string]any{"decision": "accept"}})}, nil)
	if p, ok := a.approvals["pending"]; !ok || p.Sending {
		t.Fatal("failed response was lost or left locked")
	}
}

func TestMaskedRateLimitIsSurfacedAsRetryLimitHint(t *testing.T) {
	cases := []struct {
		name    string
		message string
		want    string
	}{
		{
			name:    "error notification with request id",
			message: "unexpected status 503 Service Unavailable: Service temporarily unavailable, url: https://sub2api.example/responses, request id: f9531027-82e3-4af9-8186-2db6ec892ec4",
			want:    "exceeded retry limit, last status: 429 Too Many Requests, request id: f9531027-82e3-4af9-8186-2db6ec892ec4",
		},
		{
			name:    "without request id",
			message: "unexpected status 503 Service Unavailable: Service temporarily unavailable, url: https://sub2api.example/responses",
			want:    "exceeded retry limit, last status: 429 Too Many Requests",
		},
		{
			name:    "other 503 body stays untouched",
			message: "unexpected status 503 Service Unavailable: upstream connect error, url: https://sub2api.example/responses",
			want:    "unexpected status 503 Service Unavailable: upstream connect error, url: https://sub2api.example/responses",
		},
		{
			name:    "real 429 hint stays untouched",
			message: "exceeded retry limit, last status: 429 Too Many Requests, request id: 0d9e9587-da96-462a-932a-002d5a5a4d50",
			want:    "exceeded retry limit, last status: 429 Too Many Requests, request id: 0d9e9587-da96-462a-932a-002d5a5a4d50",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw := mustJSON(map[string]any{"method": "error", "params": map[string]any{
				"threadId": "thread", "turnId": "turn",
				"error": map[string]any{"message": tc.message},
			}})
			var out struct {
				Params struct {
					Error struct {
						Message string `json:"message"`
					} `json:"error"`
				} `json:"params"`
			}
			if err := json.Unmarshal(rewriteMaskedRateLimit(raw), &out); err != nil {
				t.Fatal(err)
			}
			if out.Params.Error.Message != tc.want {
				t.Fatalf("message = %q, want %q", out.Params.Error.Message, tc.want)
			}
		})
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

func TestRelayConnectionReportsCurrentVersion(t *testing.T) {
	var reported atomic.Value
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reported.Store(r.Header.Get(protocol.AppVersionHeader))
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		_ = conn.Close()
	}))
	defer server.Close()
	a := &Agent{cfg: Config{ServerURL: server.URL}, creds: creds{Token: "token", AgentID: "agent"}}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := a.session(ctx, nil); err == nil {
		t.Fatal("closed relay was treated as a live session")
	}
	if reported.Load() != release.VersionName {
		t.Fatalf("reported version %v, want %s", reported.Load(), release.VersionName)
	}
}
