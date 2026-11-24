package agent

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/google/uuid"
)

const directFileMax = 512 << 20
const directFileListLimit = 2000

type directFileEntry struct {
	Name string `json:"name"`
	Dir  bool   `json:"directory"`
	Size int64  `json:"size"`
}

type directFiles struct {
	mu   sync.Mutex
	read *directFileRead
}

type directFileRead struct {
	id     string
	file   *os.File
	info   os.FileInfo
	offset int64
}

func (a *Agent) supportsDirectFiles() bool {
	return a.cfg.Mock || a.cfg.AppServerURL == ""
}

func (s *directFiles) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closeRead()
}

func (s *directFiles) closeRead() {
	if s.read != nil {
		_ = s.read.file.Close()
		s.read = nil
	}
}

func (a *Agent) directWorkspace(ctx context.Context, app *appserver.Client, params map[string]any) (string, error) {
	path, _ := params["workspace"].(string)
	if !filepath.IsAbs(path) || strings.HasPrefix(path, "//") || strings.HasPrefix(path, "\\\\") {
		return "", errors.New("不是有效的本机工作区")
	}
	allowed := samePath(a.cfg.Cwd, path)
	if !allowed {
		list, err := a.workspaceRPC("codexrm/workspace/list", nil)
		if err == nil {
			for _, w := range list.(map[string]any)["workspaces"].([]workspace) {
				allowed = allowed || samePath(w.Path, path)
			}
		}
	}
	if thread, _ := params["threadId"].(string); !allowed && thread != "" && app != nil {
		callCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		raw, err := app.Call(callCtx, "thread/read", map[string]any{"threadId": thread, "includeTurns": false})
		var result struct{ Thread struct{ Cwd string } }
		allowed = err == nil && json.Unmarshal(raw, &result) == nil && filepath.IsAbs(result.Thread.Cwd) && samePath(result.Thread.Cwd, path)
	}
	if !allowed {
		return "", errors.New("只能访问已登记的工作区或会话工作目录")
	}
	path, err := filepath.EvalSymlinks(path)
	if err != nil || strings.HasPrefix(path, "//") || strings.HasPrefix(path, "\\\\") {
		return "", errors.New("无法打开本机工作区")
	}
	return path, nil
}

func localDirectFilePath(workspace, path string) (string, error) {
	if path == "" || path == "." {
		return ".", nil
	}
	if filepath.IsAbs(path) {
		var err error
		path, err = filepath.Rel(workspace, path)
		if err != nil {
			return "", errors.New("文件不在工作区内")
		}
	}
	for _, c := range strings.Split(filepath.ToSlash(path), "/") {
		if c == ".." || (runtime.GOOS == "windows" && (strings.Contains(c, ":") || strings.HasSuffix(c, ".") && c != "." || strings.HasSuffix(c, " "))) {
			return "", errors.New("文件不在工作区内")
		}
	}
	if !filepath.IsLocal(path) {
		return "", errors.New("文件不在工作区内")
	}
	return filepath.Clean(path), nil
}

func computerDirectFilePath(path string) (string, string, error) {
	invalid := errors.New("需要有效的电脑绝对路径")
	if !filepath.IsAbs(path) || strings.IndexByte(path, 0) >= 0 {
		return "", "", invalid
	}
	volume := filepath.VolumeName(path)
	if runtime.GOOS == "windows" {
		if len(volume) != 2 || volume[1] != ':' ||
			!(volume[0] >= 'A' && volume[0] <= 'Z' || volume[0] >= 'a' && volume[0] <= 'z') {
			return "", "", invalid
		}
	}
	root := volume + string(filepath.Separator)
	rel, err := localDirectFilePath(root, strings.TrimLeft(path[len(volume):], string(filepath.Separator)+"/"))
	if err != nil {
		return "", "", invalid
	}
	return root, rel, nil
}

func pathInside(parent, child string) bool {
	rel, err := filepath.Rel(parent, child)
	return err == nil && filepath.IsLocal(rel)
}

func directFileInfo(root *os.Root, path string, private os.FileInfo) (os.FileInfo, error) {
	// Check the root too, since a Windows drive letter can alias a directory.
	base, err := root.Stat(".")
	if err != nil {
		return nil, err
	}
	if private != nil && os.SameFile(private, base) {
		return nil, errDirectFilePrivate
	}
	if path == "." {
		return base, nil
	}
	// Skip links and special files. Root also enforces containment at open time.
	var info os.FileInfo
	current := "."
	for _, part := range strings.Split(path, string(filepath.Separator)) {
		current = filepath.Join(current, part)
		var err error
		info, err = root.Lstat(current)
		if err != nil || !(info.IsDir() || info.Mode().IsRegular()) {
			return nil, errors.New("文件不存在，或不是普通文件或目录")
		}
		if private != nil && os.SameFile(private, info) {
			return nil, errDirectFilePrivate
		}
	}
	return info, nil
}

var errDirectFilePrivate = errors.New("无法访问 Agent 数据目录")

