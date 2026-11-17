package updater

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// Real Windows processes exercise executable locking, detached replacement and restart.
func TestMain(m *testing.M) {
	if os.Getenv("CODEXRM_UPDATER_FIXTURE") != "1" {
		os.Exit(m.Run())
	}
	if len(os.Args) < 2 {
		os.Exit(2)
	}
	switch os.Args[1] {
	case "--version":
		fmt.Print(`{"versionCode":2,"versionName":"test"}`)
	case "fixture-old":
		p, err := loadPlan(os.Getenv("CODEXRM_FIXTURE_PLAN"))
		if err != nil {
			panic(err)
		}
		p.ParentPID = os.Getpid()
		if err = p.StartHelper(); err != nil {
			panic(err)
		}
	case "--apply-update":
		p, err := loadPlan(os.Args[2])
		if err != nil {
			panic(err)
		}
		_ = os.WriteFile(filepath.Join(p.StageDir, "fixture-helper.pid"), []byte(strconv.Itoa(os.Getpid())), 0600)
		if err = Apply(os.Args[2]); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "fixture-new":
		updated := os.Getenv(readyEnv) != ""
		if updated && os.Getenv("CODEXRM_FIXTURE_FAIL_READY") == "1" {
			os.Exit(3)
		}
		SignalReady()
		data, _ := json.Marshal(map[string]any{"pid": os.Getpid(), "updated": updated})
		if err := os.WriteFile(os.Getenv("CODEXRM_FIXTURE_RUNNING"), data, 0600); err != nil {
			panic(err)
		}
		for {
			time.Sleep(time.Second)
		}
	default:
		os.Exit(2)
	}
	os.Exit(0)
}

func TestWindowsExecutableReplacementAndRestart(t *testing.T) {
	for _, failReady := range []bool{false, true} {
		t.Run(map[bool]string{false: "silent successful restart", true: "failed startup restores previous version"}[failReady], func(t *testing.T) {
			root := filepath.Join(t.TempDir(), "installed app [test] 中文")
			stage := filepath.Join(root, ".state", "update-test")
			p := &Plan{InstallDir: root, StageDir: stage, ParentPID: 1, Args: []string{"fixture-new"}, WorkingDir: root, Manifest: testManifest([]byte("fixture"))}
			self, err := os.Executable()
			if err != nil {
				t.Fatal(err)
			}
			for _, relative := range managedFiles {
				for _, target := range []string{filepath.Join(root, filepath.FromSlash(relative)), p.payload(relative)} {
					if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
						t.Fatal(err)
					}
					if relative == "bin/codexrm-agent.exe" {
						if err := copyFile(self, target); err != nil {
							t.Fatal(err)
						}
					} else {
						content := "old"
						if strings.Contains(target, "payload") {
							content = "new"
						}
						_ = os.WriteFile(target, []byte(content), 0600)
					}
				}
			}
			config := filepath.Join(root, ".state", "connection.json")
			_ = os.WriteFile(config, []byte("local private connection"), 0600)
			if err := writeJSON(p.planPath(), p); err != nil {
				t.Fatal(err)
			}
			runningPath := filepath.Join(stage, "fixture-running.json")
			cmd := exec.Command(filepath.Join(root, "bin", "codexrm-agent.exe"), "fixture-old")
			cmd.Env = append(withoutReadyEnv(os.Environ()), "CODEXRM_UPDATER_FIXTURE=1", "CODEXRM_FIXTURE_PLAN="+p.planPath(), "CODEXRM_FIXTURE_RUNNING="+runningPath)
			if failReady {
				cmd.Env = append(cmd.Env, "CODEXRM_FIXTURE_FAIL_READY=1")
			}
			hideProcess(cmd)
			var output strings.Builder
			cmd.Stdout, cmd.Stderr = &output, &output
			if err := cmd.Start(); err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				_ = cmd.Process.Kill()
				var running struct{ PID int }
				data, _ := os.ReadFile(runningPath)
				_ = json.Unmarshal(data, &running)
				if running.PID > 0 {
					if process, err := os.FindProcess(running.PID); err == nil {
						_ = process.Kill()
						_, _ = process.Wait()
					}
				}
				data, _ = os.ReadFile(filepath.Join(stage, "fixture-helper.pid"))
				pid, _ := strconv.Atoi(string(data))
				if pid > 0 {
					if process, err := os.FindProcess(pid); err == nil {
						_ = process.Kill()
						_, _ = process.Wait()
					}
				}
			})
			if err := cmd.Wait(); err != nil {
				t.Fatalf("original agent: %v\n%s", err, output.String())
			}
			deadline := time.Now().Add(20 * time.Second)
			var running struct {
				PID     int  `json:"pid"`
				Updated bool `json:"updated"`
			}
			for {
				data, err := os.ReadFile(runningPath)
				if err == nil && json.Unmarshal(data, &running) == nil {
					break
				}
				if time.Now().After(deadline) {
					log, _ := os.ReadFile(filepath.Join(root, ".state", "update.log"))
					t.Fatalf("no restarted client\n%s", log)
				}
				time.Sleep(50 * time.Millisecond)
			}
			if running.Updated == failReady {
				t.Fatalf("wrong restart outcome: %+v", running)
			}
			if !failReady {
				for {
					if _, err := os.Stat(filepath.Join(stage, "completed")); err == nil {
						break
					}
					if time.Now().After(deadline) {
						t.Fatal("helper did not finish the update")
					}
					time.Sleep(50 * time.Millisecond)
				}
			}
			for _, relative := range managedFiles[1:] {
				data, _ := os.ReadFile(filepath.Join(root, filepath.FromSlash(relative)))
				expected := "new"
				if failReady {
					expected = "old"
				}
				if string(data) != expected {
					t.Fatalf("%s = %q, want %s", relative, data, expected)
				}
			}
			data, _ := os.ReadFile(config)
			if string(data) != "local private connection" {
				t.Fatal("silent update overwrote the local connection")
			}
		})
	}
}
