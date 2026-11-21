//go:build !windows

package store

import (
	"log"
	"os"
	"path/filepath"
)

func replaceState(temp, path string) error {
	dir, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer dir.Close()
	if err := os.Rename(temp, path); err != nil {
		return err
	}
	// Rename is the commit point. Keep memory consistent with the visible
	// file even if this filesystem cannot make the directory entry durable.
	if err := dir.Sync(); err != nil {
		log.Printf("sync relay state directory: %v", err)
	}
	return nil
}
