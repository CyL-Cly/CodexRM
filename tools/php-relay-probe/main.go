// check-probe tests the uploaded PHP endpoint with a native client that cannot
// execute JavaScript challenges. It uses only the Go standard library.
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type outcome struct {
	Name   string `json:"name"`
	Status string `json:"status"`
	Detail any    `json:"detail"`
}

type checker struct {
	endpoint string
	key      string
	client   *http.Client
	results  []outcome
}

type apiError struct {
	Status int
	Text   string
}

func (e *apiError) Error() string { return fmt.Sprintf("HTTP %d: %s", e.Status, e.Text) }

func (c *checker) call(action string, input map[string]any, key string, output any) error {
	data := map[string]any{"action": action, "key": key}
	for k, v := range input {
		data[k] = v
	}
	body, err := json.Marshal(data)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, c.endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer codexrm-probe-header")
	req.Header.Set("X-Probe-Check", "codexrm-probe")
	req.Header.Set("Cache-Control", "no-store")
	req.Header.Set("User-Agent", "CodexRM-PHP-Probe/1")
	resp, err := c.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if !strings.Contains(strings.ToLower(resp.Header.Get("Content-Type")), "application/json") {
		return fmt.Errorf("HTTP %d returned %q instead of JSON; possible browser challenge or hosting error page", resp.StatusCode, resp.Header.Get("Content-Type"))
	}
	var envelope struct {
		OK     bool            `json:"ok"`
		Error  string          `json:"error"`
		Result json.RawMessage `json:"result"`
	}
	if err = json.NewDecoder(io.LimitReader(resp.Body, 2<<20)).Decode(&envelope); err != nil {
		return fmt.Errorf("invalid JSON response: %w", err)
	}
	if resp.StatusCode != http.StatusOK || !envelope.OK {
		return &apiError{Status: resp.StatusCode, Text: envelope.Error}
	}
	if output != nil {
		return json.Unmarshal(envelope.Result, output)
	}
	return nil
}

func (c *checker) record(name, status string, detail any) {
	if s, ok := detail.(string); ok && c.key != "" {
		detail = strings.ReplaceAll(s, c.key, "[redacted]")
	}
	c.results = append(c.results, outcome{Name: name, Status: status, Detail: detail})
	encoded, _ := json.Marshal(detail)
	fmt.Printf("[%s] %s: %s\n", strings.ToUpper(status), name, encoded)
}

func randomID() (string, error) {
	value := make([]byte, 16)
	_, err := rand.Read(value)
	return hex.EncodeToString(value), err
}

func (c *checker) queue(backend string) bool {
	run, err := randomID()
	if err != nil {
		c.record(backend+" persistence", "fail", err.Error())
		return false
	}
	base := func() map[string]any { return map[string]any{"backend": backend, "run": run} }
	var info map[string]any
	if err = c.call("queue_init", base(), c.key, &info); err != nil {
		c.record(backend+" persistence", "fail", err.Error())
		return false
	}
	defer func() {
		if err := c.call("queue_cleanup", base(), c.key, nil); err != nil {
			c.record(backend+" cleanup", "warn", "Run "+run+": "+err.Error())
		} else {
			c.record(backend+" cleanup", "pass", "Synthetic test data removed")
		}
	}()
	// Four independent requests at a time. No PHP sessions or cookies are used.
	errorsByIndex := make([]error, 16)
	for offset := 0; offset < 16; offset += 4 {
		var group sync.WaitGroup
		for j := offset; j < offset+4; j++ {
			group.Add(1)
			go func(index int) {
				defer group.Done()
				input := base()
				input["payload"] = fmt.Sprintf("%s:%d:中继🧪", run, index)
				input["side"] = []string{"a", "b"}[index%2]
				errorsByIndex[index] = c.call("queue_push", input, c.key, nil)
			}(j)
		}
		group.Wait()
	}
	for _, err := range errorsByIndex {
		if err != nil {
			c.record(backend+" concurrent writes", "fail", err.Error())
			return false
		}
	}
	type message struct {
		ID      int64  `json:"id"`
		Side    string `json:"side"`
		Payload string `json:"payload"`
	}
	seenIDs := make(map[int64]bool)
	seenPayloads := make(map[string]bool)
	for sideIndex, side := range []string{"a", "b"} {
		input := base()
		input["side"] = side
		var result struct {
			Messages []message `json:"messages"`
		}
		if err = c.call("queue_read", input, c.key, &result); err != nil {
			c.record(backend+" cross-request reads", "fail", err.Error())
			return false
		}
		if len(result.Messages) != 8 {
			c.record(backend+" integrity", "fail", "Expected eight messages in each direction")
			return false
		}
		var previous int64
		for _, m := range result.Messages {
			if m.Side != side || m.ID <= previous || seenIDs[m.ID] || seenPayloads[m.Payload] {
				c.record(backend+" integrity", "fail", "Duplicate, misrouted or unordered message")
				return false
			}
			previous, seenIDs[m.ID], seenPayloads[m.Payload] = m.ID, true, true
		}
		for i := sideIndex; i < 16; i += 2 {
			if !seenPayloads[fmt.Sprintf("%s:%d:中继🧪", run, i)] {
				c.record(backend+" integrity", "fail", "Message content changed or disappeared")
				return false
			}
		}
		input["after"] = previous
		if err = c.call("queue_read", input, c.key, &result); err != nil || len(result.Messages) != 0 {
			c.record(backend+" cursor", "fail", "Cursor read failed or replayed old messages")
			return false
		}
	}
	info["messages"] = 16
	info["concurrent_writers"] = 4
	c.record(backend+" cross-request bidirectional queue", "pass", info)
	return true
}

