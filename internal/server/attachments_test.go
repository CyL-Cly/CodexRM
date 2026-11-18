package server_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/agent"
	"github.com/codexrm/codexrm/internal/protocol"
)

func TestAgentAttachmentsOverRelayAndTurnHistory(t *testing.T) {
	_, h := newServer(t)
	page, err := http.Get(h.URL)
	if err != nil {
		t.Fatal(err)
	}
	_ = page.Body.Close()
	if !strings.Contains(page.Header.Get("Content-Security-Policy"), "img-src 'self' data: blob:") {
		t.Fatal("page policy blocks attachment previews")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	a, err := agent.New(agent.Config{ServerURL: h.URL, DataDir: t.TempDir(), Cwd: t.TempDir(), Mock: true, EnrollmentKey: "test-key"})
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
	id := 0
	call := func(method string, params any) map[string]any {
		t.Helper()
		id++
		rpc(t, c, id, method, params)
		return response(t, c, id)
	}
	info := call("codexrm/info", nil)
	if info["attachments"].(map[string]any)["maxSize"] != float64(20<<20) {
		t.Fatal("Agent did not advertise attachments")
	}
	// The whole file exceeds the relay's per-frame limit; every RPC remains small.
	data := bytes.Repeat([]byte{'a'}, (16<<20)+123)
	upload := call("codexrm/attachment/begin", map[string]any{"name": "报告.txt", "size": len(data)})
	fileID := upload["id"]
	chunkSize := int(upload["chunkSize"].(float64))
	for offset := 0; offset < len(data); {
		end := min(offset+chunkSize, len(data))
		result := call("codexrm/attachment/chunk", map[string]any{"id": fileID, "offset": offset, "data": base64.StdEncoding.EncodeToString(data[offset:end])})
		offset = int(result["offset"].(float64))
	}
	file := call("codexrm/attachment/finish", map[string]any{"id": fileID})
	got, err := os.ReadFile(file["path"].(string))
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("Agent received different bytes: %v", err)
	}
	read := call("codexrm/attachment/read", map[string]any{"id": fileID, "offset": len(data) - 20})
	if read["data"] != base64.StdEncoding.EncodeToString(data[len(data)-20:]) {
		t.Fatal("download through the relay differs")
	}
	thread := call("thread/start", map[string]any{})["thread"].(map[string]any)
	input := []any{
		map[string]any{"type": "text", "text": "请读取附件\n[CodexRM attachment] " + string(raw(file)), "text_elements": []any{}},
		map[string]any{"type": "localImage", "path": "test-image.png"},
	}
	turn := call("turn/start", map[string]any{"threadId": thread["id"], "input": input})["turn"].(map[string]any)
	content := turn["items"].([]any)[0].(map[string]any)["content"]
	if !bytes.Equal(raw(content), raw(input)) {
		t.Fatalf("turn lost attachment content: %s", raw(content))
	}
	readThread := call("thread/read", map[string]any{"threadId": thread["id"], "includeTurns": true})["thread"].(map[string]any)
	saved := readThread["turns"].([]any)[0].(map[string]any)["items"].([]any)[0].(map[string]any)["content"]
	if !bytes.Equal(raw(saved), raw(input)) {
		t.Fatal("reopened history lost attachment content")
	}
}
