package server

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/store"
	"github.com/gorilla/websocket"
)

func TestClientConnectionIPHeartbeatAndCleanup(t *testing.T) {
	s, err := New(Config{DataDir: t.TempDir(), AdminToken: "key"})
	if err != nil {
		t.Fatal(err)
	}
	h := httptest.NewServer(s.Handler())
	t.Cleanup(func() { s.Close(); h.Close() })
	a, err := s.store.EnrollAgent(protocol.EnrollRequest{InstallationID: "connection"})
	if err != nil {
		t.Fatal(err)
	}
	pair, err := s.store.StartPairing(a.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	claim := httptest.NewRequest(http.MethodPost, "/api/v1/client/claim", strings.NewReader(`{"code":"`+pair.PairingCode+`","clientName":"phone","deviceType":"mobile"}`))
	claim.RemoteAddr = "192.0.2.10:4321"
	claim.Header.Set("X-Real-IP", "192.0.2.99")
	claimed := httptest.NewRecorder()
	s.Handler().ServeHTTP(claimed, claim)
	if claimed.Code != 200 {
		t.Fatalf("claim: %s", claimed.Body.String())
	}
	var client protocol.PairClaimResponse
	if err := json.Unmarshal(claimed.Body.Bytes(), &client); err != nil {
		t.Fatal(err)
	}
	row := s.store.ListClients(a.AgentID)[0]
	if row.LastIP != "192.0.2.10" || row.LastConnectedAt.IsZero() {
		t.Fatalf("claim activity/spoofed IP: %+v", row)
	}

	// A loopback reverse proxy overwrites X-Real-IP. The successful WebSocket
	// must replace the pairing IP with the address of the new connection.
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(h.URL, "http")+"/api/v1/client/ws", http.Header{"Authorization": []string{"Bearer " + client.Token}, "X-Real-IP": []string{"2001:db8::42"}})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var env protocol.Envelope
	if err := conn.ReadJSON(&env); err != nil {
		t.Fatal(err)
	}
	if env.Type != protocol.TypeWelcome {
		t.Fatalf("welcome: %+v", env)
	}
	row = s.store.ListClients(a.AgentID)[0]
	if row.LastIP != "2001:db8::42" {
		t.Fatalf("reconnect did not update IP: %+v", row)
	}
	old := time.Now().Add(-2 * time.Minute)
	if err := s.store.RecordClientConnection(client.ClientID, row.LastIP, old, false); err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteJSON(protocol.Envelope{Type: protocol.TypePing, PingID: "activity"}); err != nil {
		t.Fatal(err)
	}
	if err := conn.ReadJSON(&env); err != nil {
		t.Fatal(err)
	}
	row = s.store.ListClients(a.AgentID)[0]
	if env.Type != protocol.TypePong || !row.LastConnectedAt.After(old) {
		t.Fatalf("application heartbeat did not refresh activity: %+v", row)
	}
	// Exercise native WebSocket pong handling too, without waiting 20 seconds.
	if err := s.store.RecordClientConnection(client.ClientID, row.LastIP, old, false); err != nil {
		t.Fatal(err)
	}
	if err := conn.WriteControl(websocket.PongMessage, nil, time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	for deadline := time.Now().Add(5 * time.Second); !s.store.ListClients(a.AgentID)[0].LastConnectedAt.After(old); {
		if time.Now().After(deadline) {
			t.Fatal("native heartbeat did not refresh activity")
		}
		time.Sleep(time.Millisecond)
	}
	if !s.store.ListClients(a.AgentID)[0].LastConnectedAt.After(old) {
		t.Fatal("native heartbeat did not refresh activity")
	}

	list := httptest.NewRequest(http.MethodGet, "/api/v1/agent/clients", nil)
	list.Header.Set("Authorization", "Bearer "+a.Token)
	listed := httptest.NewRecorder()
	s.Handler().ServeHTTP(listed, list)
	var out struct {
		Data []protocol.ClientInfo `json:"data"`
	}
	if err := json.Unmarshal(listed.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if listed.Code != 200 || len(out.Data) != 1 || out.Data[0].LastIP != row.LastIP || out.Data[0].LastConnectedAt == "" || strings.Contains(listed.Body.String(), client.Token) {
		t.Fatalf("device API: %s", listed.Body.String())
	}
	obsolete := &peer{role: protocol.RoleClient, clientID: client.ClientID, ip: "192.0.2.123"}
	if err := s.recordClientHeartbeat(obsolete); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("replaced connection heartbeat was accepted: %v", err)
	}
	if s.store.ListClients(a.AgentID)[0].LastIP != row.LastIP {
		t.Fatal("replaced connection overwrote the current IP")
	}

	future := time.Now().Add(store.ClientRetention + time.Hour)
	if err := s.pruneInactiveClients(future); err != nil {
		t.Fatal(err)
	}
	if len(s.store.ListClients(a.AgentID)) != 1 {
		t.Fatal("cleanup removed a continuously connected device")
	}
	// Mark the registered peer closed under the same lock cleanup uses.
	s.mu.Lock()
	s.clients[client.ClientID].close()
	s.mu.Unlock()
	if err := s.pruneInactiveClients(future); err != nil {
		t.Fatal(err)
	}
	if _, err := s.store.AuthenticateClient(client.Token); !errors.Is(err, store.ErrUnauthorized) {
		t.Fatalf("closed inactive device retained authorization: %v", err)
	}
}

func TestServerStartupRemovesInactiveDevices(t *testing.T) {
	dir := t.TempDir()
	s, err := New(Config{DataDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	a, err := s.store.EnrollAgent(protocol.EnrollRequest{InstallationID: "startup"})
	if err != nil {
		t.Fatal(err)
	}
	p, err := s.store.StartPairing(a.AgentID, true)
	if err != nil {
		t.Fatal(err)
	}
	c, _, err := s.store.ClaimPairing(p.PairingCode, "phone", "mobile", "192.0.2.1")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.store.RecordClientConnection(c.ClientID, c.LastIP, time.Now().Add(-store.ClientRetention-time.Hour), false); err != nil {
		t.Fatal(err)
	}
	s.Close()
	s, err = New(Config{DataDir: dir})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if len(s.store.ListClients(a.AgentID)) != 0 {
		t.Fatal("startup did not remove inactive device")
	}
	if _, err := s.store.GetAgent(a.AgentID); err != nil {
		t.Fatalf("cleanup removed owning Agent: %v", err)
	}
}
