//go:build linux || darwin || dragonfly || freebsd || netbsd || openbsd

package store

import (
	"golang.org/x/sys/unix"
	"os"
)

func lockState(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_EX|unix.LOCK_NB)
}