func (c *checker) run() bool {
	nonce, err := randomID()
	if err != nil {
		c.record("random nonce", "fail", err.Error())
		return false
	}
	var echo struct {
		Nonce         string `json:"nonce"`
		Bytes         int    `json:"bytes"`
		SHA256        string `json:"sha256"`
		Authorization bool   `json:"authorization_header"`
		Custom        bool   `json:"custom_header"`
	}
	payload := "CodexRM 中继🧪"
	err = c.call("echo", map[string]any{"nonce": nonce, "payload": payload}, c.key, &echo)
	if err != nil || echo.Nonce != nonce || echo.Bytes != len(payload) {
		if err == nil {
			err = errors.New("request or response content was modified")
		}
		c.record("native client API access", "fail", err.Error())
		return false
	}
	c.record("native client API access", "pass", "Direct POST JSON works without JavaScript or cookies")
	headerStatus := "pass"
	if !echo.Authorization || !echo.Custom {
		headerStatus = "warn"
	}
	c.record("request headers", headerStatus, map[string]bool{"authorization": echo.Authorization, "custom": echo.Custom})
	var rejected *apiError
	err = c.call("echo", nil, "invalid-"+nonce, nil)
	auth := errors.As(err, &rejected) && rejected.Status == http.StatusUnauthorized
	if auth {
		c.record("authentication", "pass", "Wrong key rejected with HTTP 401")
	} else {
		c.record("authentication", "fail", "Wrong key was not rejected with the expected response")
	}
	var environment map[string]any
	if err = c.call("info", nil, c.key, &environment); err != nil {
		c.record("PHP environment", "fail", err.Error())
		return false
	}
	c.record("PHP environment", "pass", environment)
	completePayload := true
	for _, size := range []int{16 << 10, 256 << 10, 1 << 20} {
		payload = strings.Repeat("x", size)
		digest := sha256.Sum256([]byte(payload))
		err = c.call("echo", map[string]any{"payload": payload}, c.key, &echo)
		if err != nil || echo.Bytes != size || echo.SHA256 != hex.EncodeToString(digest[:]) {
			if err == nil {
				err = errors.New("body size or SHA-256 mismatch")
			}
			c.record(fmt.Sprintf("%d-byte request", size), "fail", err.Error())
			completePayload = false
		} else {
			c.record(fmt.Sprintf("%d-byte request", size), "pass", "Byte count and SHA-256 match")
		}
	}
	mysql := false
	if configured, _ := environment["mysql_configured"].(bool); configured {
		mysql = c.queue("mysql")
	} else {
		c.record("MySQL", "warn", "Not configured")
	}
	file := c.queue("file")
	var wait struct {
		Elapsed float64 `json:"elapsed_seconds"`
	}
	if err = c.call("wait", map[string]any{"seconds": 2}, c.key, &wait); err != nil || wait.Elapsed < 1.8 {
		c.record("optional 2-second request", "warn", "Request did not complete as expected")
	} else {
		c.record("optional 2-second request", "pass", wait.Elapsed)
	}
	return auth && completePayload && (mysql || file)
}

func main() {
	endpoint := flag.String("url", "", "Full HTTPS URL of the uploaded PHP file")
	keyFile := flag.String("key-file", "", "Access key file; defaults to access-key.txt beside this executable")
	reportFile := flag.String("report", "php-relay-probe-native-report.json", "JSON report output")
	flag.Parse()
	if *endpoint == "" {
		fmt.Print("Uploaded PHP URL: ")
		if _, err := fmt.Scanln(endpoint); err != nil {
			fmt.Fprintln(os.Stderr, "A URL is required")
			os.Exit(2)
		}
	}
	parsed, err := url.Parse(*endpoint)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "https" && parsed.Scheme != "http") || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		fmt.Fprintln(os.Stderr, "Provide the PHP file URL without credentials, query parameters or fragments")
		os.Exit(2)
	}
	local := parsed.Hostname() == "localhost" || parsed.Hostname() == "127.0.0.1" || parsed.Hostname() == "::1"
	if parsed.Scheme != "https" && !local {
		fmt.Fprintln(os.Stderr, "Use HTTPS so that the access key is encrypted in transit")
		os.Exit(2)
	}
	key := strings.TrimSpace(os.Getenv("CODEXRM_PHP_PROBE_KEY"))
	if *keyFile != "" || key == "" {
		if *keyFile == "" {
			executable, _ := os.Executable()
			*keyFile = filepath.Join(filepath.Dir(executable), "access-key.txt")
		}
		data, readErr := os.ReadFile(*keyFile)
		if readErr != nil {
			fmt.Fprintln(os.Stderr, "Cannot read access key file:", readErr)
			os.Exit(2)
		}
		key = strings.TrimSpace(string(data))
	}
	if len(key) < 24 {
		fmt.Fprintln(os.Stderr, "Access key is missing or too short")
		os.Exit(2)
	}
	c := &checker{endpoint: parsed.String(), key: key, client: &http.Client{
		Timeout: 20 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("endpoint redirected; use its canonical HTTPS URL and check for hosting browser challenges")
		},
	}}
	passed := c.run()
	report := map[string]any{"probe_version": 1, "tested_at": time.Now().UTC().Format(time.RFC3339), "basic_capabilities_passed": passed,
		"note": "A passing result checks basic short-poll relay capabilities; it does not establish hosting terms, sustained reliability, traffic quotas, or WebSocket support.", "tests": c.results}
	data, err := json.MarshalIndent(report, "", "  ")
	if err == nil {
		err = os.WriteFile(*reportFile, append(data, '\n'), 0600)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "Cannot write report:", err)
		os.Exit(2)
	}
	fmt.Println("Report:", *reportFile)
	if !passed {
		os.Exit(1)
	}
}
