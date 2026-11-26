// Package publisher publishes Windows packages using Cloudreve v4's local/relay upload API.
package publisher

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/CyL-Cly/CodexRM-Server/updater"
)

type CloudreveConfig struct {
	Base     string `json:"base"`
	Email    string `json:"email"`
	Password string `json:"password"`
	Dir      string `json:"dir"`
}

type Config struct {
	Cloudreve CloudreveConfig `json:"cloudreve"`
}

type Client struct {
	HTTP             *http.Client
	base, token, dir string
}

type apiResponse struct {
	Code            int                    `json:"code"`
	Msg             string                 `json:"msg"`
	Data            json.RawMessage        `json:"data"`
	AggregatedError map[string]apiResponse `json:"aggregated_error"`
}

type APIError struct {
	Code    int
	Message string
}

func (e *APIError) Error() string { return fmt.Sprintf("Cloudreve code %d: %s", e.Code, e.Message) }

func Login(ctx context.Context, cfg CloudreveConfig) (*Client, error) {
	if password := os.Getenv("CLOUDREVE_PASSWORD"); password != "" {
		cfg.Password = password
	}
	u, err := url.Parse(cfg.Base)
	if err != nil || u.Scheme != "https" && u.Scheme != "http" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("cloudreve.base must be an HTTP(S) site URL")
	}
	if cfg.Email == "" || cfg.Password == "" {
		return nil, errors.New("cloudreve.email and password (or CLOUDREVE_PASSWORD) are required")
	}
	// The reference release script connects directly to AgentOS, bypassing build proxies.
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.ResponseHeaderTimeout = 30 * time.Second
	c := &Client{HTTP: &http.Client{Timeout: 10 * time.Minute, Transport: transport}, base: strings.TrimRight(cfg.Base, "/") + "/api/v4", dir: cfg.Dir}
	data, err := c.request(ctx, "POST", "/session/token", map[string]string{"email": cfg.Email, "password": cfg.Password})
	if err != nil {
		return nil, err
	}
	var response struct {
		Token struct {
			AccessToken string `json:"access_token"`
		} `json:"token"`
	}
	if err = json.Unmarshal(data, &response); err != nil {
		return nil, err
	}
	if response.Token.AccessToken == "" {
		return nil, errors.New("Cloudreve login did not return an access token")
	}
	c.token = response.Token.AccessToken
	return c, nil
}

func (c *Client) request(ctx context.Context, method, path string, value any) (json.RawMessage, error) {
	var body io.Reader
	if method != http.MethodGet && method != http.MethodHead {
		data, err := json.Marshal(value)
		if err != nil {
			return nil, err
		}
		body = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	return c.send(req)
}

func (c *Client) send(req *http.Request) (json.RawMessage, error) {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var envelope apiResponse
	if err = json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&envelope); err != nil {
		return nil, fmt.Errorf("Cloudreve HTTP %d: invalid JSON response", resp.StatusCode)
	}
	if envelope.Code != 0 {
		if envelope.Code == 40081 && len(envelope.AggregatedError) == 1 {
			for _, nested := range envelope.AggregatedError {
				return nil, &APIError{nested.Code, nested.Msg}
			}
		}
		return nil, &APIError{envelope.Code, envelope.Msg}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("Cloudreve HTTP %d", resp.StatusCode)
	}
	return envelope.Data, nil
}

func (c *Client) uri(name string) string {
	parts := []string{"my"}
	for _, part := range strings.Split(strings.Trim(c.dir, "/"), "/") {
		if part != "" {
			parts = append(parts, url.PathEscape(part))
		}
	}
	return "cloudreve://" + strings.Join(append(parts, url.PathEscape(name)), "/")
}

func (c *Client) Link(ctx context.Context, name string) (string, error) {
	data, err := c.request(ctx, "PUT", "/file/source", map[string]any{"uris": []string{c.uri(name)}})
	if err != nil {
		return "", err
	}
	var links []struct {
		Link string `json:"link"`
	}
	if err = json.Unmarshal(data, &links); err != nil {
		return "", err
	}
	if len(links) != 1 || links[0].Link == "" {
		return "", errors.New("Cloudreve did not return a permanent anonymous link")
	}
	u, err := url.Parse(links[0].Link)
	if err != nil || u.Scheme != "http" && u.Scheme != "https" || u.Host == "" || u.User != nil {
		return "", errors.New("Cloudreve returned an invalid link")
	}
	return links[0].Link, nil
}

