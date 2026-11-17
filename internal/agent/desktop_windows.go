package agent

import (
	"errors"
	"fmt"
	"sort"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

type processNode struct {
	PID    uint32
	Parent uint32
	Name   string
	Exe    string
}

// The desktop client is an Electron app whose main process is ChatGPT.exe inside
// the OpenAI.Codex package; it spawns resources\codex.exe app-servers. The RM
// agent starts its own app-server from the same CLI path, so selection must follow
// the desktop process tree instead of matching every codex.exe by name.
func isCodexDesktopProcess(node processNode) bool {
	name := strings.ToLower(node.Name)
	if name != "chatgpt.exe" && name != "codex.exe" {
		return false
	}
	if strings.HasSuffix(strings.ToLower(node.Exe), `\resources\codex.exe`) {
		return false
	}
	exe := strings.ToLower(node.Exe)
	return strings.Contains(exe, `\openai.codex_`) ||
		strings.Contains(exe, `\programs\codex\`) ||
		strings.Contains(exe, `\codex\app\`)
}

func isCodexDesktopChild(node processNode) bool {
	switch strings.ToLower(node.Name) {
	case "codex.exe", "chatgpt.exe", "codex-code-mode-host.exe", "codex-computer-use-swift.exe":
		return true
	}
	return false
}

func selectCodexDesktopPIDs(nodes []processNode) []uint32 {
	parent := map[uint32]uint32{}
	for _, node := range nodes {
		parent[node.PID] = node.Parent
	}
	roots := map[uint32]bool{}
	for _, node := range nodes {
		if isCodexDesktopProcess(node) {
			roots[node.PID] = true
		}
	}
	children := []processNode{}
	for _, node := range nodes {
		if roots[node.PID] || !isCodexDesktopChild(node) {
			continue
		}
		for pid := node.Parent; pid != 0; pid = parent[pid] {
			if roots[pid] {
				children = append(children, node)
				break
			}
		}
	}
	depth := func(node processNode) int {
		levels := 0
		for pid := node.Parent; pid != 0; pid = parent[pid] {
			levels++
		}
		return levels
	}
	// Children first: the desktop UI closes cleanly once its app-servers are gone.
	sort.SliceStable(children, func(i, j int) bool { return depth(children[i]) > depth(children[j]) })
	order := make([]uint32, 0, len(children)+len(roots))
	for _, node := range children {
		order = append(order, node.PID)
	}
	for _, node := range nodes {
		if roots[node.PID] {
			order = append(order, node.PID)
		}
	}
	return order
}

func runningCodexProcesses() ([]processNode, error) {
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil, fmt.Errorf("枚举 Codex 桌面进程失败: %w", err)
	}
	defer windows.CloseHandle(snapshot)
	nodes := []processNode{}
	entry := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	for err = windows.Process32First(snapshot, &entry); err == nil; err = windows.Process32Next(snapshot, &entry) {
		node := processNode{PID: entry.ProcessID, Parent: entry.ParentProcessID, Name: windows.UTF16ToString(entry.ExeFile[:])}
		if !isCodexDesktopChild(node) {
			continue
		}
		if process, openErr := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, node.PID); openErr == nil {
			buffer := make([]uint16, windows.MAX_LONG_PATH)
			size := uint32(len(buffer))
			if windows.QueryFullProcessImageName(process, 0, &buffer[0], &size) == nil {
				node.Exe = windows.UTF16ToString(buffer[:size])
			}
			windows.CloseHandle(process)
		}
		nodes = append(nodes, node)
	}
	if err != nil && !errors.Is(err, windows.ERROR_NO_MORE_FILES) {
		return nil, fmt.Errorf("枚举 Codex 桌面进程失败: %w", err)
	}
	return nodes, nil
}

func closeCodexDesktop() (int, error) {
	nodes, err := runningCodexProcesses()
	if err != nil {
		return 0, err
	}
	closed := 0
	var firstErr error
	for _, pid := range selectCodexDesktopPIDs(nodes) {
		process, openErr := windows.OpenProcess(windows.PROCESS_TERMINATE, false, pid)
		if openErr != nil {
			if !errors.Is(openErr, windows.ERROR_INVALID_PARAMETER) && firstErr == nil {
				firstErr = openErr
			}
			continue
		}
		terminateErr := windows.TerminateProcess(process, 1)
		windows.CloseHandle(process)
		if terminateErr != nil {
			if firstErr == nil {
				firstErr = terminateErr
			}
			continue
		}
		closed++
	}
	if firstErr != nil {
		return closed, fmt.Errorf("关闭 Codex 桌面客户端失败: %w", firstErr)
	}
	return closed, nil
}
