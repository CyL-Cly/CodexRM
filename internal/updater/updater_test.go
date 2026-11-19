package updater

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func testManifest(data []byte) Manifest {
	hash := sha256.Sum256(data)
	return Manifest{VersionCode: 2, VersionName: "test", DownloadURL: "updates/client.zip", FileSize: int64(len(data)), SHA256: hex.EncodeToString(hash[:])}
}

func testArchive(t *testing.T, extra map[string]string) []byte {
	t.Helper()
	var buffer bytes.Buffer
	w := zip.NewWriter(&buffer)
	files := map[string]string{}
	for _, file := range managedFiles {
		files["CodexRM/"+file] = "new " + file
	}
	for file, content := range extra {
		files[file] = content
	}
	for file, content := range files {
		f, err := w.Create(file)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = io.WriteString(f, content); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func TestManifestFormat(t *testing.T) {
	valid := testManifest([]byte("package"))
	data, _ := json.Marshal(valid)
	for _, prefix := range []string{"", "\xef\xbb\xbf"} {
		m, err := ReadManifest(strings.NewReader(prefix + string(data)))
		if err != nil || m != valid {
			t.Fatalf("manifest = %+v, %v", m, err)
		}
	}
	for _, test := range []struct {
		name string
		edit func(*Manifest)
	}{
		{"bad version", func(m *Manifest) { m.VersionCode = 0 }},
		{"no name", func(m *Manifest) { m.VersionName = "" }},
		{"negative size", func(m *Manifest) { m.FileSize = -1 }},
		{"oversize", func(m *Manifest) { m.FileSize = MaxPackageSize + 1 }},
		{"bad hash", func(m *Manifest) { m.SHA256 = "wrong" }},
		{"local download", func(m *Manifest) { m.DownloadURL = "file:///C:/package.zip" }},
		{"scheme relative URL", func(m *Manifest) { m.DownloadURL = "//other.example/package.zip" }},
	} {
		t.Run(test.name, func(t *testing.T) {
			m := valid
			test.edit(&m)
			if m.Validate() == nil {
				t.Fatal("accepted invalid manifest")
			}
		})
	}
	if _, err := ReadManifest(strings.NewReader(string(data) + " {}")); err == nil {
		t.Fatal("accepted trailing JSON")
	}
}

func TestTwentyHourChecksPersistAcrossInstancesAndServerChanges(t *testing.T) {
	var requests atomic.Int32
	h := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.URL.Path != "/rm/update.json" {
			t.Errorf("wrong manifest path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer h.Close()
	cfg := Config{ServerURL: h.URL + "/rm/", InstallDir: t.TempDir(), VersionCode: 1}
	now := time.Now().UTC()
	u, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	u.now = func() time.Time { return now }
	if _, err = u.Check(context.Background()); err != nil {
		t.Fatal(err)
	}
	restarted, _ := New(cfg)
	restarted.now = func() time.Time { return now.Add(19*time.Hour + 59*time.Minute) }
	if _, err = restarted.Check(context.Background()); err != nil || requests.Load() != 1 {
		t.Fatalf("restarted agent checked too soon: %d, %v", requests.Load(), err)
	}
	restarted.now = func() time.Time { return now.Add(CheckInterval) }
	if _, err = restarted.Check(context.Background()); err != nil || requests.Load() != 2 {
		t.Fatalf("20h check missing: %d, %v", requests.Load(), err)
	}
	changed, _ := New(Config{ServerURL: h.URL + "/different", InstallDir: cfg.InstallDir, VersionCode: 1})
	if changed.nextCheck() != 0 {
		t.Fatal("server change reused previous server's interval")
	}
	restarted.now = func() time.Time { return now.Add(-time.Hour) }
	if restarted.nextCheck() != 0 {
		t.Fatal("clock correction suspended updates")
	}
}

func TestFailedChecksUseBoundedBackoffWithoutConsumingSuccessInterval(t *testing.T) {
	var fail atomic.Bool
	var requests atomic.Int32
	feed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		requests.Add(1)
		if fail.Load() {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer feed.Close()
	u, err := New(Config{ServerURL: feed.URL, InstallDir: t.TempDir(), VersionCode: 1})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	u.now = func() time.Time { return now }
	if _, err = u.Prepare(context.Background()); err != nil {
		t.Fatal(err)
	}
	successfulAt := u.Snapshot().LastSuccessfulCheck
	now = now.Add(CheckInterval)
	fail.Store(true)
	for i, backoff := range []time.Duration{time.Minute, 5 * time.Minute, 15 * time.Minute, time.Hour, time.Hour} {
		if _, err = u.Check(context.Background()); err == nil {
			t.Fatal("failed update check returned no error")
		}
		snapshot := u.Snapshot()
		if !snapshot.LastSuccessfulCheck.Equal(successfulAt) {
			t.Fatalf("failure consumed successful check: %+v", snapshot)
		}
		if snapshot.NextRetryAt == nil || !snapshot.NextRetryAt.Equal(now.Add(backoff)) || snapshot.Phase != "error" || snapshot.Error == "" {
			t.Fatalf("attempt %d snapshot = %+v, want backoff %s", i+1, snapshot, backoff)
		}
		before := requests.Load()
		if _, err = u.Check(context.Background()); err != nil || requests.Load() != before {
			t.Fatalf("retry interval was bypassed: requests=%d, err=%v", requests.Load(), err)
		}
		now = now.Add(backoff)
	}
	fail.Store(false)
	if _, err = u.Check(context.Background()); err != nil {
		t.Fatal(err)
	}
	snapshot := u.Snapshot()
	if snapshot.Phase != "up-to-date" || !snapshot.LastSuccessfulCheck.Equal(now) || snapshot.NextRetryAt == nil || !snapshot.NextRetryAt.Equal(now.Add(CheckInterval)) {
		t.Fatalf("successful retry snapshot = %+v", snapshot)
	}
}

func TestLegacyUpdateStateMigratesAndSnapshotIsQueryable(t *testing.T) {
	root := t.TempDir()
	lastCheck := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
	legacy := map[string]any{"server": "https://updates.example", "manifestUrl": "https://updates.example/update.json", "lastCheck": lastCheck}
	if err := writeJSON(filepath.Join(root, ".state", "update-state.json"), legacy); err != nil {
		t.Fatal(err)
	}
	u, err := New(Config{ServerURL: "https://updates.example", InstallDir: root, VersionCode: 1})
	if err != nil {
		t.Fatal(err)
	}
	snapshot := u.Snapshot()
	if !snapshot.LastAttempt.Equal(lastCheck) || !snapshot.LastSuccessfulCheck.Equal(lastCheck) || snapshot.Phase != "idle" {
		t.Fatalf("legacy migration snapshot = %+v", snapshot)
	}
	if delay := u.nextCheck(); delay < CheckInterval-2*time.Hour || delay > CheckInterval-time.Hour {
		t.Fatalf("legacy successful check interval = %s", delay)
	}
}

func TestRunAlwaysChecksAtStartupThenWaitsTwentyHours(t *testing.T) {
	if CheckInterval != 20*time.Hour {
		t.Fatalf("unexpected interval: %s", CheckInterval)
	}
	var requests atomic.Int32
	checked := make(chan struct{}, 4)
	feed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusNoContent)
		checked <- struct{}{}
	}))
	defer feed.Close()
	cfg := Config{ServerURL: feed.URL, InstallDir: t.TempDir(), VersionCode: 1}
	u, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	// A previous process checked moments ago; each new process still checks.
	if _, err = u.Check(context.Background()); err != nil {
		t.Fatal(err)
	}
	<-checked
	for i := 0; i < 2; i++ {
		restarted, err := New(cfg)
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		go func() { defer close(done); restarted.Run(ctx, make(chan *Plan)) }()
		select {
		case <-checked:
		case <-time.After(time.Second):
			cancel()
			<-done
			t.Fatal("startup check was throttled by the persisted interval")
		}
		select {
		case <-checked:
			t.Error("startup check immediately repeated")
		case <-time.After(30 * time.Millisecond):
		}
		cancel()
		<-done
		if delay := restarted.nextCheck(); delay < CheckInterval-time.Minute || delay > CheckInterval {
			t.Fatalf("next periodic check is not 20h after startup: %s", delay)
		}
	}
	if requests.Load() != 3 {
		t.Fatalf("unexpected startup checks: %d", requests.Load())
	}
}

func TestDownloadValidationAndVersionComparison(t *testing.T) {
	archive := testArchive(t, map[string]string{"CodexRM/.state/connection.json": "must not replace local secrets"})
	for _, mode := range []string{"new", "equal", "older", "wrong hash", "wrong size", "truncated", "server error"} {
		t.Run(mode, func(t *testing.T) {
			var downloads atomic.Int32
			manifest := testManifest(archive)
			switch mode {
			case "equal":
				manifest.VersionCode = 1
			case "older":
				manifest.VersionCode = 1
			case "wrong hash":
				manifest.SHA256 = strings.Repeat("0", 64)
			case "wrong size":
				manifest.FileSize++
			}
			h := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != "" || r.Header.Get("X-Enrollment-Key") != "" {
					t.Error("update requests leaked enrollment credentials")
				}
				if r.URL.Path == "/base/update.json" {
					if mode == "server error" {
						w.WriteHeader(500)
						return
					}
					_ = json.NewEncoder(w).Encode(manifest)
					return
				}
				if r.URL.Path != "/base/updates/client.zip" {
					t.Errorf("wrong relative download URL: %s", r.URL.Path)
				}
				downloads.Add(1)
				data := archive
				if mode == "truncated" {
					data = data[:len(data)-1]
				}
				_, _ = w.Write(data)
			}))
			defer h.Close()
			root := t.TempDir()
			currentVersion := int64(1)
			if mode == "older" {
				currentVersion = 3
			}
			u, _ := New(Config{ServerURL: h.URL + "/base", InstallDir: root, VersionCode: currentVersion})
			plan, err := u.Check(context.Background())
			switch mode {
			case "new":
				if err != nil || plan == nil || downloads.Load() != 1 {
					t.Fatalf("update preparation: %v, %+v", err, plan)
				}
				if _, err := os.Stat(plan.payload(".state/connection.json")); !errors.Is(err, os.ErrNotExist) {
					t.Fatal("private package configuration was extracted")
				}
				plan.Discard()
			case "equal", "older":
				if err != nil || plan != nil || downloads.Load() != 0 {
					t.Fatalf("downloaded non-newer version: %v", err)
				}
			default:
				if err == nil || plan != nil {
					t.Fatal("invalid update accepted")
				}
			}
			// Failed requests also observe the daily interval.
			if _, err := u.Check(context.Background()); err != nil {
				t.Fatalf("immediate duplicate attempt: %v", err)
			}
			stages, _ := filepath.Glob(filepath.Join(root, ".state", "update-*"))
			for _, stage := range stages {
				if info, _ := os.Stat(stage); info != nil && info.IsDir() {
					t.Fatalf("discarded/invalid update left a staged directory: %s", stage)
				}
			}
		})
	}
}