func (c *Client) Upload(ctx context.Context, name string, reader io.ReaderAt, size int64, mimeType string) error {
	if size <= 0 {
		return errors.New("cannot upload an empty file")
	}
	const partSize int64 = 8 << 20
	hash := sha256.New()
	partHashes := make([][8]uint32, 0)
	for offset := int64(0); offset < size; offset += partSize {
		n := partSize
		if size-offset < n {
			n = size - offset
		}
		if _, err := io.Copy(hash, io.NewSectionReader(reader, offset, n)); err != nil {
			return err
		}
		if offset+n < size {
			state, err := hash.(encoding.BinaryMarshaler).MarshalBinary()
			if err != nil {
				return err
			}
			var words [8]uint32
			for i := range words {
				words[i] = binary.BigEndian.Uint32(state[4+i*4 : 8+i*4])
			}
			partHashes = append(partHashes, words)
		}
	}
	contentHash := hex.EncodeToString(hash.Sum(nil))
	data, err := c.request(ctx, "PUT", "/file/upload", map[string]any{
		"uri":           c.uri(name),
		"size":          size,
		"last_modified": time.Now().UnixMilli(),
		"mime_type":     mimeType,
		"entity_type":   "version",
		"content_hash":  contentHash,
		"part_hashes":   partHashes,
		"chunk_size":    partSize,
	})
	if err != nil {
		return err
	}
	var session struct {
		ID             string   `json:"session_id"`
		URLs           []string `json:"upload_urls"`
		CallbackSecret string   `json:"callback_secret"`
		ChunkSize      int64    `json:"chunk_size"`
		Policy         struct {
			Type  string `json:"type"`
			Relay bool   `json:"relay"`
		} `json:"storage_policy"`
	}
	if err = json.Unmarshal(data, &session); err != nil {
		return err
	}
	if session.Policy.Type == "yun139" && !session.Policy.Relay {
		if session.ID == "" || session.CallbackSecret == "" || session.ChunkSize != partSize {
			return errors.New("invalid yun139 upload session")
		}
		expected := (size + partSize - 1) / partSize
		if len(session.URLs) != 0 && int64(len(session.URLs)) != expected {
			return errors.New("yun139 upload URL count differs from package chunks")
		}
		for i, target := range session.URLs {
			u, err := url.Parse(target)
			if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
				return errors.New("invalid yun139 upload URL")
			}
			offset := int64(i) * partSize
			n := partSize
			if size-offset < n {
				n = size - offset
			}
			req, err := http.NewRequestWithContext(ctx, "PUT", target, io.NewSectionReader(reader, offset, n))
			if err != nil {
				return err
			}
			req.ContentLength = n
			req.Header.Set("Content-Type", "application/octet-stream")
			resp, err := c.HTTP.Do(req)
			if err != nil {
				return fmt.Errorf("yun139 chunk %d upload failed", i)
			}
			io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
			resp.Body.Close()
			if resp.StatusCode < 200 || resp.StatusCode >= 300 {
				return fmt.Errorf("yun139 chunk %d HTTP %d", i, resp.StatusCode)
			}
		}
		_, err := c.request(ctx, "GET", "/callback/yun139/"+url.PathEscape(session.ID)+"/"+url.PathEscape(session.CallbackSecret), nil)
		return err
	}
	if session.ID == "" || session.Policy.Type != "local" && !session.Policy.Relay {
		return errors.New("Cloudreve upload requires a local/relay storage policy and session_id")
	}
	chunk := session.ChunkSize
	if chunk <= 0 || chunk > size {
		chunk = size
	}
	for offset, index := int64(0), 0; offset < size; index++ {
		length := chunk
		if size-offset < length {
			length = size - offset
		}
		req, err := http.NewRequestWithContext(ctx, "POST", fmt.Sprintf("%s/file/upload/%s/%d", c.base, url.PathEscape(session.ID), index), io.NewSectionReader(reader, offset, length))
		if err != nil {
			return err
		}
		req.Header.Set("Authorization", "Bearer "+c.token)
		req.Header.Set("Content-Type", "application/octet-stream")
		req.ContentLength = length
		if _, err = c.send(req); err != nil {
			return fmt.Errorf("upload chunk %d: %w", index, err)
		}
		offset += length
	}
	return nil
}

func writeJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	temporary := path + ".next"
	if err = os.WriteFile(temporary, append(data, '\n'), 0600); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}

// VerifyAnonymous downloads without credentials and checks the exact bytes before publishing a manifest.
func (c *Client) VerifyAnonymous(ctx context.Context, link string, size int64, sha string) error {
	req, err := http.NewRequestWithContext(ctx, "GET", link, nil)
	if err != nil {
		return err
	}
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("anonymous download HTTP %d", resp.StatusCode)
	}
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(resp.Body, size+1))
	if err != nil {
		return err
	}
	if n != size || !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), sha) {
		return errors.New("anonymous download size or SHA-256 differs from the release")
	}
	return nil
}

// Publish uploads only the release package, verifies its anonymous download, and updates the local manifest for RM publication.
func (c *Client) Publish(ctx context.Context, archive, manifestPath string) (updater.Manifest, error) {
	var m updater.Manifest
	data, err := os.ReadFile(manifestPath)
	if err != nil {
		return m, err
	}
	m, err = updater.ReadManifest(bytes.NewReader(data))
	if err != nil {
		return m, err
	}
	f, err := os.Open(archive)
	if err != nil {
		return m, err
	}
	defer f.Close()
	hash := sha256.New()
	size, err := io.Copy(hash, f)
	if err != nil {
		return m, err
	}
	if size != m.FileSize || !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), m.SHA256) {
		return m, errors.New("local package differs from update.json")
	}
	if err = c.Upload(ctx, filepath.Base(archive), f, size, packageContentType(archive)); err != nil {
		return m, err
	}
	m.DownloadURL, err = c.Link(ctx, filepath.Base(archive))
	if err != nil {
		return m, err
	}
	if err = c.VerifyAnonymous(ctx, m.DownloadURL, m.FileSize, m.SHA256); err != nil {
		return m, err
	}
	return m, writeJSON(manifestPath, m)
}

func packageContentType(name string) string {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".zip":
		return "application/zip"
	case ".apk":
		return "application/vnd.android.package-archive"
	default:
		return "application/octet-stream"
	}
}
