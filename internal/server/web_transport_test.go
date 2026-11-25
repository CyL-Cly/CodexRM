package server

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHTTPRelayDoesNotServeWeb(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "index.html"), []byte("WEB_UI"), 0600); err != nil {
		t.Fatal(err)
	}
	s, err := New(Config{DataDir: t.TempDir(), WebDir: dir, TrustLoopbackWebProxy: true, RequireSecureClients: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	h := s.Handler()
	for _, tc := range []struct {
		url, addr, proto string
		want             int
	}{
		{"http://rm/", "127.0.0.1:12", "", http.StatusForbidden},
		{"http://rm/sw.js", "127.0.0.1:12", "http", http.StatusForbidden},
		{"http://rm/app.js", "192.0.2.1:12", "https", http.StatusForbidden},
		{"http://rm/hiui/", "127.0.0.1:12", "https,http", http.StatusForbidden},
		{"http://rm/", "[::1]:12", "https", http.StatusOK},
		{"https://rm/", "192.0.2.1:12", "", http.StatusOK},
		{"http://rm/api/v1/health", "192.0.2.1:12", "", http.StatusOK},
	} {
		r := httptest.NewRequest(http.MethodGet, tc.url, nil)
		r.RemoteAddr = tc.addr
		if tc.proto != "" {
			r.Header.Set("X-Forwarded-Proto", tc.proto)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Errorf("%v: status %d", tc, w.Code)
		}
		if tc.want == http.StatusForbidden && (!strings.Contains(w.Body.String(), "如需使用网页控制需要使用https") || w.Header().Get("Cache-Control") != "no-store") {
			t.Errorf("unexpected cleartext web response for %s: %q", tc.url, w.Body.String())
		}
	}
}
