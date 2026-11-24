package serverupdate

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"time"

	"github.com/codexrm/codexrm/internal/release"
)

// Runner assumes its caller holds the same installation lock as the manual installer.
// Root, its bin and web directories, and the updater executable must be administrator-owned.
type Runner struct {
	Root    string
	Feed    *url.URL
	Client  *http.Client
	Service func(ctx context.Context, action string) error
	Health  func(ctx context.Context) (release.ServerHealth, error)
}

func (r Runner) Run(ctx context.Context) error {
	current, err := r.Health(ctx)
	if err != nil {
		return fmt.Errorf("read installed Server version: %w", err)
	}
	m, downloadURL, err := readFeed(ctx, r.Client, r.Feed)
	if err != nil {
		return fmt.Errorf("check Server update: %w", err)
	}
	if m.VersionCode <= current.VersionCode {
		log.Printf("no newer Server release (installed: %s, code %d)", current.VersionName, current.VersionCode)
		return nil
	}
	stage, err := os.MkdirTemp(r.Root, ".server-update-")
	if err != nil {
		return err
	}
	keep := false
	defer func() {
		if !keep {
			if err := os.RemoveAll(stage); err != nil {
				log.Printf("clean Server update staging: %v", err)
			}
		}
	}()
	archive := filepath.Join(stage, "server.zip")
	if err := download(ctx, r.Client, downloadURL, archive, m); err != nil {
		return fmt.Errorf("download Server update: %w", err)
	}
	if err := unpack(archive, stage); err != nil {
		return fmt.Errorf("unpack Server update: %w", err)
	}
	next := release.ServerHealth{VersionCode: m.VersionCode, VersionName: m.VersionName}
	keep, err = r.activate(ctx, stage, current, next)
	if err != nil {
		return err
	}
	log.Printf("Server updated from %s (%d) to %s (%d)", current.VersionName, current.VersionCode, next.VersionName, next.VersionCode)
	return nil
}

func (r Runner) activate(ctx context.Context, stage string, current, next release.ServerHealth) (keep bool, err error) {
	binary := filepath.Join(r.Root, "bin", binaryName)
	web := filepath.Join(r.Root, "web")
	oldBinary := filepath.Join(stage, "binary.previous")
	oldWeb := filepath.Join(stage, "web.previous")
	if info, err := os.Lstat(binary); err != nil || !info.Mode().IsRegular() {
		return false, errors.New("installed Server binary is not a regular file")
	}
	if info, err := os.Lstat(web); err != nil || !info.IsDir() {
		return false, errors.New("installed web path is not a directory")
	}
	if err := os.Link(binary, oldBinary); err != nil {
		return false, fmt.Errorf("back up Server binary: %w", err)
	}
	// Release history is published separately from the frontend bundle.
	if target, err := os.Readlink(filepath.Join(web, historyName)); err == nil {
		if err := os.Symlink(target, filepath.Join(stage, "web", historyName)); err != nil {
			return false, fmt.Errorf("retain release history link: %w", err)
		}
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	log.Printf("installing Server %s (%d); restarting the relay", next.VersionName, next.VersionCode)
	var binaryChanged, webMoved, webChanged, startAttempted bool
	defer func() {
		if err == nil {
			return
		}
		rollbackCtx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		if startAttempted {
			if stopErr := r.Service(rollbackCtx, "stop"); stopErr != nil {
				log.Printf("stop failed before Server rollback: %v", stopErr)
			}
		}
		var restoreErr error
		if binaryChanged {
			restoreErr = errors.Join(restoreErr, os.Rename(oldBinary, binary))
		}
		if webChanged {
			restoreErr = errors.Join(restoreErr, os.Rename(web, filepath.Join(stage, "web.failed")))
		}
		if webMoved {
			restoreErr = errors.Join(restoreErr, os.Rename(oldWeb, web))
		}
		if restoreErr == nil {
			restoreErr = r.Service(rollbackCtx, "restart")
		}
		if restoreErr == nil {
			restoreErr = r.waitHealth(rollbackCtx, current)
		}
		if restoreErr != nil {
			keep = true
			err = errors.Join(err, fmt.Errorf("Server rollback failed; staging retained at %s: %w", stage, restoreErr))
			return
		}
		err = fmt.Errorf("Server update failed; previous version restored: %w", err)
	}()
	if err := r.Service(ctx, "stop"); err != nil {
		return false, err
	}
	if err := os.Rename(filepath.Join(stage, binaryName), binary); err != nil {
		return false, err
	}
	binaryChanged = true
	if err := os.Rename(web, oldWeb); err != nil {
		return false, err
	}
	webMoved = true
	if err := os.Rename(filepath.Join(stage, "web"), web); err != nil {
		return false, err
	}
	webChanged = true
	startAttempted = true
	if err := r.Service(ctx, "start"); err != nil {
		return false, err
	}
	return false, r.waitHealth(ctx, next)
}

func (r Runner) waitHealth(ctx context.Context, expected release.ServerHealth) error {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	var last error
	for {
		h, err := r.Health(ctx)
		if err == nil {
			if h.VersionCode == expected.VersionCode && h.VersionName == expected.VersionName {
				return nil
			}
			err = fmt.Errorf("expected Server %s (%d), received %s (%d)", expected.VersionName, expected.VersionCode, h.VersionName, h.VersionCode)
		}
		last = err
		select {
		case <-ctx.Done():
			return fmt.Errorf("Server health check failed: %w", last)
		case <-time.After(500 * time.Millisecond):
		}
	}
}
