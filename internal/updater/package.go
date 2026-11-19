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
const linuxBinary = "codexrm-agent"

var managedFiles = []string{
	"bin/codexrm-agent.exe",
}

type Plan struct {
	InstallDir      string `json:"installDir"`
	stateDir        string
	linuxExecutable string
	StageDir        string   `json:"stageDir"`
	ParentPID       int      `json:"parentPid"`
	Args            []string `json:"args"`
	WorkingDir      string   `json:"workingDir"`
	Manifest        Manifest `json:"manifest"`
}

func (p *Plan) planPath() string { return filepath.Join(p.StageDir, "plan.json") }
func (p *Plan) Discard() {
	stateDir := p.stateDir
	if stateDir == "" {
		stateDir = filepath.Join(p.InstallDir, ".state")
	}
	if filepath.Dir(p.StageDir) == stateDir && strings.HasPrefix(filepath.Base(p.StageDir), "update-") {
		_ = os.RemoveAll(p.StageDir)
	}
}
func (p *Plan) payload(relative string) string {
	return filepath.Join(p.StageDir, "payload", filepath.FromSlash(relative))
}

func preparePackage(stage, archive string, cfg Config, m Manifest) (*Plan, error) {
	files := managedFiles
	if cfg.LinuxExecutable != "" {
		files = []string{linuxBinary}
	}
	if err := extractPackageFiles(archive, filepath.Join(stage, "payload"), files); err != nil {
		return nil, err
	}
	workingDir, err := os.Getwd()
	if err != nil {
		return nil, err
	}
	plan := &Plan{InstallDir: cfg.InstallDir, StageDir: stage, ParentPID: os.Getpid(), Args: cfg.Args, WorkingDir: workingDir, Manifest: m, stateDir: cfg.StateDir, linuxExecutable: cfg.LinuxExecutable}
	return plan, nil
}

func extractPackage(archive, destination string) error {
	return extractPackageFiles(archive, destination, managedFiles)
}

func extractPackageFiles(archive, destination string, files []string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return fmt.Errorf("open update ZIP: %w", err)
	}
	defer r.Close()
	allowed := make(map[string]bool, len(files))
	for _, file := range files {
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
		if !entry.Mode().IsRegular() {
			return errors.New("update ZIP contains a non-regular executable")
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
	if len(seen) != len(files) {
		return fmt.Errorf("update ZIP is missing required files: %s", strings.Join(files, ", "))
	}
	return nil
}

// VerifyBinary also checks that versionCode describes the actual binary, avoiding repeated updates.
func (p *Plan) VerifyBinary(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	binary := managedFiles[0]
	if p.linuxExecutable != "" {
		binary = linuxBinary
	}
	cmd := exec.CommandContext(ctx, p.payload(binary), "--version")
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
		return errors.New("downloaded client version differs from update manifest")
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
