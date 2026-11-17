package server_test

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/codexrm/codexrm/internal/server"
	"github.com/codexrm/codexrm/internal/updater"
)

func TestClientUpdateRoutes(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "updates")
	_ = os.MkdirAll(directory, 0700)
	s, err := server.New(server.Config{DataDir: root, AdminToken: "private-key"})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	h := httptest.NewServer(s.Handler())
	defer h.Close()
	response, err := http.Get(h.URL + "/update.json")
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 404 || response.Header.Get("Cache-Control") != "no-store" {
		t.Fatal("unpublished update did not return an uncached 404")
	}
	data := []byte("windows ZIP bytes")
	hash := sha256.Sum256(data)
	m := updater.Manifest{VersionCode: 2, VersionName: "0.6.1", DownloadURL: "updates/client.zip", ReleaseNotes: "更新", FileSize: int64(len(data)), SHA256: hex.EncodeToString(hash[:])}
	manifest, _ := json.Marshal(m)
	_ = os.WriteFile(filepath.Join(directory, "update.json"), append([]byte{0xef, 0xbb, 0xbf}, manifest...), 0600)
	_ = os.WriteFile(filepath.Join(directory, "client.zip"), data, 0600)
	response, err = http.Get(h.URL + "/update.json")
	if err != nil {
		t.Fatal(err)
	}
	var actual updater.Manifest
	err = json.NewDecoder(response.Body).Decode(&actual)
	_ = response.Body.Close()
	if err != nil || actual != m || response.StatusCode != 200 || response.Header.Get("Cache-Control") != "no-store" {
		t.Fatalf("published manifest: %+v, %v", actual, err)
	}
	response, err = http.Get(h.URL + "/updates/client.zip")
	if err != nil {
		t.Fatal(err)
	}
	downloaded, _ := io.ReadAll(response.Body)
	_ = response.Body.Close()
	if response.StatusCode != 200 || string(downloaded) != string(data) || response.Header.Get("Content-Type") != "application/zip" {
		t.Fatal("public package download failed")
	}
	for _, path := range []string{"/updates/update.json", "/updates/..%5cstate.json", "/updates/missing.zip", "/updates/client.zip/extra"} {
		response, err = http.Get(h.URL + path)
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
		if response.StatusCode != 404 {
			t.Fatalf("unexpected package path was served: %s", path)
		}
	}
	_ = os.WriteFile(filepath.Join(directory, "update.json"), []byte("invalid JSON"), 0600)
	response, err = http.Get(h.URL + "/update.json")
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 500 {
		t.Fatal("invalid publication was accepted")
	}
}

func TestCustomUpdateDirectory(t *testing.T) {
	directory := t.TempDir()
	s, err := server.New(server.Config{DataDir: t.TempDir(), UpdateDir: directory})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	_ = os.WriteFile(filepath.Join(directory, "client.zip"), []byte("custom release"), 0600)
	r := httptest.NewRecorder()
	s.Handler().ServeHTTP(r, httptest.NewRequest(http.MethodGet, "/updates/client.zip", nil))
	if r.Code != 200 || !strings.Contains(r.Body.String(), "custom release") {
		t.Fatal("custom update directory was ignored")
	}
}
