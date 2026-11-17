package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"

	"github.com/codexrm/codexrm/internal/installation"
	"github.com/codexrm/codexrm/internal/updater"
)

// Release builds may override the public update feed. It is independent of
// the remote connection configured later in the agent's settings page.
var defaultManifestURL = "https://codexrm.dagongren.tech/update.json"

type options struct {
	manifestURL, installDir, workspace, codexPath                    string
	installDirSet, nonInteractive, noShortcuts, noAutostart, noStart bool
}

func main() {
	opts, err := parseOptions(os.Args[1:], os.Stderr)
	if err != nil {
		if !errors.Is(err, flag.ErrHelp) {
			fmt.Fprintf(os.Stderr, "\n安装失败: %v\n", err)
		}
		finish(errors.Is(err, flag.ErrHelp), hasArgument(os.Args[1:], "--non-interactive"))
		return
	}
	fmt.Println("CodexRM 原生安装器")
	fmt.Println("安装后请右键托盘图标打开设置，填写远程服务器和连接授权密钥。")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	err = install(ctx, opts, os.Stdin, os.Stdout, os.Stderr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "\n安装失败: %v\n", err)
	} else {
		fmt.Println("\nCodexRM 安装完成。")
	}
	finish(err == nil, opts.nonInteractive)
}

func finish(success, nonInteractive bool) {
	if !nonInteractive {
		fmt.Print("\n按回车键关闭窗口...")
		_, _ = fmt.Fscanln(os.Stdin)
	}
	if !success {
		os.Exit(1)
	}
}

func hasArgument(args []string, wanted string) bool {
	for _, arg := range args {
		if arg == wanted || arg == strings.TrimPrefix(wanted, "-") {
			return true
		}
	}
	return false
}

func hasOption(args []string, name string) bool {
	for _, arg := range args {
		for _, prefix := range []string{"-" + name, "--" + name} {
			if arg == prefix || strings.HasPrefix(arg, prefix+"=") {
				return true
			}
		}
	}
	return false
}

func parseOptions(args []string, output io.Writer) (options, error) {
	var opts options
	manifestDefault := os.Getenv("CODEXRM_UPDATE_MANIFEST")
	if manifestDefault == "" {
		manifestDefault = defaultManifestURL
	}
	flags := flag.NewFlagSet("codexrm-setup", flag.ContinueOnError)
	flags.SetOutput(output)
	flags.StringVar(&opts.manifestURL, "manifest", manifestDefault, "公开 update.json 地址")
	flags.StringVar(&opts.installDir, "install-dir", installation.ResolveDir(), "安装目录")
	flags.StringVar(&opts.workspace, "workspace", "", "初始工作区")
	flags.StringVar(&opts.codexPath, "codex-path", "", "Codex CLI 路径，可留空自动查找")
	flags.BoolVar(&opts.nonInteractive, "non-interactive", false, "使用默认值，无需交互")
	flags.BoolVar(&opts.noShortcuts, "no-shortcuts", false, "不创建桌面快捷方式")
	flags.BoolVar(&opts.noAutostart, "no-autostart", false, "不启用登录自启动")
	flags.BoolVar(&opts.noStart, "no-start", false, "安装后暂不启动")
	if err := flags.Parse(args); err != nil {
		return opts, err
	}
	if flags.NArg() != 0 {
		return opts, fmt.Errorf("无法识别位置参数: %s", strings.Join(flags.Args(), " "))
	}
	opts.manifestURL = strings.TrimSpace(opts.manifestURL)
	opts.installDirSet = hasOption(args, "install-dir")
	return opts, nil
}

func install(ctx context.Context, opts options, stdin io.Reader, stdout, _ io.Writer) error {
	if runtime.GOOS != "windows" {
		return errors.New("安装器仅支持 Windows")
	}
	if !opts.nonInteractive && !opts.installDirSet {
		reader := bufio.NewReader(stdin)
		fmt.Fprintf(stdout, "安装目录 [%s]（回车使用默认值）: ", opts.installDir)
		line, err := reader.ReadString('\n')
		if err != nil && line == "" {
			return fmt.Errorf("读取安装目录: %w", err)
		}
		if custom := strings.TrimSpace(line); custom != "" {
			opts.installDir = custom
		}
	}
	if opts.installDir == "" {
		return errors.New("无法确定安装目录，请传入 --install-dir")
	}
	installDir, err := filepath.Abs(opts.installDir)
	if err != nil {
		return err
	}
	installOptions := installation.Options{Workspace: opts.workspace, CodexPath: opts.codexPath, NoShortcuts: opts.noShortcuts, NoAutostart: opts.noAutostart, NoStart: opts.noStart}
	// An extracted ZIP contains this same installer beside bin/. No script host
	// or network is required to install that package.
	self, _ := os.Executable()
	localBinary := filepath.Join(filepath.Dir(self), "bin", "codexrm-agent.exe")
	if info, err := os.Stat(localBinary); err == nil && info.Mode().IsRegular() {
		fmt.Fprintln(stdout, "正在安装离线软件包...")
		return installation.Install(ctx, localBinary, installDir, installOptions)
	}
	manifestURL, err := validateManifestURL(opts.manifestURL)
	if err != nil {
		return err
	}
	installOptions.ManifestURL = manifestURL
	u, _ := url.Parse(manifestURL)
	client, err := updater.New(updater.Config{ServerURL: (&url.URL{Scheme: u.Scheme, Host: u.Host}).String(), ManifestURL: manifestURL, InstallDir: installDir, VersionCode: 0})
	if err != nil {
		return err
	}
	display := *u
	display.RawQuery, display.ForceQuery = "", false
	fmt.Fprintf(stdout, "更新源: %s\n正在读取更新清单...\n", display.String())
	plan, err := client.Prepare(ctx)
	if err != nil {
		return fmt.Errorf("获取安装包: %w", err)
	}
	if plan == nil {
		return errors.New("更新源尚未发布可安装的软件包")
	}
	defer plan.Discard()
	fmt.Fprintf(stdout, "软件版本: %s (code %d)\n", plan.Manifest.VersionName, plan.Manifest.VersionCode)
	if notes := strings.TrimSpace(plan.Manifest.ReleaseNotes); notes != "" {
		fmt.Fprintf(stdout, "更新说明: %s\n", notes)
	}
	fmt.Fprintln(stdout, "安装包已通过大小与 SHA-256 校验，正在验证客户端版本...")
	if err := plan.VerifyBinary(ctx); err != nil {
		return err
	}
	return installation.Install(ctx, filepath.Join(plan.StageDir, "payload", "bin", "codexrm-agent.exe"), installDir, installOptions)
}

func validateManifestURL(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.Fragment != "" {
		return "", errors.New("更新源必须是完整的 HTTP(S) update.json 地址，且不能带账号或片段")
	}
	return u.String(), nil
}
