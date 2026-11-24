package serverupdate

import (
	"archive/zip"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/codexrm/codexrm/internal/updater"
)

const binaryName = "codexrm-server"
const historyName = "release-history.json"

func unpack(archive, stage string) error {
	z, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer z.Close()
	if len(z.File) > 4096 {
		return errors.New("Server package contains too many files")
	}
	remaining := uint64(updater.MaxPackageSize)
	seen := make(map[string]bool)
	for _, f := range z.File {
		name := strings.TrimSuffix(f.Name, "/")
		if !fs.ValidPath(name) || strings.ContainsAny(name, "\\:\x00") || name == "web/"+historyName ||
			(name != binaryName && name != "web" && !strings.HasPrefix(name, "web/")) || seen[name] {
			return fmt.Errorf("invalid or duplicate Server package entry %q", f.Name)
		}
		seen[name] = true
		dest := filepath.Join(stage, filepath.FromSlash(name))
		if f.Mode().IsDir() && name != binaryName {
			if err := os.MkdirAll(dest, 0755); err != nil {
				return err
			}
			continue
		}
		if !f.Mode().IsRegular() || name == "web" || strings.HasSuffix(f.Name, "/") || f.UncompressedSize64 > remaining {
			return fmt.Errorf("unsupported Server package entry %q", f.Name)
		}
		remaining -= f.UncompressedSize64
		if err := os.MkdirAll(filepath.Dir(dest), 0755); err != nil {
			return err
		}
		mode := fs.FileMode(0644)
		if name == binaryName {
			mode = 0755
		}
		if err := extractFile(f, dest, mode); err != nil {
			return err
		}
	}
	for _, name := range []string{binaryName, "web/index.html", "web/app.js", "web/sw.js"} {
		info, err := os.Lstat(filepath.Join(stage, filepath.FromSlash(name)))
		if err != nil || !info.Mode().IsRegular() || info.Size() == 0 {
			return fmt.Errorf("Server package is missing %s", name)
		}
	}
	return nil
}

func extractFile(entry *zip.File, dest string, mode fs.FileMode) error {
	r, err := entry.Open()
	if err != nil {
		return err
	}
	defer r.Close()
	f, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return err
	}
	n, err := io.Copy(f, io.LimitReader(r, int64(entry.UncompressedSize64)+1))
	if err == nil && uint64(n) != entry.UncompressedSize64 {
		err = fmt.Errorf("wrong uncompressed size for %q", entry.Name)
	}
	return errors.Join(err, f.Close())
}
