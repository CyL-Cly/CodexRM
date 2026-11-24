package publisher

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
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

func TestPackageContentType(t *testing.T) {
	for name, expected := range map[string]string{
		"client.zip":        "application/zip",
		"CodexRM.APK":       "application/vnd.android.package-archive",
		"unexpected.binary": "application/octet-stream",
	} {
		if actual := packageContentType(name); actual != expected {
			t.Fatalf("packageContentType(%q) = %q, expected %q", name, actual, expected)
		}
	}
}

func TestPublicationUploadsOnlyZIPAndPreparesRMManifest(t *testing.T) {
	for _, mode := range []string{"success", "corrupt download", "upload failure", "bad local hash"} {
		t.Run(mode, func(t *testing.T) {
			files := map[string][]byte{}
			sessions := map[string]string{}
			var events []string
			var expectedContentHash string
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
						URI         string      `json:"uri"`
						Mime        string      `json:"mime_type"`
						Entity      string      `json:"entity_type"`
						Size        int64       `json:"size"`
						ContentHash string      `json:"content_hash"`
						PartHashes  [][8]uint32 `json:"part_hashes"`
					}
					_ = json.NewDecoder(r.Body).Decode(&body)
					name := strings.TrimPrefix(body.URI, "cloudreve://my/")
					if !strings.HasSuffix(name, ".zip") && !strings.HasSuffix(name, ".apk") {
						t.Error("only release packages belong on Cloudreve")
					}
					if body.Entity != "version" {
						t.Error("upload must retain the file and permanent link")
					}
					if strings.HasSuffix(name, ".zip") && body.Mime != "application/zip" {
						t.Error("APK MIME type reused for ZIP")
					}
					if body.Size <= 0 {
						t.Error("upload session must include size")
					}
					if expectedContentHash != "" && body.ContentHash != expectedContentHash {
						t.Errorf("content_hash = %q, expected %q", body.ContentHash, expectedContentHash)
					}
					if len(body.PartHashes) != 0 {
						t.Error("small package must not include prefix states")
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
			expectedContentHash = hex.EncodeToString(hash[:])
			m := updater.Manifest{VersionCode: 2, VersionName: "0.6.1", DownloadURL: "updates/client.zip", FileSize: int64(len(archive)), SHA256: expectedContentHash}
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

func TestYun139DirectUpload(t *testing.T) {
	payload := bytes.Repeat([]byte("a"), (8<<20)+17)
	var server *httptest.Server
	var uploaded []byte
	completed := false
	server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/file/upload":
			var body struct {
				ContentHash string      `json:"content_hash"`
				PartHashes  [][8]uint32 `json:"part_hashes"`
				ChunkSize   int64       `json:"chunk_size"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Error(err)
			}
			want := sha256.Sum256(payload)
			if body.ContentHash != hex.EncodeToString(want[:]) || body.ChunkSize != 8<<20 || len(body.PartHashes) != 1 {
				t.Errorf("invalid digest metadata: %+v", body)
			}
			// SHA-256 state after 8 MiB of 'a', before final padding.
			h := sha256.New()
			h.Write(payload[:8<<20])
			state, _ := h.(interface{ MarshalBinary() ([]byte, error) }).MarshalBinary()
			if len(body.PartHashes) == 1 {
				for i, word := range body.PartHashes[0] {
					if word != binary.BigEndian.Uint32(state[4+i*4:8+i*4]) {
						t.Error("wrong prefix state")
					}
				}
			}
			json.NewEncoder(w).Encode(map[string]any{"code": 0, "data": map[string]any{"session_id": "session", "chunk_size": 8 << 20, "storage_policy": map[string]string{"type": "yun139"}, "upload_urls": []string{server.URL + "/part/0", server.URL + "/part/1"}, "callback_secret": "secret"}})
		case strings.HasPrefix(r.URL.Path, "/part/"):
			if r.Header.Get("Authorization") != "" {
				t.Error("leaked Cloudreve token")
			}
			b, _ := io.ReadAll(r.Body)
			uploaded = append(uploaded, b...)
		case r.URL.Path == "/callback/yun139/session/secret":
			completed = true
			json.NewEncoder(w).Encode(map[string]any{"code": 0})
		default:
			t.Error("unexpected path", r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	c := &Client{HTTP: server.Client(), base: server.URL, token: "private-token"}
	if err := c.Upload(context.Background(), "client.zip", bytes.NewReader(payload), int64(len(payload)), "application/zip"); err != nil {
		t.Fatal(err)
	}
	if !completed || !bytes.Equal(uploaded, payload) {
		t.Fatal("direct upload did not finish with exact bytes")
	}
}
