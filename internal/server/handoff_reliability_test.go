package server

import (
	"encoding/json"
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestOutboundQueueUsesWireByteBudget(t *testing.T) {
	p := &peer{send: make(chan []byte, 256), closed: make(chan struct{})}
	frame := make([]byte, protocol.MaxEnvelopeSize)
	if !p.enqueue(frame) || !p.enqueue(frame) || p.enqueue([]byte{1}) {
		t.Fatal("outbound queue accepted more than its byte budget")
	}
	p.close()
	if len(p.send) != 0 || p.queuedBytes.Load() != 0 {
		t.Fatal("closed peer retained queued frames")
	}
}

func TestDispatchRechecksClientAfterRemoval(t *testing.T) {
	p := &peer{agentID: "agent", clientID: "client", send: make(chan []byte, 1), closed: make(chan struct{})}
	a := &peer{send: make(chan []byte, 2), closed: make(chan struct{})}
	s := &Server{clients: map[string]*peer{p.clientID: p}, agents: map[string]*peer{p.agentID: a}}
	request := protocol.Envelope{Type: protocol.TypeClientMessage, Message: json.RawMessage(`{"id":1,"method":"ping"}`)}
	s.dispatchClientRPC(p, request)
	if len(a.send) != 1 {
		t.Fatal("valid client did not reach the Agent")
	}
	if err := s.removeClient(p.clientID, p.agentID, func() error { return nil }); err != nil {
		t.Fatal(err)
	}
	s.dispatchClientRPC(p, request)
	if len(a.send) != 1 {
		t.Fatal("already read RPC reached the Agent after client removal")
	}
}
