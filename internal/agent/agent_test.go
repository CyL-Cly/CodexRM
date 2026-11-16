package agent

import (
	"context"
	"testing"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/protocol"
)

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
