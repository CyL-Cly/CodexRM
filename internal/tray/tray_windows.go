//go:build windows

package tray

import (
	"context"
	_ "embed"
	"errors"
	"log"
	"os"
	"path/filepath"
	"sync"

	"github.com/getlantern/systray"
)

//go:embed icon.ico
var iconData []byte

// Run owns the Windows message loop while the connector runs in the background.
// The tray menu is the interactive shutdown path; closing it gracefully cancels
// the connector so its app-server is not left behind.
func Run(ctx context.Context, stateDir string, openSettings func(), run func(context.Context) error) error {
	restoreLog := configureLog(filepath.Join(stateDir, "agent.log"))
	defer restoreLog()

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	runErr := make(chan error, 1)
	done := make(chan struct{})
	var doneOnce sync.Once
	finish := func() {
		doneOnce.Do(func() {
			cancel()
			close(done)
		})
	}

	systray.Run(func() {
		systray.SetIcon(iconData)
		systray.SetTooltip("CodexRM · 右键退出")
		status := systray.AddMenuItem("CodexRM 正在运行", "本地 Codex 连接器")
		status.Disable()
		systray.AddSeparator()
		settings := systray.AddMenuItem("设置", "打开本机 Agent 设置页面")
		quitItem := systray.AddMenuItem("退出 CodexRM", "停止连接器并关闭 Codex app-server")

		go func() {
			for {
				select {
				case <-settings.ClickedCh:
					go openSettings()
				case <-quitItem.ClickedCh:
					status.SetTitle("CodexRM 正在退出…")
					quitItem.Disable()
					cancel()
					return
				case <-done:
					return
				}
			}
		}()
		go func() {
			runErr <- run(runCtx)
			systray.Quit()
		}()
	}, finish)
	finish()

	err := <-runErr
	if err != nil && !errors.Is(err, context.Canceled) {
		log.Printf("connector stopped: %v", err)
	}
	return err
}

func configureLog(path string) func() {
	file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		log.Printf("agent log file disabled: %v", err)
		return func() {}
	}
	previous := log.Writer()
	log.SetOutput(file)
	log.Print("tray connector started")
	return func() {
		log.Print("tray connector stopped")
		log.SetOutput(previous)
		_ = file.Close()
	}
}
