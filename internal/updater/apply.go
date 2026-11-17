package updater

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const readyEnv = "CODEXRM_UPDATE_READY"

// StartHelper copies the running executable away from its replacement target.
// The detached helper waits for the original agent to close Codex and exit.
func (p *Plan) StartHelper() error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	expected := filepath.Join(p.InstallDir, "bin", "codexrm-agent.exe")
	if !strings.EqualFold(filepath.Clean(executable), expected) {
		return errors.New("automatic replacement requires the installed Windows agent")
	}
	if err = p.VerifyBinary(context.Background()); err != nil {
		return err
	}
	helper := filepath.Join(p.StageDir, "updater.exe")
	if err = copyFile(executable, helper); err != nil {
		return err
	}
	if err = writeJSON(p.planPath(), p); err != nil {
		return err
	}
	cmd := exec.Command(helper, "--apply-update", p.planPath())
	hideProcess(cmd)
	logFile, err := openUpdateLog(p.InstallDir)
	if err != nil {
		return err
	}
	defer logFile.Close()
	cmd.Stdout, cmd.Stderr = logFile, logFile
	if err = cmd.Start(); err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(p.StageDir, "helper.pid"), []byte(strconv.Itoa(cmd.Process.Pid)), 0600); err != nil {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		return err
	}
	_ = cmd.Process.Release()
	return nil
}