func TestArchiveCannotEscapeOrOverwriteLocalState(t *testing.T) {
	for _, malicious := range []string{"../outside", "CodexRM/../../outside", "C:/outside", "CodexRM/bin/../install.cmd"} {
		t.Run(malicious, func(t *testing.T) {
			root := t.TempDir()
			file := filepath.Join(root, "package.zip")
			if err := os.WriteFile(file, testArchive(t, map[string]string{malicious: "bad"}), 0600); err != nil {
				t.Fatal(err)
			}
			if extractPackage(file, filepath.Join(root, "payload")) == nil {
				t.Fatal("accepted traversal path")
			}
		})
	}
}

func TestFileReplacementAndRollbackPreserveConfiguration(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(map[bool]string{false: "successful replacement", true: "partial failure"}[fail], func(t *testing.T) {
			root := t.TempDir()
			stage := filepath.Join(root, ".state", "update-test")
			p := &Plan{InstallDir: root, StageDir: stage}
			for _, relative := range managedFiles {
				for _, location := range []struct{ path, content string }{
					{filepath.Join(root, filepath.FromSlash(relative)), "old"},
					{p.payload(relative), "new"},
				} {
					_ = os.MkdirAll(filepath.Dir(location.path), 0700)
					if err := os.WriteFile(location.path, []byte(location.content), 0700); err != nil {
						t.Fatal(err)
					}
				}
			}
			config := filepath.Join(root, ".state", "connection.json")
			_ = os.WriteFile(config, []byte("local connection and credentials"), 0600)
			if fail {
				_ = os.Remove(p.payload(managedFiles[len(managedFiles)-1]))
			}
			rollback, err := p.replaceFiles()
			if fail && err == nil || !fail && err != nil {
				t.Fatalf("replacement result: %v", err)
			}
			if !fail {
				data, _ := os.ReadFile(filepath.Join(root, filepath.FromSlash(managedFiles[0])))
				if string(data) != "new" {
					t.Fatal("new files not placed")
				}
				if err := rollback(); err != nil {
					t.Fatal(err)
				}
			}
			for _, relative := range managedFiles {
				data, _ := os.ReadFile(filepath.Join(root, filepath.FromSlash(relative)))
				if string(data) != "old" {
					t.Fatalf("rollback did not restore %s", relative)
				}
			}
			data, _ := os.ReadFile(config)
			if string(data) != "local connection and credentials" {
				t.Fatal("configuration was changed")
			}
		})
	}
}

