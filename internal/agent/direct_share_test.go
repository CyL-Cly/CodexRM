package agent

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/google/uuid"
)

func TestReceiveDirectShare(t *testing.T) {
	for _, tc := range []struct {
		name, data string
		size       int64
		accepted   bool
	}{
		{"complete", "abc", 3, true},
		{"empty", "", 0, true},
		{"truncated", "ab", 3, false},
		{"oversized", "abcd", 3, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "incoming", "file")
			if err := os.Mkdir(filepath.Dir(path), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path+".part", nil, 0600); err != nil {
				t.Fatal(err)
			}
			upload := &attachmentUpload{File: attachment{Path: path, Size: tc.size}, Owner: "phone", Offset: -1, DirectID: "session", Streamed: true}
			a := &Agent{uploads: map[string]*attachmentUpload{"upload": upload}}
			for _, d := range []*directSession{
				{id: "session", client: "other-phone", ctx: context.Background()},
				{id: "other-session", client: "phone", ctx: context.Background()},
			} {
				if err := a.receiveDirectShare(d, "upload", bytes.NewBufferString(tc.data)); err == nil {
					t.Fatal("unrelated direct session could write the upload")
				}
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			d := &directSession{id: "session", client: "phone", ctx: ctx}
			err := a.receiveDirectShare(d, "upload", bytes.NewBufferString(tc.data))
			if (err == nil) != tc.accepted {
				t.Fatalf("received error: %v", err)
			}
			if tc.accepted {
				data, err := os.ReadFile(path + ".part")
				if err != nil || string(data) != tc.data || upload.Offset != tc.size {
					t.Fatalf("wrong recorded content or offset: %q offset=%d error=%v", data, upload.Offset, err)
				}
				cancel()
				a.clearDirectShares(d)
			}
			if _, err := os.Stat(path + ".part"); !os.IsNotExist(err) || a.uploads["upload"] != nil {
				t.Fatalf("abandoned upload still exists: %v", err)
			}
		})
	}
}

func TestDesktopShareRPCRequiresOriginalDirectSession(t *testing.T) {
	id := uuid.NewString()
	a := &Agent{cfg: Config{DataDir: t.TempDir()}, uploads: map[string]*attachmentUpload{
		id: {Owner: "phone", DirectID: "original", Updated: time.Now()},
	}}
	denied := func(operation string, params map[string]any, direct *directSession) {
		t.Helper()
		_, err := a.attachmentRPC("phone", "codexrm/attachment/"+operation, params, direct)
		if err == nil || err.Error() != protocol.DesktopShareDirectRequired {
			t.Fatalf("%s: expected P2P requirement, got %v", operation, err)
		}
	}
	denied("begin", map[string]any{"destination": protocol.DesktopShareDestination}, nil)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	for _, operation := range []string{"chunk", "finish", "abort"} {
		params := map[string]any{"id": id}
		denied(operation, params, nil)
		denied(operation, params, &directSession{id: "replacement", ctx: context.Background()})
		denied(operation, params, &directSession{id: "original", ctx: ctx})
	}
}