func openUpdateLog(root string) (*os.File, error) {
	return os.OpenFile(filepath.Join(root, ".state", "update.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
}

// InstallStopped is called by a temporary copy of the agent while the installed connector is stopped.
func (p *Plan) InstallStopped(ctx context.Context) error {
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	if strings.EqualFold(filepath.Clean(executable), filepath.Join(p.InstallDir, "bin", "codexrm-agent.exe")) {
		return errors.New("manual update requires a temporary copy of the agent")
	}
	if err := p.VerifyBinary(ctx); err != nil {
		return err
	}
	_, err = p.replaceFiles()
	if err == nil {
		p.complete()
	}
	return err
}

func loadPlan(planPath string) (*Plan, error) {
	data, err := os.ReadFile(planPath)
	if err != nil {
		return nil, err
	}
	var p Plan
	if err = json.Unmarshal(data, &p); err != nil {
		return nil, err
	}
	root, err := filepath.Abs(p.InstallDir)
	if err != nil {
		return nil, err
	}
	stage, err := filepath.Abs(p.StageDir)
	if err != nil {
		return nil, err
	}
	if p.ParentPID <= 0 || !strings.EqualFold(filepath.Dir(stage), filepath.Join(root, ".state")) ||
		!strings.HasPrefix(filepath.Base(stage), "update-") || !strings.EqualFold(filepath.Clean(planPath), filepath.Join(stage, "plan.json")) {
		return nil, errors.New("invalid local update plan location")
	}
	p.InstallDir, p.StageDir = root, stage
	return &p, p.Manifest.Validate()
}

// Apply runs in the copied helper, never in the executable being replaced.
func Apply(planPath string) error {
	p, err := loadPlan(planPath)
	if err != nil {
		return err
	}
	if err = waitForProcess(p.ParentPID, 2*time.Minute); err != nil {
		return err
	}
	rollback, err := p.replaceFiles()
	if err != nil {
		if restartErr := p.restart(false); restartErr != nil {
			return fmt.Errorf("%v; restart restored client: %w", err, restartErr)
		}
		return err
	}
	if err = p.restart(true); err != nil {
		if restoreErr := rollback(); restoreErr != nil {
			return fmt.Errorf("%v; rollback failed: %w", err, restoreErr)
		}
		if restartErr := p.restart(false); restartErr != nil {
			return fmt.Errorf("%v; restart previous client: %w", err, restartErr)
		}
		p.complete()
		return fmt.Errorf("new client failed; restored previous version: %w", err)
	}
	p.complete()
	log.Printf("updated Windows client to %s (%d)", p.Manifest.VersionName, p.Manifest.VersionCode)
	return nil
}

func (p *Plan) complete() {
	// Keep the helper executable until the next check; Windows locks running images.
	_ = os.RemoveAll(filepath.Join(p.StageDir, "backup"))
	_ = os.RemoveAll(filepath.Join(p.StageDir, "payload"))
	_ = os.Remove(filepath.Join(p.StageDir, "package.zip"))
	_ = os.WriteFile(filepath.Join(p.StageDir, "completed"), []byte(time.Now().UTC().Format(time.RFC3339)), 0600)
}

func renameWithRetry(from, to string) error {
	deadline := time.Now().Add(10 * time.Second)
	for {
		err := os.Rename(from, to)
		if err == nil || errors.Is(err, os.ErrNotExist) || time.Now().After(deadline) {
			return err
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func (p *Plan) replaceFiles() (func() error, error) {
	type change struct {
		relative string
		backedUp bool
		placed   bool
	}
	var changes []change
	rollback := func() error {
		var failures []error
		for i := len(changes) - 1; i >= 0; i-- {
			c := changes[i]
			target := filepath.Join(p.InstallDir, filepath.FromSlash(c.relative))
			if c.placed {
				if err := renameWithRetry(target, p.payload(c.relative)); err != nil {
					failures = append(failures, err)
					continue
				}
			}
			if c.backedUp {
				backup := filepath.Join(p.StageDir, "backup", filepath.FromSlash(c.relative))
				if err := renameWithRetry(backup, target); err != nil {
					failures = append(failures, err)
				}
			}
		}
		return errors.Join(failures...)
	}
	for _, relative := range managedFiles {
		target := filepath.Join(p.InstallDir, filepath.FromSlash(relative))
		backup := filepath.Join(p.StageDir, "backup", filepath.FromSlash(relative))
		if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return rollback, errors.Join(err, rollback())
		}
		if err := os.MkdirAll(filepath.Dir(backup), 0700); err != nil {
			return rollback, errors.Join(err, rollback())
		}
		changes = append(changes, change{relative: relative})
		c := &changes[len(changes)-1]
		if _, err := os.Lstat(target); err == nil {
			if err = renameWithRetry(target, backup); err != nil {
				return rollback, errors.Join(err, rollback())
			}
			c.backedUp = true
		} else if !errors.Is(err, os.ErrNotExist) {
			return rollback, errors.Join(err, rollback())
		}
		if err := renameWithRetry(p.payload(relative), target); err != nil {
			return rollback, errors.Join(err, rollback())
		}
		c.placed = true
	}
	return rollback, nil
}

func (p *Plan) restart(expectReady bool) error {
	cmd := exec.Command(filepath.Join(p.InstallDir, "bin", "codexrm-agent.exe"), p.Args...)
	cmd.Dir = p.WorkingDir
	hideProcess(cmd)
	marker := filepath.Join(p.StageDir, "started")
	cmd.Env = withoutReadyEnv(os.Environ())
	if expectReady {
		_ = os.Remove(marker)
		cmd.Env = append(cmd.Env, readyEnv+"="+marker)
	}
	logFile, err := openUpdateLog(p.InstallDir)
	if err != nil {
		return err
	}
	defer logFile.Close()
	cmd.Stdout, cmd.Stderr = logFile, logFile
	if err = cmd.Start(); err != nil {
		return err
	}
	if !expectReady {
		return cmd.Process.Release()
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	timeout := time.NewTimer(60 * time.Second)
	defer timeout.Stop()
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return errors.New("updated client exited before becoming ready")
		case <-timeout.C:
			_ = cmd.Process.Kill()
			<-done
			return errors.New("updated client did not become ready within 60s")
		case <-ticker.C:
			if _, err := os.Stat(marker); err == nil {
				return nil
			}
		}
	}
}

func withoutReadyEnv(env []string) []string {
	var result []string
	for _, entry := range env {
		if !strings.HasPrefix(strings.ToUpper(entry), readyEnv+"=") {
			result = append(result, entry)
		}
	}
	return result
}

// SignalReady is called only after Codex initialized and the connector can serve requests.
func SignalReady() {
	path := os.Getenv(readyEnv)
	_ = os.Unsetenv(readyEnv)
	if path != "" {
		if err := os.WriteFile(path, []byte("ready"), 0600); err != nil {
			log.Printf("update readiness: %v", err)
		}
	}
}