func TestRunCancellationWhileWaiting(t *testing.T) {
	u, _ := New(Config{ServerURL: "https://example.com", InstallDir: t.TempDir(), VersionCode: 1})
	if err := writeJSON(u.statePath(), checkState{ServerURL: u.cfg.ServerURL, ManifestURL: u.cfg.ManifestURL, LastCheck: time.Now()}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { u.Run(ctx, make(chan *Plan)); close(done) }()
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("updater did not stop while waiting for the next daily check")
	}
}

func TestExternalManifestAndManualChecksBypassDailyInterval(t *testing.T) {
	archive := testArchive(t, nil)
	var checks, downloads atomic.Int32
	feed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "" {
			t.Error("credentials sent to update feed")
		}
		if r.URL.Path == "/f/permanent/CodexRM-update.json" && r.URL.Query().Get("key") == "public" {
			checks.Add(1)
			m := testManifest(archive)
			m.DownloadURL = "client.zip"
			_ = json.NewEncoder(w).Encode(m)
		} else if r.URL.Path == "/f/permanent/client.zip" {
			downloads.Add(1)
			_, _ = w.Write(archive)
		} else {
			http.NotFound(w, r)
		}
	}))
	defer feed.Close()
	cfg := Config{ServerURL: "https://unrelated-rm.example", ManifestURL: feed.URL + "/f/permanent/CodexRM-update.json?key=public", InstallDir: t.TempDir(), VersionCode: 1}
	u, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := u.Check(context.Background())
	if err != nil || plan == nil {
		t.Fatalf("external feed: %v", err)
	}
	plan.Discard()
	if _, err = u.Check(context.Background()); err != nil || checks.Load() != 1 {
		t.Fatal("automatic interval was bypassed")
	}
	for i := 0; i < 2; i++ {
		m, err := u.Inspect(context.Background())
		if err != nil || m == nil || m.VersionCode != 2 {
			t.Fatalf("manual check: %v", err)
		}
	}
	if downloads.Load() != 1 || checks.Load() != 3 {
		t.Fatal("manual inspection downloaded a package or was throttled")
	}
	plan, err = u.Prepare(context.Background())
	if err != nil || plan == nil || downloads.Load() != 2 {
		t.Fatalf("manual installation was throttled: %v", err)
	}
	plan.Discard()
	cfg.ManifestURL = feed.URL + "/different.json"
	changed, _ := New(cfg)
	if changed.nextCheck() != 0 {
		t.Fatal("feed change reused the previous feed interval")
	}
}

