package server

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func newRateLimitTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	s, err := New(Config{DataDir: t.TempDir(), AdminToken: "test-key"})
	if err != nil {
		t.Fatal(err)
	}
	h := httptest.NewServer(s.Handler())
	t.Cleanup(func() {
		s.Close()
		h.Close()
	})
	return h
}

func postJSON(t *testing.T, url string, body string, realIP string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewBufferString(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	if realIP != "" {
		req.Header.Set("X-Real-IP", realIP)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = resp.Body.Close() })
	return resp
}

func TestClaimRateLimit(t *testing.T) {
	h := newRateLimitTestServer(t)
	for i := 0; i < 5; i++ {
		resp := postJSON(t, h.URL+"/api/v1/client/claim", `{"code":"invalid"}`, "")
		if resp.StatusCode != http.StatusNotFound {
			t.Fatalf("attempt %d returned %d, want 404", i+1, resp.StatusCode)
		}
	}
	limited := postJSON(t, h.URL+"/api/v1/client/claim", `{"code":"invalid"}`, "")
	if limited.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("sixth attempt returned %d, want 429", limited.StatusCode)
	}
	retry, err := strconv.Atoi(limited.Header.Get("Retry-After"))
	if err != nil || retry < 1 {
		t.Fatalf("invalid Retry-After header %q", limited.Header.Get("Retry-After"))
	}
	otherIP := postJSON(t, h.URL+"/api/v1/client/claim", `{"code":"invalid"}`, "192.0.2.20")
	if otherIP.StatusCode != http.StatusNotFound {
		t.Fatalf("independent proxy client returned %d, want 404", otherIP.StatusCode)
	}
}

func TestEnrollRateLimit(t *testing.T) {
	h := newRateLimitTestServer(t)
	for i := 0; i < 10; i++ {
		resp := postJSON(t, h.URL+"/api/v1/agent/enroll", `{"installationId":"rate-limit"}`, "")
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("attempt %d returned %d, want 401", i+1, resp.StatusCode)
		}
	}
	limited := postJSON(t, h.URL+"/api/v1/agent/enroll", `{"installationId":"rate-limit"}`, "")
	if limited.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("eleventh attempt returned %d, want 429", limited.StatusCode)
	}
	if limited.Header.Get("Retry-After") == "" {
		t.Fatal("rate-limited enrollment omitted Retry-After")
	}
}

func TestRateLimiterRefills(t *testing.T) {
	limiter := newIPRateLimiter(60, 1)
	now := time.Unix(1000, 0)
	limiter.now = func() time.Time { return now }
	if allowed, _ := limiter.allow("client"); !allowed {
		t.Fatal("initial token was not available")
	}
	allowed, retry := limiter.allow("client")
	if allowed || retry < time.Second {
		t.Fatalf("empty bucket: allowed=%v retry=%v", allowed, retry)
	}
	now = now.Add(time.Second)
	if allowed, _ := limiter.allow("client"); !allowed {
		t.Fatal("token did not refill")
	}
}

func TestClientIPOnlyTrustsProxyHeaderFromLoopback(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "http://example.test/api", nil)
	req.Header.Set("X-Real-IP", "192.0.2.30")
	req.RemoteAddr = "192.0.2.40:1234"
	if got := clientIP(req); got != "192.0.2.40" {
		t.Fatalf("public client spoofed proxy header: %s", got)
	}
	req.RemoteAddr = "127.0.0.1:1234"
	if got := clientIP(req); got != "192.0.2.30" {
		t.Fatalf("loopback proxy address was not used: %s", got)
	}
	req.Header.Set("X-Real-IP", "invalid")
	if got := clientIP(req); got != "127.0.0.1" {
		t.Fatalf("invalid proxy header was accepted: %s", got)
	}
}
