package updater

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

const (
	linuxUpdateEnv  = "CODEXRM_LINUX_UPDATE_DIR"
	linuxTempPrefix = ".codexrm-agent-update-"
)

// LinuxExecutable returns the persistent, replaceable binary behind the current
// process (even if the launcher used a symlink). The release feed is amd64 only.
func LinuxExecutable() string {
	if runtime.GOARCH != "amd64" {
		return ""
	}
	executable, err := os.Executable()
	if err != nil {
		return ""
	}
	if _, err := runningLinuxBinary(executable); err != nil {
		return ""
	}
	if unix.Faccessat(unix.AT_FDCWD, filepath.Dir(executable), unix.W_OK|unix.X_OK, unix.AT_EACCESS) != nil {
		return ""
	}
	return executable
}

func runningLinuxBinary(executable string) (os.FileInfo, error) {
	info, err := os.Lstat(executable)
	if err != nil {
		return nil, err
	}
	running, err := os.Stat("/proc/self/exe")
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || !os.SameFile(running, info) || info.Mode()&(os.ModeSetuid|os.ModeSetgid) != 0 || int(info.Sys().(*syscall.Stat_t).Uid) != os.Geteuid() {
		return nil, errors.New("Linux update target must be the running agent and owned by its user")
	}
	return info, nil
}

// LinuxUpdate holds the replacement and its backup on the executable's own
// filesystem, which may differ from the settings directory's filesystem.
type LinuxUpdate struct {
	plan *Plan
	dir  *os.File // Flock serializes updates to binaries in this directory.
	work string
}

// StageLinux copies the payload and saves a link to the current binary. Until
// Restart runs, the installed path still points to the old executable.
func (p *Plan) StageLinux() (*LinuxUpdate, error) {
	if p.linuxExecutable == "" || p.ParentPID != os.Getpid() {
		return nil, errors.New("missing running Linux agent in update plan")
	}
	dir, err := os.Open(filepath.Dir(p.linuxExecutable))
	if err != nil {
		return nil, err
	}
	u := &LinuxUpdate{plan: p, dir: dir}
	staged := false
	defer func() {
		if !staged {
			u.close()
		}
	}()
	if err := unix.Flock(int(dir.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		return nil, fmt.Errorf("lock Linux agent directory: %w", err)
	}
	info, err := runningLinuxBinary(p.linuxExecutable)
	if err != nil {
		return nil, err
	}
	u.work, err = os.MkdirTemp(dir.Name(), linuxTempPrefix)
	if err != nil {
		return nil, err
	}
	next := filepath.Join(u.work, "next")
	if err := copyFile(p.payload(linuxBinary), next); err != nil {
		return nil, err
	}
	if err := os.Chmod(next, info.Mode().Perm()); err != nil {
		return nil, err
	}
	if err := os.Link(p.linuxExecutable, filepath.Join(u.work, "previous")); err != nil {
		return nil, err
	}
	staged = true
	return u, nil
}

func (u *LinuxUpdate) close() {
	if u.work != "" {
		_ = os.RemoveAll(u.work)
	}
	if u.dir != nil {
		_ = u.dir.Close()
		u.dir = nil
	}
}

// Restart is called after the old agent has stopped serving requests. Exec
// preserves the PID, working directory, arguments and service environment.
func (u *LinuxUpdate) Restart(ctx context.Context) error {
	defer u.close()
	p := u.plan
	p.Discard()
	if ctx.Err() != nil {
		return nil
	}
	target := p.linuxExecutable
	if _, err := runningLinuxBinary(target); err != nil {
		return err
	}
	err := os.Rename(filepath.Join(u.work, "next"), target)
	if err == nil {
		log.Printf("restarting Linux agent with %s (%d)", p.Manifest.VersionName, p.Manifest.VersionCode)
		err = unix.Exec(target, os.Args, updateEnvironment(linuxUpdateEnv+"="+u.work))
		if restoreErr := os.Rename(filepath.Join(u.work, "previous"), target); restoreErr != nil {
			log.Printf("previous Linux agent retained at %s", filepath.Join(u.work, "previous"))
			u.work = "" // Keep the backup for manual recovery.
			return errors.Join(err, restoreErr)
		}
	}
	log.Printf("Linux update failed, restarting previous version: %v", err)
	u.close()
	return errors.Join(err, unix.Exec(target, os.Args, updateEnvironment(rollbackEnv+"=1")))
}

func updateEnvironment(value string) []string {
	env := os.Environ()
	out := env[:0]
	for _, entry := range env {
		name, _, _ := strings.Cut(entry, "=")
		if name != linuxUpdateEnv && name != rollbackEnv {
			out = append(out, entry)
		}
	}
	return append(out, value)
}

func linuxStartupUpdate() (target, work string) {
	work = os.Getenv(linuxUpdateEnv)
	if work == "" {
		return "", ""
	}
	target, err := os.Executable()
	if err != nil || filepath.Dir(work) != filepath.Dir(target) || filepath.Clean(work) != work || !strings.HasPrefix(filepath.Base(work), linuxTempPrefix) {
		return "", ""
	}
	if _, err := runningLinuxBinary(target); err != nil {
		return "", ""
	}
	info, err := os.Lstat(work)
	if err != nil || !info.IsDir() || info.Mode().Perm() != 0700 || int(info.Sys().(*syscall.Stat_t).Uid) != os.Geteuid() {
		return "", ""
	}
	return target, work
}

func signalLinuxReady() {
	_, work := linuxStartupUpdate()
	_ = os.Unsetenv(linuxUpdateEnv)
	if work != "" {
		if err := os.RemoveAll(work); err != nil {
			log.Printf("could not remove Linux update backup %s: %v", work, err)
		}
	}
}

// RollbackStartup is called on an explicit startup error before SignalReady.
// If the new process crashes, the backup remains available beside the binary.
func RollbackStartup() error {
	target, work := linuxStartupUpdate()
	if work == "" {
		return nil
	}
	backup := filepath.Join(work, "previous")
	if info, err := os.Lstat(backup); err != nil || !info.Mode().IsRegular() {
		return fmt.Errorf("missing Linux update backup at %s", backup)
	}
	if err := os.Rename(backup, target); err != nil {
		return fmt.Errorf("restore Linux agent from %s: %w", backup, err)
	}
	_ = os.Remove(work)
	log.Printf("Linux update did not start, restarting previous version")
	return unix.Exec(target, os.Args, updateEnvironment(rollbackEnv+"=1"))
}
