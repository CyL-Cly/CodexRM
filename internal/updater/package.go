package updater

import (
	"archive/zip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strings"
	"time"
)

// Packages cannot replace local connection settings, credentials or startup preferences.
var managedFiles = []string{
	"bin/codexrm-agent.exe",
}

type Plan struct {
	InstallDir string   `json:"installDir"`
	StageDir   string   `json:"stageDir"`
	ParentPID  int      `json:"parentPid"`
	Args       []string `json:"args"`
	WorkingDir string   `json:"workingDir"`
	Manifest   Manifest `json:"manifest"`
}

func (p *Plan) planPath() string { return filepath.Join(p.StageDir, "plan.json") }
func (p *Plan) Discard() {
	if filepath.Dir(p.StageDir) == filepath.Join(p.InstallDir, ".state") && strings.HasPrefix(filepath.Base(p.StageDir), "update-") {
		_ = os.RemoveAll(p.StageDir)
	}
}
func (p *Plan) payload(relative string) string {
	return filepath.Join(p.StageDir, "payload", filepath.FromSlash(relative))
}

func preparePackage(stage, archive string, cfg Config, m Manifest) (*Plan, error) {
	if err := extractPackage(archive, filepath.Join(stage, "payload")); err != nil {
		return nil, err
	}
	workingDir, err := os.Getwd()
	if err != nil {
		return nil, err
	}
	plan := &Plan{InstallDir: cfg.InstallDir, StageDir: stage, ParentPID: os.Getpid(), Args: cfg.Args, WorkingDir: workingDir, Manifest: m}
	return plan, nil
}

func extractPackage(archive, destination string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return fmt.Errorf("open update ZIP: %w", err)
	}
	defer r.Close()
	allowed := make(map[string]bool, len(managedFiles))
	for _, file := range managedFiles {
		allowed[file] = true
	}
	seen := map[string]bool{}
	var total uint64
	for _, entry := range r.File {
		name := strings.ReplaceAll(entry.Name, "\\", "/")
		clean := path.Clean(name)
		if strings.HasPrefix(clean, "/") || clean == ".." || strings.HasPrefix(clean, "../") || strings.Contains(clean, ":") || entry.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe path in update ZIP")
		}
		for _, part := range strings.Split(name, "/") {
			if part == ".." {
				return errors.New("path traversal in update ZIP")
			}
		}
		clean = strings.TrimPrefix(clean, "CodexRM/")
		if !allowed[clean] || entry.FileInfo().IsDir() {
			continue
		}
		if seen[clean] {
			return errors.New("duplicate file in update ZIP")
		}
		seen[clean] = true
		total += entry.UncompressedSize64
		if entry.UncompressedSize64 > uint64(MaxPackageSize) || total > uint64(MaxPackageSize) {
			return errors.New("unpacked update exceeds 512 MiB")
		}
		target := filepath.Join(destination, filepath.FromSlash(clean))
		if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
			return err
		}
		source, err := entry.Open()
		if err != nil {
			return err
		}
		f, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0700)
		if err != nil {
			_ = source.Close()
			return err
		}
		n, copyErr := io.Copy(f, io.LimitReader(source, int64(entry.UncompressedSize64)+1))
		_ = source.Close()
		closeErr := f.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if n != int64(entry.UncompressedSize64) {
			return errors.New("unpacked update size mismatch")
		}
	}
	if len(seen) != len(managedFiles) {
		return errors.New("update ZIP is missing required Windows client files")
	}
	return nil
}

// VerifyBinary also checks that versionCode describes the actual binary, avoiding repeated updates.
func (p *Plan) VerifyBinary(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, p.payload("bin/codexrm-agent.exe"), "--version")
	hideProcess(cmd)
	output, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("check downloaded client version: %w", err)
	}
	var version struct {
		VersionCode int64  `json:"versionCode"`
		VersionName string `json:"versionName"`
	}
	if json.Unmarshal(output, &version) != nil || version.VersionCode != p.Manifest.VersionCode || version.VersionName != p.Manifest.VersionName {
		return errors.New("downloaded client version differs from update.json")
	}
	return nil
}

func copyFile(source, target string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(target, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0700)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(out, in)
	closeErr := out.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}