func TestDefaultRMManifestDownloadsPackageFromSeparateCloudreveHost(t *testing.T) {
	archive := testArchive(t, nil)
	var downloads atomic.Int32
	cloud := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/f/client.zip" {
			t.Errorf("unexpected Cloudreve path: %s", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "" || r.Header.Get("X-Enrollment-Key") != "" {
			t.Error("RM credentials leaked to Cloudreve")
		}
		downloads.Add(1)
		_, _ = w.Write(archive)
	}))
	defer cloud.Close()
	var checks atomic.Int32
	rm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/rm/update.json" {
			t.Errorf("unexpected RM path: %s", r.URL.Path)
		}
		checks.Add(1)
		m := testManifest(archive)
		m.DownloadURL = cloud.URL + "/f/client.zip"
		_ = json.NewEncoder(w).Encode(m)
	}))
	defer rm.Close()
	u, err := New(Config{ServerURL: rm.URL + "/rm/", InstallDir: t.TempDir(), VersionCode: 1})
	if err != nil {
		t.Fatal(err)
	}
	m, err := u.Inspect(context.Background())
	if err != nil || m == nil || downloads.Load() != 0 {
		t.Fatalf("RM manifest inspection: %v", err)
	}
	plan, err := u.Check(context.Background())
	if err != nil || plan == nil || checks.Load() != 2 || downloads.Load() != 1 {
		t.Fatalf("RM-to-Cloudreve update: %v", err)
	}
	plan.Discard()
}

