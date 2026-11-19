package server_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestRobotClientCannotCallDesktopOrReceiveBroadcasts(t *testing.T) {
	_, h := newServer(t)
	var enrolled protocol.EnrollResponse
	if status := post(t, h, "/api/v1/agent/enroll", "", "test-key", protocol.EnrollRequest{Name: "robot-test", InstallationID: "robot-install"}, &enrolled); status != 200 {
		t.Fatalf("enroll %d", status)
	}
	var pair protocol.PairStartResponse
	post(t, h, "/api/v1/agent/pair", enrolled.Token, "", map[string]any{}, &pair)
	var browser protocol.PairClaimResponse
	post(t, h, "/api/v1/client/claim", "", "", protocol.PairClaimRequest{Code: pair.ManualPairingCode, ClientName: "browser", DeviceType: "browser"}, &browser)
	var pairRobot protocol.PairStartResponse
	post(t, h, "/api/v1/agent/pair", enrolled.Token, "", map[string]any{}, &pairRobot)
	var robot protocol.PairClaimResponse
	post(t, h, "/api/v1/client/claim", "", "", protocol.PairClaimRequest{Code: pairRobot.ManualPairingCode, ClientName: "wx", DeviceType: protocol.DeviceTypeRobot}, &robot)

	agentConn := ws(t, h, "agent", enrolled.Token)
	browserConn := ws(t, h, "client", browser.Token)
	robotConn := ws(t, h, "client", robot.Token)
	_ = read(t, browserConn)
	_ = read(t, robotConn)
	rpc(t, robotConn, 1, "codexrm/desktop/close", map[string]any{})
	denied := until(t, robotConn, func(env protocol.Envelope) bool { return env.Type == protocol.TypeError })
	if denied.Error != "method not allowed" {
		t.Fatalf("robot desktop call: %#v", denied)
	}
	write(t, agentConn, protocol.Envelope{Type: protocol.TypeServerMessage, Message: raw(map[string]any{"method": "thread/started", "params": map[string]any{"thread": map[string]any{"id": "t"}}})})
	gotBrowser := until(t, browserConn, func(env protocol.Envelope) bool {
		return env.Type == protocol.TypeServerMessage && len(env.Message) > 0
	})
	if !json.Valid(gotBrowser.Message) {
		t.Fatal("browser missed broadcast")
	}
	_ = robotConn.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	var leaked protocol.Envelope
	if err := robotConn.ReadJSON(&leaked); err == nil && leaked.Type == protocol.TypeServerMessage {
		t.Fatalf("robot received broadcast: %#v", leaked)
	}
}

func TestRobotProfileCannotEscalate(t *testing.T) {
	_, h := newServer(t)
	var enrolled protocol.EnrollResponse
	post(t, h, "/api/v1/agent/enroll", "", "test-key", protocol.EnrollRequest{Name: "robot-test", InstallationID: "robot-profile"}, &enrolled)
	var pair protocol.PairStartResponse
	post(t, h, "/api/v1/agent/pair", enrolled.Token, "", map[string]any{}, &pair)
	var robot protocol.PairClaimResponse
	post(t, h, "/api/v1/client/claim", "", "", protocol.PairClaimRequest{Code: pair.ManualPairingCode, DeviceType: protocol.DeviceTypeRobot}, &robot)
	if status := post(t, h, "/api/v1/client/profile", robot.Token, "", protocol.ClientProfileRequest{ClientName: "wx", DeviceType: "browser"}, nil); status != 400 {
		t.Fatalf("escalation status %d", status)
	}
}
