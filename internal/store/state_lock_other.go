//go:build !windows && !linux && !darwin && !dragonfly && !freebsd && !netbsd && !openbsd

package store

import (
	"errors"
	"os"
)

func lockState(*os.File) error {
	return errors.New("relay state file locking is unavailable on this platform")
}
