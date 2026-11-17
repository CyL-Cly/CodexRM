package publisher

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/codexrm/codexrm/internal/updater"
)

func TestPublicationUploadsOnlyZIPAndPreparesRMManifest(t *testing.T) {
	for _, mode := range []string{"success", "corrupt download", "upload failure", "bad local hash"} {
		t.Run(mode, func(t *testing.T) {
			files := map[string][]byte{}
			sessions := map[string]string{}
			var events []string
			var server *httptest.Server
			server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				reply := func(value any) { _ = json.NewEncoder(w).Encode(map[string]any{"code": 0, "data": value}) }
				if strings.HasPrefix(r.URL.Path, "/public/") {
					if r.Header.Get("Authorization") != "" {
						t.Error("anonymous verification leaked an access token")
					}
					name := strings.TrimPrefix(r.URL.Path, "/public/")
					events = append(events, "get:"+name)
					data, ok := files[name]
					if !ok {
						http.NotFound(w, r)
						return
					}
					if mode == "corrupt download" && name == "client.zip" {
						data = []byte("bad data")
					}
					_, _ = w.Write(data)
					return
				}
				if r.URL.Path == "/api/v4/session/token" {
					var credentials map[string]string
					_ = json.NewDecoder(r.Body).Decode(&credentials)
					if credentials["password"] != "test-password" {
						t.Error("incorrect login password")
					}
					reply(map[string]any{"token": map[string]string{"access_token": "test-token"}})
					return
				}
				if r.Header.Get("Authorization") != "Bearer test-token" {
					t.Error("API request missing authentication")
				}
				switch {
				case r.URL.Path == "/api/v4/file/source":
					var body struct {
						URIs []string `json:"uris"`
					}
					_ = json.NewDecoder(r.Body).Decode(&body)
					name := strings.TrimPrefix(body.URIs[0], "cloudreve://my/")
					if _, ok := files[name]; !ok {
						_ = json.NewEncoder(w).Encode(map[string]any{"code": 40081, "msg": "failed to get target file", "aggregated_error": map[string]any{body.URIs[0]: map[string]any{"code": 40016, "msg": "Path not exist"}}})
						return
					}
					reply([]map[string]string{{"link": server.URL + "/public/" + name}})
				case r.URL.Path == "/api/v4/file/upload":
					var body struct {
						URI    string `json:"uri"`
						Mime   string `json:"mime_type"`
						Entity string `json:"entity_type"`
					}
					_ = json.NewDecoder(r.Body).Decode(&body)
					name := strings.TrimPrefix(body.URI, "cloudreve://my/")
					if !strings.HasSuffix(name, ".zip") {
						t.Error("only ZIP packages belong on Cloudreve")
					}
					if body.Entity != "version" {
						t.Error("upload must retain the file and permanent link")
					}
					if strings.HasSuffix(name, ".zip") && body.Mime != "application/zip" {
						t.Error("APK MIME type reused for ZIP")
					}
					id := fmt.Sprint(len(sessions))
					sessions[id] = name
					files[name] = nil
					events = append(events, "upload:"+name)
					reply(map[string]any{"session_id": id, "chunk_size": 3, "storage_policy": map[string]string{"type": "local"}})
				case strings.HasPrefix(r.URL.Path, "/api/v4/file/upload/"):
					parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/v4/file/upload/"), "/")
					name := sessions[parts[0]]
					if mode == "upload failure" && name == "client.zip" {
						_ = json.NewEncoder(w).Encode(map[string]any{"code": 50000, "msg": "fixture failure"})
						return
					}
					data, _ := io.ReadAll(r.Body)
					files[name] = append(files[name], data...)
					reply(nil)
				default:
					t.Errorf("unexpected request %s", r.URL.Path)
					http.NotFound(w, r)
				}
			}))
			defer server.Close()
			c, err := Login(context.Background(), CloudreveConfig{Base: server.URL, Email: "test@example.com", Password: "test-password"})
			if err != nil {
				t.Fatal(err)
			}
			root := t.TempDir()
			events = nil
			archive := []byte("test ZIP payload split into many chunks")
			hash := sha256.Sum256(archive)
			m := updater.Manifest{VersionCode: 2, VersionName: "0.6.1", DownloadURL: "updates/client.zip", FileSize: int64(len(archive)), SHA256: hex.EncodeToString(hash[:])}
			if mode == "bad local hash" {
				m.SHA256 = strings.Repeat("0", 64)
			}
			archivePath, manifestPath := filepath.Join(root, "client.zip"), filepath.Join(root, "update.json")
			_ = os.WriteFile(archivePath, archive, 0600)
			if err = writeJSON(manifestPath, m); err != nil {
				t.Fatal(err)
			}
			beforeManifest, _ := os.ReadFile(manifestPath)
			published, err := c.Publish(context.Background(), archivePath, manifestPath)
			if mode != "success" {
				if err == nil {
					t.Fatal("failed verification was accepted")
				}
				afterManifest, _ := os.ReadFile(manifestPath)
				if !bytes.Equal(beforeManifest, afterManifest) {
					t.Fatal("failed upload changed the RM update manifest")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(files["client.zip"], archive) {
				t.Fatal("chunk upload corrupted the package")
			}
			if strings.Join(events, ",") != "upload:client.zip,get:client.zip" {
				t.Fatalf("unsafe publication order: %v", events)
			}
			localManifest, _ := os.ReadFile(manifestPath)
			actual, err := updater.ReadManifest(bytes.NewReader(localManifest))
			if err != nil || actual != published || actual.DownloadURL != server.URL+"/public/client.zip" {
				t.Fatalf("public manifest mismatch: %v", err)
			}
		})
	}
}
