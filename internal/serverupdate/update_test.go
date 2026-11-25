package serverupdate

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/updater"
)

func makeArchive(t *testing.T, extra string, mode os.FileMode) []byte {
	t.Helper()
	var b bytes.Buffer
	w := zip.NewWriter(&b)
	entries := map[string]string{binaryName: "new-binary", "web/index.html": "new-index", "web/app.js": "new-app", "web/sw.js": "new-sw"}
	if extra != "" {
		entries[extra] = "extra"
	}
	for name, body := range entries {
		h := &zip.FileHeader{Name: name, Method: zip.Deflate}
		if name == extra {
			h.SetMode(mode)
		}
		entry, err := w.CreateHeader(h)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := fmt.Fprint(entry, body); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

func TestServerUpdate(t *testing.T) {
	for _, tc := range []struct {
		name, actions, wantError string
		wantDownload, wantNew    bool
	}{
		{"success", "stop,start", "", true, true},
		{"rollback startup", "stop,start,stop,restart", "previous version restored", true, false},
		{"rollback health", "stop,start,stop,restart", "previous version restored", true, false},
		{"rollback failure", "stop,start,stop,restart", "staging retained", true, false},
		{"bad hash", "", "SHA-256", true, false},
		{"bad length", "", "size", true, false},
		{"bad archive", "", "entry", true, false},
		{"symlink", "", "unsupported", true, false},
		{"same version", "", "", false, false},
		{"older release", "", "", false, false},
		{"unpublished", "", "", false, false},
		{"http download redirect", "", "redirect", true, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			write := func(file, value string) {
				t.Helper()
				name := filepath.Join(root, file)
				if err := os.MkdirAll(filepath.Dir(name), 0755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(name, []byte(value), 0644); err != nil {
					t.Fatal(err)
				}
			}
			read := func(file string) string {
				t.Helper()
				b, err := os.ReadFile(filepath.Join(root, file))
				if err != nil {
					t.Fatal(err)
				}
				return string(b)
			}
			write("bin/"+binaryName, "old-binary")
			write("web/index.html", "old-index")
			write("server.env", "configuration")
			write("data/state.json", "pairings")
			history := os.Symlink("../data/state.json", filepath.Join(root, "web", historyName))
			if history != nil {
				// Some Windows test hosts do not grant permission to create symlinks.
				t.Logf("not testing release history link: %v", history)
			}
			var extra string
			var mode os.FileMode
			if tc.name == "bad archive" {
				extra = "web/../../escape"
			}
			if tc.name == "symlink" {
				extra, mode = "web/link", os.ModeSymlink|0777
			}
			payload := makeArchive(t, extra, mode)
			m := updater.Manifest{VersionCode: 3, VersionName: "0.1.2", SHA256: fmt.Sprintf("%x", sha256.Sum256(payload)), FileSize: int64(len(payload)), DownloadURL: "/package"}
			switch tc.name {
			case "bad hash":
				m.SHA256 = strings.Repeat("0", 64)
			case "bad length":
				m.FileSize++
			case "same version":
				m.VersionCode = 2
			case "older release":
				m.VersionCode = 1
			}
			var downloaded atomic.Bool
			feed := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				switch req.URL.Path {
				case "/" + release.ServerManifestName:
					if tc.name == "unpublished" {
						http.NotFound(w, req)
						return
					}
					_ = json.NewEncoder(w).Encode(m)
				case "/package":
					downloaded.Store(true)
					if tc.name == "http download redirect" {
						http.Redirect(w, req, "http://example.invalid/package", http.StatusFound)
						return
					}
					_, _ = w.Write(payload)
				default:
					http.NotFound(w, req)
				}
			}))
			defer feed.Close()
			url, err := ParseFeed(feed.URL + "/" + release.ServerManifestName)
			if err != nil {
				t.Fatal(err)
			}
			var actions []string
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			err = (Runner{
				Root: root, Feed: url, Client: feed.Client(),
				Service: func(_ context.Context, action string) error {
					actions = append(actions, action)
					if (tc.name == "rollback startup" || tc.name == "rollback failure") && action == "start" {
						return errors.New("start failed")
					}
					if tc.name == "rollback failure" && action == "restart" {
						return errors.New("restart failed")
					}
					return nil
				},
				Health: func(context.Context) (release.ServerHealth, error) {
					if read("bin/"+binaryName) == "old-binary" {
						return release.ServerHealth{VersionName: "0.1.1", VersionCode: 2}, nil
					}
					if tc.name == "rollback health" {
						cancel() // End the polling window after the first mismatched version.
						return release.ServerHealth{VersionName: "0.1.1", VersionCode: 2}, nil
					}
					return release.ServerHealth{VersionName: "0.1.2", VersionCode: 3}, nil
				},
			}).Run(ctx)
			if (tc.wantError == "" && err != nil) || (tc.wantError != "" && (err == nil || !strings.Contains(err.Error(), tc.wantError))) {
				t.Fatalf("result: %v, wanted %q", err, tc.wantError)
			}
			if downloaded.Load() != tc.wantDownload || strings.Join(actions, ",") != tc.actions {
				t.Fatalf("download=%v actions=%v", downloaded.Load(), actions)
			}
			prefix := "old"
			if tc.wantNew {
				prefix = "new"
			}
			if read("bin/"+binaryName) != prefix+"-binary" || read("web/index.html") != prefix+"-index" || read("server.env") != "configuration" || read("data/state.json") != "pairings" {
				t.Fatal("binary, web, or state were not preserved as expected")
			}
			if history == nil && read("web/"+historyName) != "pairings" {
				t.Fatal("release history symlink was not preserved")
			}
			stages, err := filepath.Glob(filepath.Join(root, ".server-update-*"))
			if err != nil || (len(stages) > 0) != (tc.name == "rollback failure") {
				t.Fatalf("staged files: %v, %v", stages, err)
			}
		})
	}
}