func TestStaleUpdateCleanupPreservesFreshAndActiveStages(t *testing.T) {
	root := t.TempDir()
	stateDir := filepath.Join(root, ".state")
	u, err := New(Config{ServerURL: "https://example.com", InstallDir: root, VersionCode: 1})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	u.now = func() time.Time { return now }
	makeStage := func(name string, age time.Duration, helperPID int, completed bool) string {
		t.Helper()
		stage := filepath.Join(stateDir, "update-"+name)
		if err = os.MkdirAll(stage, 0o700); err != nil {
			t.Fatal(err)
		}
		if helperPID > 0 {
			if err = os.WriteFile(filepath.Join(stage, "helper.pid"), []byte(strconv.Itoa(helperPID)), 0o600); err != nil {
				t.Fatal(err)
			}
		}
		if completed {
			if err = os.WriteFile(filepath.Join(stage, "completed"), []byte("done"), 0o600); err != nil {
				t.Fatal(err)
			}
		}
		modified := now.Add(-age)
		if err = os.Chtimes(stage, modified, modified); err != nil {
			t.Fatal(err)
		}
		return stage
	}

	stale := makeStage("stale", 48*time.Hour, 0, false)
	fresh := makeStage("fresh", time.Hour, 0, false)
	active := makeStage("active", 48*time.Hour, os.Getpid(), false)
	completed := makeStage("completed", 48*time.Hour, 0, true)
	u.cleanCompletedUpdates()

	for _, stage := range []string{stale, completed} {
		if _, err = os.Stat(stage); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("stage should have been removed: %s (err=%v)", stage, err)
		}
	}
	for _, stage := range []string{fresh, active} {
		if info, statErr := os.Stat(stage); statErr != nil || !info.IsDir() {
			t.Fatalf("stage should have been preserved: %s (err=%v)", stage, statErr)
		}
	}
}
