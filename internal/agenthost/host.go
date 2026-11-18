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
	startUpdate            func(*updater.Plan) error
	newUpdater             func(updater.Config) (*updater.Updater, error)
	pendingUpdate          *updater.Plan
	updateRetryAt          time.Time
	updateWake             chan struct{}
}

func New(path, installDir string, config clientconfig.Connection, base agent.Config) (*Host, error) {
	if err := config.Normalize(); err != nil {
		return nil, err
	}
	return &Host{path: path, installDir: installDir, config: config, base: base, version: 1, changed: make(chan struct{}, 1), updateWake: make(chan struct{}, 1), openBrowser: desktop.OpenBrowser, newUpdater: updater.New, startUpdate: func(plan *updater.Plan) error { return plan.StartHelper() }}, nil
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
	updates := make(chan *updater.Plan)
	updateDone := make(chan struct{})
	go func() { defer close(updateDone); h.runUpdates(runCtx, updates) }()
	defer func() {
		h.cancel()
		<-updateDone
		h.opMu.Lock()
		h.mu.Lock()
		if h.pendingUpdate != nil {
			h.pendingUpdate.Discard()
			h.pendingUpdate = nil
		}
		h.mu.Unlock()
		h.opMu.Unlock()
	}()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for runCtx.Err() == nil {
		h.mu.Lock()
		cfg, version := h.config, h.version
		h.mu.Unlock()
		generationCtx, cancel := context.WithCancel(runCtx)
		finished := make(chan struct{})
		go func() { defer close(finished); h.runConnection(generationCtx, cfg, version) }()
		reconfigure := false
		for !reconfigure && runCtx.Err() == nil {
			select {
			case <-runCtx.Done():
			case <-h.changed:
				reconfigure = true
			case plan := <-updates:
				h.opMu.Lock()
				h.queueUpdate(plan)
				h.opMu.Unlock()
			case <-h.updateWake:
			case <-ticker.C:
			}
			if !reconfigure && runCtx.Err() == nil {
				h.opMu.Lock()
				err := h.applyPendingUpdate()
				h.opMu.Unlock()
				if err != nil {
					log.Printf("update postponed: %v", err)
				}
			}
		}
		cancel()
		<-finished
	}
	return nil
}

func (h *Host) updaterConfig() updater.Config {
	return updater.Config{ServerURL: clientconfig.DefaultUpdateServer, ManifestURL: clientconfig.DefaultUpdateServer + "update.json", InstallDir: h.installDir, VersionCode: release.VersionCode, Args: []string{"--config", h.path}}
}

func (h *Host) runUpdates(ctx context.Context, ready chan<- *updater.Plan) {
	h.mu.Lock()
	autoUpdate := h.config.AutoUpdate
	h.mu.Unlock()
	if h.installDir == "" || installation.IsPackaged() || h.base.Mock || !autoUpdate || runtime.GOOS != "windows" {
		return
	}
	u, err := h.newUpdater(h.updaterConfig())
	if err != nil {
		log.Printf("automatic update: %v", err)
		return
	}
	u.Run(ctx, ready)
}

// queueUpdate and applyPendingUpdate are serialized with settings and manual
// checks by opMu. The pending package survives relay and settings reconnections.
func (h *Host) queueUpdate(plan *updater.Plan) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.ctx.Err() != nil || h.pendingUpdate != nil {
		plan.Discard()
		return
	}
	h.pendingUpdate = plan
	log.Printf("update %s ready; waiting for conversations to finish", plan.Manifest.VersionName)
}

func (h *Host) applyPendingUpdate() error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.pendingUpdate == nil || h.ctx.Err() != nil || time.Now().Before(h.updateRetryAt) {
		return nil
	}
	plan := h.pendingUpdate
	start := func() error {
		if err := h.startUpdate(plan); err != nil {
			h.updateRetryAt = time.Now().Add(time.Minute)
			return err
		}
		h.pendingUpdate = nil // The helper now owns the staged files.
		log.Printf("applying update %s", plan.Manifest.VersionName)
		h.cancel()
		return nil
	}
	if h.active != nil && h.activeCtx.Err() == nil {
		_, err := h.active.TryStartUpdate(start)
		return err
	}
	return start()
}

// Manual checks use the same idle gate as automatic updates and return while a
// conversation is still running, leaving the verified package pending.
func (h *Host) checkForUpdate() (bool, error) {
	return h.checkForUpdateMode(true)
}

func (h *Host) checkRemoteUpdate() (agent.UpdateResult, error) {
	available, err := h.checkForUpdateMode(false)
	return agent.UpdateResult{UpdateAvailable: available, UpdatePending: available}, err
}

func (h *Host) checkForUpdateMode(applyImmediately bool) (bool, error) {
	h.opMu.Lock()
	defer h.opMu.Unlock()
	h.mu.Lock()
	ctx := h.ctx
	pending := h.pendingUpdate != nil
	running := ctx != nil && ctx.Err() == nil
	h.mu.Unlock()
	if !running {
		return false, errors.New("Agent 正在退出")
	}
	if h.installDir == "" || installation.IsPackaged() || runtime.GOOS != "windows" {
		return false, errors.New("此版本由 Windows 管理更新，或未使用受管理的安装目录")
	}
	if pending {
		return true, nil
	}
	u, err := h.newUpdater(h.updaterConfig())
	if err != nil {
		return false, err
	}
	plan, err := u.Prepare(ctx)
	if err != nil || plan == nil {
		return false, err
	}
	h.queueUpdate(plan)
	if applyImmediately {
		if err = h.applyPendingUpdate(); err != nil {
			return true, err
		}
		select {
		case h.updateWake <- struct{}{}:
		default:
		}
	}
	return true, nil
}

func (h *Host) runConnection(ctx context.Context, cfg clientconfig.Connection, version uint64) {
	if cfg.Server == "" {
		return
	}
	base := h.base
	base.ServerURL, base.EnrollmentKey = cfg.Server, cfg.EnrollmentKey
	base.CheckForUpdate = h.checkRemoteUpdate
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
	if h.version != version || ctx.Err() != nil {
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
