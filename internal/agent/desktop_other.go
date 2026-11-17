//go:build !windows

package agent

import "errors"

func closeCodexDesktop() (int, error) {
	return 0, errors.New("关闭 Codex 桌面客户端仅支持 Windows 设备")
}