func (a *Agent) directFileRPC(ctx context.Context, app *appserver.Client, d *directSession, method string, params map[string]any) (any, error) {
	if d == nil {
		return nil, errors.New("文件管理仅支持 P2P 直连")
	}
	if !a.supportsDirectFiles() {
		return nil, errors.New("Agent 与 Codex 不在同一台设备")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s := &d.files
	switch method {
	case protocol.DirectFilePrefix + "roots":
		roots, err := directFileRoots()
		if err != nil {
			return nil, errors.New("无法读取电脑盘符")
		}
		return map[string]any{"roots": roots}, nil
	case protocol.DirectFilePrefix + "read", protocol.DirectFilePrefix + "close":
		s.mu.Lock()
		defer s.mu.Unlock()
		id, _ := params["id"].(string)
		r := s.read
		if r == nil || r.id != id {
			return nil, errors.New("下载已结束，请重试")
		}
		if method == protocol.DirectFilePrefix+"close" {
			s.closeRead()
			return map[string]any{}, nil
		}
		next, ok := params["offset"].(float64)
		if !ok || next != float64(r.offset) {
			return nil, errors.New("下载分块顺序无效")
		}
		offset := r.offset
		data := make([]byte, min(int64(attachmentChunkSize), r.info.Size()-offset))
		if len(data) > 0 {
			if _, err := r.file.ReadAt(data, offset); err != nil {
				s.closeRead()
				return nil, errors.New("文件读取失败，可能已被修改")
			}
		}
		info, err := r.file.Stat()
		if err != nil || info.Size() != r.info.Size() || !info.ModTime().Equal(r.info.ModTime()) {
			s.closeRead()
			return nil, errors.New("文件已修改，请重新下载")
		}
		r.offset += int64(len(data))
		if r.offset == info.Size() {
			s.closeRead()
		}
		return map[string]any{"offset": r.offset, "data": base64.StdEncoding.EncodeToString(data)}, nil
	case protocol.DirectFilePrefix + "list", protocol.DirectFilePrefix + "stat", protocol.DirectFilePrefix + "open":
	default:
		return nil, errors.New("未知的文件操作")
	}
	base, path, err := a.directFileLocation(ctx, app, params)
	if err != nil {
		return nil, err
	}
	private, _ := filepath.EvalSymlinks(a.cfg.DataDir)
	if private == "" {
		private = a.cfg.DataDir
	}
	protected := func(path string) bool {
		return filepath.IsAbs(private) && pathInside(private, filepath.Join(base, path))
	}
	if protected(path) {
		return nil, errDirectFilePrivate
	}
	root, err := os.OpenRoot(base)
	if err != nil {
		return nil, errors.New("无法打开电脑目录")
	}
	defer root.Close()
	// Also compare directory identities, since Windows paths can use short aliases.
	var privateInfo os.FileInfo
	if filepath.IsAbs(private) {
		privateInfo, _ = os.Stat(private)
	}
	info, err := directFileInfo(root, path, privateInfo)
	if err != nil {
		return nil, err
	}
	if method == protocol.DirectFilePrefix+"list" {
		if !info.IsDir() {
			return nil, errors.New("不是文件夹")
		}
		f, err := root.Open(path)
		if err != nil {
			return nil, errors.New("无法读取文件夹")
		}
		defer f.Close()
		if actual, err := f.Stat(); err != nil || !os.SameFile(info, actual) {
			return nil, errors.New("文件夹已变更，请重试")
		}
		items, err := f.ReadDir(directFileListLimit + 1)
		if err != nil && !errors.Is(err, io.EOF) {
			return nil, errors.New("无法读取文件夹")
		}
		entries := make([]directFileEntry, 0, len(items))
		for _, entry := range items[:min(len(items), directFileListLimit)] {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if protected(filepath.Join(path, entry.Name())) {
				continue
			}
			item, err := entry.Info()
			if err == nil && (item.IsDir() || item.Mode().IsRegular()) {
				entries = append(entries, directFileEntry{entry.Name(), item.IsDir(), item.Size()})
			}
		}
		sort.Slice(entries, func(i, j int) bool {
			if entries[i].Dir != entries[j].Dir {
				return entries[i].Dir
			}
			return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
		})
		return map[string]any{"entries": entries, "truncated": len(items) > directFileListLimit}, nil
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("不是普通文件")
	}
	if method == protocol.DirectFilePrefix+"stat" {
		return directFileEntry{info.Name(), false, info.Size()}, nil
	}
	if info.Size() > directFileMax {
		return nil, errors.New("文件超过 512 MB 下载上限")
	}
	f, err := root.Open(path)
	if err != nil {
		return nil, errors.New("无法打开文件")
	}
	actual, err := f.Stat()
	if err != nil || !os.SameFile(info, actual) || !actual.Mode().IsRegular() || actual.Size() > directFileMax {
		_ = f.Close()
		return nil, errors.New("文件已变更，请重试")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if ctx.Err() != nil {
		_ = f.Close()
		return nil, ctx.Err()
	}
	s.closeRead()
	s.read = &directFileRead{id: uuid.NewString(), file: f, info: actual}
	return map[string]any{"id": s.read.id, "name": actual.Name(), "size": actual.Size()}, nil
}

func (a *Agent) directFileLocation(ctx context.Context, app *appserver.Client, params map[string]any) (string, string, error) {
	requested, _ := params["path"].(string)
	if params["scope"] == "computer" {
		return computerDirectFilePath(requested)
	}
	workspace, err := a.directWorkspace(ctx, app, params)
	if err != nil {
		return "", "", err
	}
	// Codex emits absolute paths even when the displayed workspace is an alias.
	if original, _ := params["workspace"].(string); filepath.IsAbs(requested) && original != workspace {
		if rel, err := localDirectFilePath(original, requested); err == nil {
			requested = rel
		}
	}
	path, err := localDirectFilePath(workspace, requested)
	return workspace, path, err
}
