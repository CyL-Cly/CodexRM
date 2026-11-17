package agenthost

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/agent"
	"github.com/codexrm/codexrm/internal/clientconfig"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/server"
)

func startHost(t *testing.T, cfg clientconfig.Connection) (*Host, string, string, func()) {
	t.Helper()
	h, err := New(filepath.Join(t.TempDir(), ".state", "connection.json"), "", cfg, agent.Config{Mock: true, Cwd: t.TempDir(), DataDir: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); _ = h.Run(ctx) }()
	var address string
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
		address, err = h.SettingsURL()
		if err == nil {
			break
		}
		time.Sleep(time.Millisecond)
	}
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	u, _ := url.Parse(address)
	stop := func() {
		cancel()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("host did not stop")
		}
	}
	t.Cleanup(stop)
	return h, "http://" + u.Host, u.Fragment, stop
}

func request(t *testing.T, base, token, method, path string, input any) (int, []byte) {
	t.Helper()
	var body io.Reader
	if input != nil {
		data, _ := json.Marshal(input)
		body = bytes.NewReader(data)
	}
	req, _ := http.NewRequest(method, base+path, body)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode, b
}

func TestLocalSettingsListenerLifetimeAndAccess(t *testing.T) {
	h, base, token, stop := startHost(t, clientconfig.Connection{})
	address, err := h.SettingsURL()
	if err != nil || address != base+"/#"+token {
		t.Fatalf("listener changed: %s, %v", address, err)
	}
	if !strings.HasPrefix(base, "http://127.0.0.1:") {
		t.Fatal("not bound to IPv4 loopback")
	}
	if code, _ := request(t, base, "", "GET", "/api/settings", nil); code != 401 {
		t.Fatalf("unauthorized API: %d", code)
	}
	for _, mode := range []string{"origin", "host", "fetch-site"} {
		req, _ := http.NewRequest("PUT", base+"/api/settings", strings.NewReader(`{}`))
		req.Header.Set("Authorization", "Bearer "+token)
		switch mode {
		case "origin":
			req.Header.Set("Origin", "https://evil.example")
		case "host":
			req.Host = "evil.example"
		case "fetch-site":
			req.Header.Set("Sec-Fetch-Site", "cross-site")
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != 403 {
			t.Errorf("%s accepted: %d", mode, resp.StatusCode)
		}
	}
	code, b := request(t, base, token, "GET", "/api/settings", nil)
	if code != 200 || !bytes.Contains(b, []byte(clientconfig.DefaultUpdateServer)) {
		t.Fatalf("default settings: %d %s", code, b)
	}
	stop()
	u, _ := url.Parse(base)
	conn, err := net.DialTimeout("tcp", u.Host, 100*time.Millisecond)
	if err == nil {
		conn.Close()
		t.Fatal("settings port remained open after shutdown")
	}
}

func TestUnconfiguredStartupOpensWorkingLocalSettings(t *testing.T) {
	h, err := New(filepath.Join(t.TempDir(), ".state", "connection.json"), "", clientconfig.Connection{}, agent.Config{})
	if err != nil {
		t.Fatal(err)
	}
	if h.page.server != nil {
		t.Fatal("settings listener started before opening settings")
	}
	opened := make(chan string, 1)
	h.openBrowser = func(address string) error { opened <- address; return nil }
	h.OpenOnStart = !h.Configured()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { defer close(done); _ = h.Run(ctx) }()
	defer func() { cancel(); <-done }()
	select {
	case address := <-opened:
		u, _ := url.Parse(address)
		if code, _ := request(t, "http://"+u.Host, u.Fragment, "GET", "/api/settings", nil); code != 200 {
			t.Fatal("browser opened before settings were available")
		}
	case <-time.After(time.Second):
		t.Fatal("unconfigured startup did not open settings")
	}
}

func TestSettingsSaveFailureDoesNotApplyAndServerChangeClearsOldKey(t *testing.T) {
	h, base, token, _ := startHost(t, clientconfig.Connection{Server: "http://127.0.0.1:1", EnrollmentKey: "old-secret"})
	code, b := request(t, base, token, "GET", "/api/settings", nil)
	if code != 200 || bytes.Contains(b, []byte("old-secret")) {
		t.Fatal("settings exposed the saved key")
	}
	input := map[string]any{"server": "http://127.0.0.1:2", "updateServer": clientconfig.DefaultUpdateServer}
	if err := os.MkdirAll(h.path, 0700); err != nil {
		t.Fatal(err)
	}
	code, _ = request(t, base, token, "PUT", "/api/settings", input)
	if code == 200 || h.snapshot()["server"] != "http://127.0.0.1:1" {
		t.Fatal("failed persistence changed live configuration")
	}
	if err := os.Remove(h.path); err != nil {
		t.Fatal(err)
	}
	code, b = request(t, base, token, "PUT", "/api/settings", input)
	if code != 200 {
		t.Fatalf("save: %d %s", code, b)
	}
	cfg, err := clientconfig.Load(h.path)
	if err != nil || cfg.Server != "http://127.0.0.1:2" || cfg.EnrollmentKey != "" {
		t.Fatalf("cross-server credential leak: %+v, %v", cfg, err)
	}
	input["enrollmentKey"] = "new-secret"
	if code, _ := request(t, base, token, "PUT", "/api/settings", input); code != 200 {
		t.Fatal("new key not saved")
	}
	delete(input, "enrollmentKey")
	if code, _ := request(t, base, token, "PUT", "/api/settings", input); code != 200 {
		t.Fatal("unchanged key not retained")
	}
	cfg, err = clientconfig.Load(h.path)
	if err != nil || cfg.EnrollmentKey != "new-secret" {
		t.Fatal("blank form erased the saved key")
	}
}

func TestSettingsPairListAndRevokeAgainstRelay(t *testing.T) {
	relay, err := server.New(server.Config{DataDir: t.TempDir(), AdminToken: "relay-key"})
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	remote := httptest.NewServer(relay.Handler())
	defer remote.Close()
	h, base, token, _ := startHost(t, clientconfig.Connection{Server: remote.URL, EnrollmentKey: "relay-key"})
	for deadline := time.Now().Add(5 * time.Second); ; {
		if h.snapshot()["status"] == "已连接" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("no relay connection: %+v", h.snapshot())
		}
		time.Sleep(10 * time.Millisecond)
	}
	code, b := request(t, base, token, "POST", "/api/pair", nil)
	if code != 200 {
		t.Fatalf("pair: %d %s", code, b)
	}
	var pair struct{ Code, URL, QR string }
	if err := json.Unmarshal(b, &pair); err != nil {
		t.Fatal(err)
	}
	if pair.Code == "" || !strings.HasPrefix(pair.QR, "data:image/png;base64,") || !strings.HasPrefix(pair.URL, remote.URL+"/#pair=") {
		t.Fatalf("incomplete pair response: %s", b)
	}
	code, b = request(t, remote.URL, "", "POST", "/api/v1/client/claim", map[string]string{"code": pair.Code, "clientName": "test browser", "deviceType": "desktop"})
	if code != 200 {
		t.Fatalf("claim: %d %s", code, b)
	}
	var claimed protocol.PairClaimResponse
	_ = json.Unmarshal(b, &claimed)
	code, b = request(t, base, token, "GET", "/api/devices", nil)
	if code != 200 || !bytes.Contains(b, []byte(claimed.ClientID)) {
		t.Fatalf("device missing: %d %s", code, b)
	}
	code, b = request(t, base, token, "DELETE", "/api/devices/"+claimed.ClientID, nil)
	if code != 200 {
		t.Fatalf("revoke: %d %s", code, b)
	}
	code, b = request(t, base, token, "GET", "/api/devices", nil)
	if code != 200 || strings.TrimSpace(string(b)) != "[]" {
		t.Fatalf("revoked device remained: %s", b)
	}
}
