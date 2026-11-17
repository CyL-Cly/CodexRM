package agent

import (
	"fmt"

	"github.com/codexrm/codexrm/internal/appserver"
)

func (a *Agent) desktopRPC(method string) (any, error) {
	if method != "codexrm/desktop/close" {
		return nil, &appserver.RPCError{Code: -32601, Message: "Unknown CodexRM method"}
	}
	count, err := closeCodexDesktop()
	if err != nil {
		return nil, err
	}
	return map[string]any{"closed": count, "message": fmt.Sprintf("已关闭 %d 个 Codex 桌面进程", count)}, nil
}
