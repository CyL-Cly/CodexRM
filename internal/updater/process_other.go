//go:build !windows

package updater

import (
	"errors"
	"os"
	"os/exec"
	"syscall"
	"time"
)

func hideProcess(cmd *exec.Cmd) {}

func processRunning(pid int) bool {
	process, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	err = process.Signal(syscall.Signal(0))
	return err == nil || errors.Is(err, syscall.EPERM)
}

func waitForProcess(pid int, timeout time.Duration) error {
	return errors.New("automatic client replacement is supported on Windows")
}
