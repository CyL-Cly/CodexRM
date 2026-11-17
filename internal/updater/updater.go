package updater

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const CheckInterval = 24 * time.Hour
const staleUpdateAge = 24 * time.Hour

type Config struct {
	ServerURL   string
	ManifestURL string
	InstallDir  string
	VersionCode int64
	Args        []string
	Client      *http.Client
}

type Updater struct {
	cfg Config
	now func() time.Time
}

type checkState struct {
	ServerURL   string    `json:"server"`
	ManifestURL string    `json:"manifestUrl"`
	LastCheck   time.Time `json:"lastCheck"`
}

func New(cfg Config) (*Updater, error) {
	u, err := url.Parse(strings.TrimRight(cfg.ServerURL, "/"))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return nil, errors.New("update server must be an absolute HTTP(S) URL")
	}
	cfg.ServerURL = u.String()
	if cfg.ManifestURL == "" {
		cfg.ManifestURL = cfg.ServerURL + "/update.json"
	}
	m, err := url.Parse(cfg.ManifestURL)
	if err != nil || (m.Scheme != "http" && m.Scheme != "https") || m.Host == "" || m.User != nil || m.Fragment != "" {
		return nil, errors.New("update manifest must be an absolute HTTP(S) URL")
	}
	cfg.InstallDir, err = filepath.Abs(cfg.InstallDir)
	if err != nil {
		return nil, err
	}
	if cfg.Client == nil {
		cfg.Client = &http.Client{Timeout: 10 * time.Minute}
	}
	return &Updater{cfg: cfg, now: time.Now}, nil
}

func (u *Updater) statePath() string {
	return filepath.Join(u.cfg.InstallDir, ".state", "update-state.json")
}

func (u *Updater) nextCheck() time.Duration {
	var state checkState
	data, err := os.ReadFile(u.statePath())
	if err != nil || json.Unmarshal(data, &state) != nil || state.ServerURL != u.cfg.ServerURL || state.ManifestURL != u.cfg.ManifestURL {
		return 0
	}
	now := u.now()
	// Clock corrections must not suspend updates for more than a day.
	if state.LastCheck.After(now) {
		return 0
	}
	delay := state.LastCheck.Add(CheckInterval).Sub(now)
	if delay < 0 {
		return 0
	}
	return delay
}

// Run persists the last attempt, so reconnecting or restarting does not reset the 24h interval.
func (u *Updater) Run(ctx context.Context, ready chan<- *Plan) {
	delay := u.nextCheck()
	for {
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		plan, err := u.Check(ctx)
		if err != nil && ctx.Err() == nil {
			log.Printf("automatic update: %v; next check in 24h", err)
		}
		if plan != nil {
			select {
			case ready <- plan:
			case <-ctx.Done():
				plan.Discard()
			}
			return
		}
		if ctx.Err() != nil {
			return
		}
		delay = u.nextCheck()
		if delay <= 0 {
			delay = CheckInterval // A state-write error must not cause a tight retry loop.
		}
	}
}

func writeJSON(path string, value any) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0600)
}

// Check returns a fully downloaded and verified update, without stopping the connector.
func (u *Updater) Check(ctx context.Context) (*Plan, error) {
	if u.nextCheck() > 0 {
		return nil, nil
	}
	return u.Prepare(ctx)
}

// Inspect always checks the feed, without changing the automatic check schedule or downloading a package.
func (u *Updater) Inspect(ctx context.Context) (*Manifest, error) {
	manifestURL := u.cfg.ManifestURL
	manifestCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(manifestCtx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := u.cfg.Client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusNoContent {
		return nil, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("update manifest returned HTTP %d", resp.StatusCode)
	}
	m, err := ReadManifest(resp.Body)
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// Prepare bypasses the daily interval for an explicit user-requested update.
func (u *Updater) Prepare(ctx context.Context) (*Plan, error) {
	if err := writeJSON(u.statePath(), checkState{ServerURL: u.cfg.ServerURL, ManifestURL: u.cfg.ManifestURL, LastCheck: u.now().UTC()}); err != nil {
		return nil, err
	}
	u.cleanCompletedUpdates()
	m, err := u.Inspect(ctx)
	if err != nil {
		return nil, err
	}
	if m == nil || m.VersionCode <= u.cfg.VersionCode {
		return nil, nil
	}
	base, _ := url.Parse(u.cfg.ManifestURL)
	reference, _ := url.Parse(m.DownloadURL)
	downloadURL := base.ResolveReference(reference).String()
	stage, err := os.MkdirTemp(filepath.Join(u.cfg.InstallDir, ".state"), "update-")
	if err != nil {
		return nil, err
	}
	complete := false
	defer func() {
		if !complete {
			_ = os.RemoveAll(stage)
		}
	}()
	archive := filepath.Join(stage, "package.zip")
	if err = u.download(ctx, downloadURL, archive, *m); err != nil {
		return nil, err
	}
	plan, err := preparePackage(stage, archive, u.cfg, *m)
	if err != nil {
		return nil, err
	}
	complete = true
	return plan, nil
}

func (u *Updater) cleanCompletedUpdates() {
	stateDir := filepath.Join(u.cfg.InstallDir, ".state")
	entries, _ := os.ReadDir(stateDir)
	for _, entry := range entries {
		if !entry.IsDir() || !strings.HasPrefix(entry.Name(), "update-") {
			continue
		}
		stage := filepath.Join(stateDir, entry.Name())
		if _, err := os.Stat(filepath.Join(stage, "completed")); err == nil {
			_ = os.RemoveAll(stage)
			continue
		}
		info, err := entry.Info()
		if err != nil || u.now().Before(info.ModTime().Add(staleUpdateAge)) {
			continue
		}
		if pid := updateHelperPID(stage); pid > 0 && processRunning(pid) {
			continue
		}
		_ = os.RemoveAll(stage)
	}
}

func updateHelperPID(stage string) int {
	data, err := os.ReadFile(filepath.Join(stage, "helper.pid"))
	if err != nil {
		return 0
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(data)))
	return pid
}

func (u *Updater) download(ctx context.Context, rawURL, path string, m Manifest) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	resp, err := u.cfg.Client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("update download returned HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength >= 0 && resp.ContentLength != m.FileSize {
		return errors.New("update download size does not match fileSize")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	hash := sha256.New()
	n, copyErr := io.Copy(io.MultiWriter(f, hash), io.LimitReader(resp.Body, m.FileSize+1))
	closeErr := f.Close()
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if n != m.FileSize || !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), m.SHA256) {
		return errors.New("update package size or SHA-256 mismatch")
	}
	return nil
}
