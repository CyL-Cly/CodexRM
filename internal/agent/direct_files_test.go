package agent

import (
	"context"
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestWorkspaceFileDownloadBoundaries(t *testing.T) {
	root, outside := t.TempDir(), t.TempDir()
	private := filepath.Join(root, "agent-state")
	if err := os.Mkdir(private, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(private, "agent.json"), []byte("private"), 0600); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(root, "产物.apk")
	f, err := os.Create(file)
	if err != nil {
		t.Fatal(err)
	}
	const largeOffset = 21 << 20
	if _, err := f.WriteAt([]byte("end"), largeOffset); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	a := &Agent{cfg: Config{Cwd: root, DataDir: private}}
	d := &directSession{}
	t.Cleanup(d.files.close)
	ctx := context.Background()
	call := func(d *directSession, method string, p map[string]any) (any, error) {
		return a.directFileRPC(ctx, nil, d, protocol.DirectFilePrefix+method, p)
	}
	params := func(path string) map[string]any { return map[string]any{"workspace": root, "path": path} }
	if _, err := call(nil, "open", params(file)); err == nil {
		t.Fatal("relay allowed a file read")
	}
	for _, path := range []string{"../outside", outside, "agent-state/agent.json"} {
		if _, err := call(d, "open", params(path)); err == nil {
			t.Fatalf("opened protected path %q", path)
		}
	}
	if err := os.Symlink(outside, filepath.Join(root, "escape")); err == nil {
		if _, err := call(d, "list", params("escape")); err == nil {
			t.Fatal("followed a link outside the workspace")
		}
	}
	listing, err := call(d, "list", params(""))
	if err != nil {
		t.Fatal(err)
	}
	entries := listing.(map[string]any)["entries"].([]directFileEntry)
	if len(entries) != 1 || entries[0].Name != "产物.apk" || entries[0].Size != largeOffset+3 {
		t.Fatalf("listing: %+v", entries)
	}
	started, err := call(d, "open", params(file))
	if err != nil {
		t.Fatal(err)
	}
	id := started.(map[string]any)["id"]
	read := func(session *directSession, offset float64) (any, error) {
		return call(session, "read", map[string]any{"id": id, "offset": offset})
	}
	if _, err := read(&directSession{}, 0); err == nil {
		t.Fatal("another connection read an unrelated handle")
	}
	if _, err := read(d, 1); err == nil {
		t.Fatal("accepted a nonsequential offset")
	}
	for offset := 0; offset <= largeOffset; offset += attachmentChunkSize {
		result, err := read(d, float64(offset))
		if err != nil {
			t.Fatalf("read at %d: %v", offset, err)
		}
		if offset == largeOffset {
			data, err := base64.StdEncoding.DecodeString(result.(map[string]any)["data"].(string))
			if err != nil || string(data) != "end" {
				t.Fatalf("last chunk: %q, %v", data, err)
			}
		}
	}
	if d.files.read != nil {
		t.Fatal("completed handle was not released")
	}
}
