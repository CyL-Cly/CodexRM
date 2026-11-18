package server_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/agent"
	"github.com/codexrm/codexrm/internal/protocol"
)

func TestAgentImagesOverAuthenticatedRelay(t *testing.T) {
	_, h := newServer(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	root := t.TempDir()
	a, err := agent.New(agent.Config{ServerURL: h.URL, DataDir: t.TempDir(), Cwd: root, Mock: true, EnrollmentKey: "test-key"})
	if err != nil {
		t.Fatal(err)
	}
	pair, err := a.Pair(ctx)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- a.Run(ctx) }()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("Agent did not stop")
		}
	})
	var claimed protocol.PairClaimResponse
	post(t, h, "/api/v1/client/claim", "", "", protocol.PairClaimRequest{Code: pair.ManualPairingCode}, &claimed)
	c := ws(t, h, "client", claimed.Token)
	until(t, c, func(e protocol.Envelope) bool { return e.Status == "connected" })
	rpc(t, c, 1, "codexrm/info", nil)
	if response(t, c, 1)["images"] != true {
		t.Fatal("Agent did not advertise image reads")
	}
	png, _ := base64.StdEncoding.DecodeString("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aA3sAAAAASUVORK5CYII=")
	data := append(png, bytes.Repeat([]byte{0}, 300000)...)
	if err := os.WriteFile(filepath.Join(root, "generated.png"), data, 0600); err != nil {
		t.Fatal(err)
	}
	var got []byte
	version := ""
	for id := 2; len(got) < len(data); id++ {
		rpc(t, c, id, "codexrm/image/read", map[string]any{"path": "generated.png", "cwd": root, "offset": len(got), "version": version})
		result := response(t, c, id)
		chunk, err := base64.StdEncoding.DecodeString(result["data"].(string))
		if err != nil {
			t.Fatal(err)
		}
		got = append(got, chunk...)
		if result["offset"] != float64(len(got)) {
			t.Fatal("incorrect download offset")
		}
		file := result["file"].(map[string]any)
		version = file["version"].(string)
		if file["mimeType"] != "image/png" {
			t.Fatal("image type lost in relay")
		}
	}
	if !bytes.Equal(got, data) {
		t.Fatal("image bytes changed in relay")
	}
}
