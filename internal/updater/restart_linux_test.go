package updater

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

const linuxFixtureEnv = "CODEXRM_TEST_LINUX_UPDATE"

type linuxFixtureResult struct {
	PID        int
	Cwd        string
	Arg        string
	Updated    bool
	RolledBack bool
}

func TestMain(m *testing.M) {
	mode := os.Getenv(linuxFixtureEnv)
	if mode == "" {
		os.Exit(m.Run())
	}
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		fmt.Println(`{"versionCode":2,"versionName":"test"}`)
		return
	}
	if err := runLinuxFixture(mode); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runLinuxFixture(mode string) error {
	updated, rolledBack := os.Getenv(linuxUpdateEnv) != "", os.Getenv(rollbackEnv) == "1"
	if updated && mode == "rollback" {
		return RollbackStartup()
	}
	if !updated && !rolledBack {
		feed := os.Getenv("CODEXRM_TEST_FEED")
		u, err := New(Config{ServerURL: feed, ManifestURL: feed + "/linux-update.json", StateDir: os.Getenv("CODEXRM_TEST_STATE"), LinuxExecutable: LinuxExecutable(), VersionCode: 1})
		if err != nil {
			return err
		}
		p, err := u.Prepare(context.Background())
		if err != nil {
			return err
		}
		if p == nil {
			return fmt.Errorf("Linux package was not prepared")
		}
		defer p.Discard()
		update, err := p.StageLinux()
		if err != nil {
			return err
		}
		if mode == "cancel" {
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			return update.Restart(ctx)
		}
		return update.Restart(context.Background())
	}
	SignalReady()
	cwd, err := os.Getwd()
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(linuxFixtureResult{PID: os.Getpid(), Cwd: cwd, Arg: os.Args[1], Updated: updated, RolledBack: rolledBack})
}

func TestLinuxUpdateReexec(t *testing.T) {
	if LinuxExecutable() == "" {
		t.Skip("requires Linux amd64 and an executable test directory")
	}
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	old, err := os.ReadFile(self)
	if err != nil {
		t.Fatal(err)
	}
	next := append(bytes.Clone(old), []byte("updated Linux fixture")...)
	pkg := testArchive(t, map[string]string{linuxBinary: string(next), "connection.json": "must not install"})
	manifest := testManifest(pkg)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/linux-update.json":
			_ = json.NewEncoder(w).Encode(manifest)
		case "/linux-bad-version/linux-update.json":
			bad := manifest
			bad.VersionCode++
			_ = json.NewEncoder(w).Encode(bad)
		case "/" + manifest.DownloadURL, "/linux-bad-version/" + manifest.DownloadURL:
			_, _ = w.Write(pkg)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	for _, mode := range []string{"commit", "rollback", "cancel", "bad-version"} {
		t.Run(mode, func(t *testing.T) {
			root := t.TempDir()
			state := t.TempDir()
			target := filepath.Join(root, "custom-agent-name")
			if err := os.WriteFile(target, old, 0750); err != nil {
				t.Fatal(err)
			}
			launcher := filepath.Join(root, "agent-link")
			if err := os.Symlink(target, launcher); err != nil {
				t.Fatal(err)
			}
			config := filepath.Join(state, "connection.json")
			if err := os.WriteFile(config, []byte("saved config"), 0600); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			feed := srv.URL
			if mode == "bad-version" {
				feed += "/linux-bad-version"
			}
			cmd := exec.CommandContext(ctx, launcher, "original argument")
			cmd.Dir = state
			cmd.Env = append(os.Environ(), linuxFixtureEnv+"="+mode, "CODEXRM_TEST_FEED="+feed, "CODEXRM_TEST_STATE="+state)
			var stdout, stderr bytes.Buffer
			cmd.Stdout, cmd.Stderr = &stdout, &stderr
			err := cmd.Run()
			if mode == "bad-version" {
				if err == nil || !bytes.Contains(stderr.Bytes(), []byte("version differs")) {
					t.Fatalf("wrong package accepted: %v\n%s", err, &stderr)
				}
			} else if err != nil {
				t.Fatalf("update process: %v\n%s", err, &stderr)
			}
			if mode == "commit" || mode == "rollback" {
				var result linuxFixtureResult
				if err := json.Unmarshal(stdout.Bytes(), &result); err != nil {
					t.Fatalf("restart output: %v\n%s", err, &stderr)
				}
				want := linuxFixtureResult{PID: cmd.Process.Pid, Cwd: state, Arg: "original argument", Updated: mode == "commit", RolledBack: mode == "rollback"}
				if result != want {
					t.Fatalf("restart = %+v, want %+v", result, want)
				}
			}
			binary, err := os.ReadFile(target)
			want := old
			if mode == "commit" {
				want = next
			}
			if err != nil || !bytes.Equal(binary, want) {
				t.Fatalf("incorrect installed executable: %v", err)
			}
			info, err := os.Stat(target)
			if err != nil || info.Mode().Perm() != 0750 {
				t.Fatalf("executable permissions changed: %v, %v", info, err)
			}
			data, err := os.ReadFile(config)
			if err != nil || string(data) != "saved config" {
				t.Fatalf("saved config changed: %s, %v", data, err)
			}
			for _, glob := range []string{filepath.Join(root, linuxTempPrefix+"*"), filepath.Join(state, "update-*")} {
				files, err := filepath.Glob(glob)
				for _, file := range files {
					if filepath.Base(file) != "update-state.json" {
						t.Errorf("unexpected staging file left behind: %s", file)
					}
				}
				if err != nil {
					t.Fatal(err)
				}
			}
		})
	}
}
