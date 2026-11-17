package server

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/gorilla/websocket"
)

func TestRevocationBetweenClientAuthAndUpgrade(t *testing.T) {
	for _, mode := range []string{"revoke", "delete"} {
		t.Run(mode, func(t *testing.T) {
			s, err := New(Config{DataDir: t.TempDir(), AdminToken: "test-key"})
			if err != nil {
				t.Fatal(err)
			}
			h := httptest.NewServer(s.Handler())
			t.Cleanup(func() { s.Close(); h.Close() })

			agent, err := s.store.EnrollAgent(protocol.EnrollRequest{InstallationID: "agent"})
			if err != nil {
				t.Fatal(err)
			}
			pairing, err := s.store.StartPairing(agent.AgentID, true)
			if err != nil {
				t.Fatal(err)
			}
			client, _, err := s.store.ClaimPairing(pairing.ManualPairingCode, "browser", "browser")
			if err != nil {
				t.Fatal(err)
			}

			authenticated := make(chan struct{})
			continueUpgrade := make(chan struct{})
			s.beforeClientUpgrade = func() {
				close(authenticated)
				<-continueUpgrade
			}
			type dialResult struct {
				conn *websocket.Conn
				err  error
			}
			dialed := make(chan dialResult, 1)
			go func() {
				conn, _, dialErr := websocket.DefaultDialer.Dial(
					"ws"+strings.TrimPrefix(h.URL, "http")+"/api/v1/client/ws",
					http.Header{"Authorization": []string{"Bearer " + client.Token}},
				)
				dialed <- dialResult{conn: conn, err: dialErr}
			}()
			select {
			case <-authenticated:
			case <-time.After(5 * time.Second):
				t.Fatal("client did not reach the post-authentication barrier")
			}

			method, path, token, body := http.MethodPost, "/api/v1/client/revoke", client.Token, []byte("{}")
			if mode == "delete" {
				method, path, token, body = http.MethodDelete, "/api/v1/agent/clients/"+client.ClientID, agent.Token, nil
			}
			req, err := http.NewRequest(method, h.URL+path, bytes.NewReader(body))
			if err != nil {
				t.Fatal(err)
			}
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("Content-Type", "application/json")
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			_, _ = io.Copy(io.Discard, resp.Body)
			_ = resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("%s status: %s", mode, resp.Status)
			}
			close(continueUpgrade)

			var result dialResult
			select {
			case result = <-dialed:
			case <-time.After(5 * time.Second):
				t.Fatal("client upgrade did not finish")
			}
			if result.err != nil {
				t.Fatalf("websocket handshake: %v", result.err)
			}
			defer result.conn.Close()
			_ = result.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			var envelope protocol.Envelope
			if err = result.conn.ReadJSON(&envelope); err == nil {
				t.Fatalf("revoked client received %q instead of being disconnected", envelope.Type)
			}

			s.mu.RLock()
			peer := s.clients[client.ClientID]
			byAgent := s.byAgent[agent.AgentID][client.ClientID]
			s.mu.RUnlock()
			if peer != nil || byAgent != nil {
				t.Fatal("rejected upgrade left a registered client peer")
			}
		})
	}
}
