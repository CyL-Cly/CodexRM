package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/codexrm/codexrm/internal/agent"
	"github.com/codexrm/codexrm/internal/agenthost"
	"github.com/codexrm/codexrm/internal/clientconfig"
	"github.com/codexrm/codexrm/internal/desktop"
	"github.com/codexrm/codexrm/internal/installation"
	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/tray"
	"github.com/codexrm/codexrm/internal/updater"
)

func defaultConfigPath() string {
	if path := installation.PackageConfigPath(); path != "" {
		return path
	}
	executable, _ := os.Executable()
	if filepath.Base(filepath.Dir(executable)) == "bin" {
		return filepath.Join(filepath.Dir(filepath.Dir(executable)), ".state", "connection.json")
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		dir, _ = os.UserHomeDir()
	}
	return filepath.Join(dir, "CodexRM", ".state", "connection.json")
}

func fatal(err any) {
	log.Print(err)
	desktop.ShowStartupError(fmt.Sprint(err))
	if err := updater.RollbackStartup(); err != nil {
		log.Print(err)
	}
	os.Exit(1)
}

func main() {
	configPath := flag.String("config", defaultConfigPath(), "local connection settings JSON")
	serverURL := flag.String("server", "", "CodexRM server URL (overrides saved settings)")
	appServer := flag.String("appserver", "", "local Codex app-server websocket URL")
	name := flag.String("name", "", "display name for this host")
	dataDir := flag.String("data", "", "root directory for per-server agent credentials")
	wxLocalRelay := flag.String("wx-local-relay", "", "optional same-host relay for local WeChat robots")
	mock := flag.Bool("mock", false, "start an in-process mock app-server")
	mockAddr := flag.String("mock-addr", "127.0.0.1:0", "mock app-server listen address")
	doPair := flag.Bool("pair", false, "create a pairing code and exit")
	listDevices := flag.Bool("list-devices", false, "list authorized browsers as JSON and exit")
	removeDevice := flag.String("remove-device", "", "revoke a browser by client ID and exit")
	codexPath := flag.String("codex", "", "Codex CLI executable (default: desktop installation, then PATH)")
	cwd := flag.String("cwd", "", "initial workspace directory")
	ccswitchDB := flag.String("ccswitch-db", os.Getenv("CODEXRM_CCSWITCH_DB"), "CCSwitch database path")
	enrollKey := flag.String("enroll-key", os.Getenv("CODEXRM_ENROLL_KEY"), "server enrollment key")
	appToken := flag.String("appserver-token", os.Getenv("CODEXRM_APPSERVER_TOKEN"), "app-server WebSocket bearer token")
	updateDir := flag.String("update-dir", "", "managed Windows installation directory")
	// Accept the legacy flag for old launch scripts; installed agents always
	// use the built-in update service.
	flag.String("update-manifest", "", "deprecated: update service is built in")
	checkUpdate := flag.Bool("check-update", false, "check for updates, print JSON, and exit")
	installUpdate := flag.Bool("install-update", false, "update a stopped Windows installation from a temporary copy")
	currentVersion := flag.Int64("current-version-code", release.VersionCode, "installed release code")
	applyUpdate := flag.String("apply-update", "", "internal: apply a prepared update plan")
	version := flag.Bool("version", false, "print release version as JSON and exit")
	installationInfo := flag.Bool("installation-info", false, "print installation identity and configuration path as JSON and exit")
	setupAction := flag.String("setup-action", "", "internal: perform an installer action")
	setupDir := flag.String("setup-dir", "", "internal: installer target directory")
	setupAutostart := flag.Bool("setup-autostart", false, "internal: record the setup startup preference")
	restart := flag.Bool("restart", false, "stop the existing agent and restart the service")
	settings := flag.Bool("settings", false, "open the local settings page (Windows/macOS)")
	loopbackSettings := flag.Bool("loopback-settings", false, "listen on loopback with a persistent settings password and port for SSH forwarding")
	lanSettings := flag.Bool("lan-settings", false, "allow LAN access to the settings page (trusted networks only)")
	uninstall := flag.Bool("uninstall", false, "uninstall this Windows installation")
	uninstallDir := flag.String("uninstall-dir", "", "internal: uninstall using a temporary helper")
	wxInstallHelper := flag.Bool("wx-install-helper", false, "install signed Docker Engine and Compose packages on Linux")
	flag.Parse()
	if *loopbackSettings && *lanSettings {
		log.Fatal("--loopback-settings and --lan-settings are mutually exclusive")
	}
	if *wxInstallHelper {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
		defer cancel()
		if err := agenthost.InstallWXDocker(ctx); err != nil {
			log.Fatal(err)
		}
		return
	}
	if *version {
		_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"versionCode": release.VersionCode, "versionName": release.VersionName})
		return
	}
	if *installationInfo {
		executable, _ := os.Executable()
		_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"packageFamilyName": installation.PackageFamilyName(), "configPath": *configPath, "executable": executable})
		return
	}
	if installation.IsPackaged() && (*applyUpdate != "" || *installUpdate || *updateDir != "" || *uninstallDir != "" || *setupAction != "") {
		fatal("MSIX 版本由 Windows 管理安装和更新，请使用对应的 MSIX 或 App Installer 文件")
	}
	if *setupAction != "" {
		if err := installation.SetupAction(*setupAction, *setupDir, *setupAutostart); err != nil {
			// Inno captures this output and displays it in the setup wizard.
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if *applyUpdate != "" {
		if err := updater.Apply(*applyUpdate); err != nil {
			fatal(err)
		}
		return
	}
	if *uninstallDir != "" {
		if err := installation.Uninstall(context.Background(), *uninstallDir); err != nil {
			fatal(err)
		}
		return
	}
	*configPath, _ = filepath.Abs(*configPath)
	restoreLog := tray.ConfigureLog(filepath.Join(filepath.Dir(*configPath), "agent.log"))
	defer restoreLog()
	root := filepath.Dir(filepath.Dir(*configPath))
	if *uninstall {
		if installation.IsPackaged() || installation.UsesSetup(root) {
			if err := desktop.OpenBrowser("ms-settings:appsfeatures"); err != nil {
				fatal(err)
			}
			return
		}
		if err := installation.BeginUninstall(root); err != nil {
			fatal(err)
		}
		return
	}
	cfg, err := clientconfig.Load(*configPath)
	if err != nil {
		fatal(err)
	}
	if *serverURL != "" {
		normalized, err := clientconfig.ServerURL(*serverURL)
		if err != nil {
			fatal(err)
		}
		if normalized != cfg.Server {
			cfg.EnrollmentKey = ""
		}
		cfg.Server = normalized
	}
	if *cwd != "" {
		cfg.Workspace = *cwd
	}
	if *codexPath != "" {
		cfg.CodexPath = *codexPath
	}
	if err := cfg.Normalize(); err != nil {
		fatal(err)
	}
	// The public relay uses keyless enrollment. Keep private relay keys local.
	if *enrollKey != "" && cfg.Server != clientconfig.DefaultRelayServer {
		cfg.EnrollmentKey = strings.TrimSpace(*enrollKey)
	}
	if cfg.Workspace == "" {
		cfg.Workspace, _ = os.UserHomeDir()
	}
	if *checkUpdate || *installUpdate {
		if *updateDir == "" && (runtime.GOOS != "linux" || *installUpdate) {
			fatal("--update-dir is required")
		}
		manifest := cfg.ManifestURL()
		if runtime.GOOS == "linux" {
			if runtime.GOARCH != "amd64" {
				fatal("the Linux Agent update feed is for amd64")
			}
			manifest = clientconfig.LinuxUpdateManifest
		}
		u, err := updater.New(updater.Config{ServerURL: cfg.UpdateServer, ManifestURL: manifest, InstallDir: *updateDir, VersionCode: *currentVersion})
		if err != nil {
			fatal(err)
		}
		if *installUpdate {
			if runtime.GOOS != "windows" {
				fatal("manual installation is only supported on Windows")
			}
			plan, err := u.Prepare(context.Background())
			if err != nil {
				fatal(err)
			}
			if plan != nil {
				if err := plan.InstallStopped(context.Background()); err != nil {
					fatal(err)
				}
				plan.Discard()
				_ = json.NewEncoder(os.Stdout).Encode(plan.Manifest)
			}
		} else {
			m, err := u.Inspect(context.Background())
			if err != nil {
				fatal(err)
			}
			_ = json.NewEncoder(os.Stdout).Encode(map[string]any{"updateAvailable": m != nil && m.VersionCode > *currentVersion, "manifest": m})
		}
		return
	}
	base := agent.Config{ServerURL: cfg.Server, EnrollmentKey: cfg.EnrollmentKey, AppServerURL: *appServer, AppServerToken: *appToken, Name: *name, DataDir: *dataDir, Mock: *mock, MockAddr: *mockAddr, CodexPath: cfg.CodexPath, Cwd: cfg.Workspace, CCSwitchDB: *ccswitchDB}
	if *doPair || *listDevices || *removeDevice != "" {
		if *listDevices && *removeDevice != "" {
			fatal("--list-devices and --remove-device cannot be used together")
		}
		a, err := agent.NewForServer(base)
		if err != nil {
			fatal(err)
		}
		if *listDevices {
			devices, err := a.ListDevices(context.Background())
			if err != nil {
				fatal(err)
			}
			_ = json.NewEncoder(os.Stdout).Encode(devices)
		} else if *removeDevice != "" {
			if err := a.RemoveDevice(context.Background(), *removeDevice); err != nil {
				fatal(err)
			}
			fmt.Println("Device authorization removed.")
		} else {
			pair, err := a.Pair(context.Background())
			if err != nil {
				fatal(err)
			}
			instructions, err := a.PairingInstructions(pair)
			if err != nil {
				fatal(err)
			}
			fmt.Println(instructions)
		}
		return
	}
	instance, err := desktop.Acquire(filepath.Dir(*configPath), *restart)
	if err != nil {
		fatal(err)
	}
	if instance == nil {
		return
	}
	defer instance.Close()
	if err := desktop.EnsureP2PFirewall(); err != nil {
		if err == desktop.ErrP2PFirewallRequired {
			return
		}
		fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(*configPath), 0700); err != nil {
		fatal(err)
	}
	installDir := *updateDir
	if installDir == "" && !installation.IsPackaged() {
		if _, err := os.Stat(filepath.Join(root, ".state", "installation.json")); err == nil {
			installDir = root
		}
		if installDir == "" && filepath.Clean(installation.ResolveDir()) == root {
			installDir = root
		}
	}
	if installDir != "" {
		if err := installation.MigrateLegacy(installDir); err != nil {
			log.Printf("迁移原生启动入口: %v", err)
		}
	}
	host, err := agenthost.New(*configPath, installDir, cfg, base)
	if err != nil {
		fatal(err)
	}
	if runtime.GOOS == "linux" && *wxLocalRelay != "" {
		if err := host.SetWXLocalRelay(*wxLocalRelay); err != nil {
			fatal(err)
		}
	}
	host.LANSettings = *lanSettings
	host.LoopbackSettings = *loopbackSettings
	host.LinuxUpdateExecutable = updater.LinuxExecutable()
	host.OpenOnStart = runtime.GOOS != "linux" && (*settings || !host.Configured())
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := tray.Run(ctx, host.StateDir(), host.OpenSettings, func() string {
		switch host.Status().State {
		case agent.Connected:
			return "CodexRM · 已连接"
		case agent.Retrying:
			return "CodexRM · 连接中断，等待重试"
		case agent.Degraded:
			return "CodexRM · 功能受限"
		case agent.Connecting:
			return "CodexRM · 正在连接"
		default:
			return "CodexRM · 未配置服务器"
		}
	}, func(runCtx context.Context) error {
		watchCtx, stopWatch := context.WithCancel(runCtx)
		watchDone := make(chan struct{})
		go func() { defer close(watchDone); instance.Watch(watchCtx, host.OpenSettings, cancel) }()
		err := host.Run(runCtx)
		stopWatch()
		<-watchDone
		return err
	}); err != nil && ctx.Err() == nil {
		fatal(err)
	}
	if err := host.RestartUpdate(ctx); err != nil {
		fatal(err)
	}
}
