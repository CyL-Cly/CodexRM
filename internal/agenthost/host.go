package agenthost

import (
	"context"
	"errors"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/agent"
	"github.com/codexrm/codexrm/internal/clientconfig"
	"github.com/codexrm/codexrm/internal/desktop"
	"github.com/codexrm/codexrm/internal/installation"
	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/updater"
)

type Host struct {
	OpenOnStart            bool
	mu                     sync.Mutex
	opMu                   sync.Mutex
	config                 clientconfig.Connection
	path                   string
	base                   agent.Config
	installDir             string
	active                 *agent.Agent
	activeCtx              context.Context
	version, activeVersion uint64
	lastError              string
	changed                chan struct{}
	ctx                    context.Context
	cancel                 context.CancelFunc
	page                   settingsPage
	openBrowser            func(string) error
}

func New(path, installDir string, config clientconfig.Connection, base agent.Config) (*Host, error) {
	if err := config.Normalize(); err != nil {
		return nil, err
	}
	return &Host{path: path, installDir: installDir, config: config, base: base, version: 1, changed: make(chan struct{}, 1), openBrowser: desktop.OpenBrowser}, nil
}

func (h *Host) StateDir() string { return filepath.Dir(h.path) }
func (h *Host) Configured() bool { h.mu.Lock(); defer h.mu.Unlock(); return h.config.Server != "" }

func (h *Host) OpenSettings() {
	url, err := h.SettingsURL()
	if err == nil {
		err = h.openBrowser(url)
	}
	if err != nil {
		log.Printf("打开设置页面: %v", err)
	}
}

func (h *Host) Run(ctx context.Context) error {
	h.mu.Lock()
	h.ctx, h.cancel = context.WithCancel(ctx)
	runCtx := h.ctx
	h.mu.Unlock()
	defer h.cancel()
	defer h.closePage()
	if h.OpenOnStart {
		h.OpenSettings()
	}
	// A configured server may be offline, and a new installation may have no
	// Codex yet. Both must leave the tray and settings usable after an update.
	updater.SignalReady()
	for runCtx.Err() == nil {
		h.mu.Lock()
		cfg, version := h.config, h.version
		h.mu.Unlock()
		generationCtx, cancel := context.WithCancel(runCtx)
		finished := make(chan struct{})
		go func() { defer close(finished); h.runConnection(generationCtx, cfg, version) }()
		updates := make(chan *updater.Plan)
		updateDone := make(chan struct{})
		go func() {
			defer close(updateDone)
			if h.installDir == "" || installation.IsPackaged() || h.base.Mock || !cfg.AutoUpdate || runtime.GOOS != "windows" {
				return
			}
			u, err := updater.New(updater.Config{ServerURL: cfg.UpdateServer, ManifestURL: cfg.ManifestURL(), InstallDir: h.installDir, VersionCode: release.VersionCode, Args: []string{"--config", h.path}})
			if err != nil {
				log.Printf("automatic update: %v", err)
				return
			}
			u.Run(generationCtx, updates)
		}()
		select {
		case <-runCtx.Done():
		case <-h.changed:
		case plan := <-updates:
			h.opMu.Lock()
			h.mu.Lock()
			current := h.version == version && runCtx.Err() == nil
			h.mu.Unlock()
			if !current {
				plan.Discard()
			} else if err := plan.StartHelper(); err != nil {
				plan.Discard()
				log.Printf("automatic update postponed: %v", err)
			} else {
				log.Printf("applying update %s", plan.Manifest.VersionName)
				h.cancel()
			}
			h.opMu.Unlock()
		}
		cancel()
		<-finished
		<-updateDone
	}
	return nil
}

func (h *Host) runConnection(ctx context.Context, cfg clientconfig.Connection, version uint64) {
	if cfg.Server == "" {
		return
	}
	base := h.base
	base.ServerURL, base.EnrollmentKey = cfg.Server, cfg.EnrollmentKey
	base.NoAutoPair = true
	if cfg.Workspace != "" {
		base.Cwd = cfg.Workspace
	}
	if cfg.CodexPath != "" {
		base.CodexPath = cfg.CodexPath
		if filepath.IsAbs(base.CodexPath) {
			if _, err := os.Stat(base.CodexPath); errors.Is(err, os.ErrNotExist) {
				base.CodexPath = ""
			}
		}
	}
	a, err := agent.New(base)
	if err != nil {
		h.setError(version, err)
		return
	}
	h.mu.Lock()
	if h.version != version {
		h.mu.Unlock()
		return
	}
	h.active, h.activeCtx, h.activeVersion, h.lastError = a, ctx, version, ""
	h.mu.Unlock()
	for ctx.Err() == nil {
		err = a.Run(ctx)
		if ctx.Err() != nil {
			break
		}
		h.setError(version, err)
		select {
		case <-ctx.Done():
		case <-time.After(3 * time.Second):
		}
	}
}

func (h *Host) setError(version uint64, err error) {
	if err == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.version == version {
		h.lastError = err.Error()
	}
	log.Printf("agent connection: %v", err)
}

func (h *Host) current(ctx context.Context) (*agent.Agent, context.Context, func(), error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.active == nil || h.activeVersion != h.version || h.activeCtx.Err() != nil {
		return nil, nil, nil, errors.New("请先保存远程服务器和连接授权密钥，稍后重试")
	}
	requestCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	stop := context.AfterFunc(h.activeCtx, cancel)
	return h.active, requestCtx, func() { stop(); cancel() }, nil
}
