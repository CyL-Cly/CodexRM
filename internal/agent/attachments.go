package agent

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
)

const attachmentMaxSize = 20 << 20
const attachmentChunkSize = 256 << 10

type attachment struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	MimeType string `json:"mimeType"`
}

type attachmentUpload struct {
	File    attachment
	Owner   string
	Offset  int64
	Updated time.Time
}

func attachmentName(name string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.Trim(name, " .")
	runes := []rune(name)
	if len(runes) > 120 {
		name = string(runes[:120])
	}
	for len(name) > 180 {
		_, size := utf8.DecodeLastRuneInString(name)
		name = name[:len(name)-size]
	}
	if name == "" {
		name = "attachment"
	}
	return name
}

func attachmentNumber(params map[string]any, key string) (int64, error) {
	v, ok := params[key].(float64)
	if !ok || v < 0 || v > attachmentMaxSize || v != float64(int64(v)) {
		return 0, fmt.Errorf("invalid %s", key)
	}
	return int64(v), nil
}

// Attachment RPCs use small frames on the existing authenticated relay. Files
// stay on the Agent computer so Codex can read them after a browser disconnect.
func (a *Agent) attachmentRPC(owner, method string, params map[string]any) (any, error) {
	a.attachmentMu.Lock()
	defer a.attachmentMu.Unlock()
	if a.uploads == nil {
		a.uploads = map[string]*attachmentUpload{}
	}
	root, err := filepath.Abs(filepath.Join(a.cfg.DataDir, "attachments"))
	if err != nil {
		return nil, err
	}
	for id, upload := range a.uploads {
		if time.Since(upload.Updated) > 30*time.Minute {
			_ = os.Remove(upload.File.Path + ".part")
			_ = os.Remove(filepath.Dir(upload.File.Path))
			delete(a.uploads, id)
		}
	}
	if method == "codexrm/attachment/begin" {
		name, _ := params["name"].(string)
		size, err := attachmentNumber(params, "size")
		if err != nil || size > attachmentMaxSize {
			return nil, fmt.Errorf("每个附件最大 20 MB")
		}
		if len(a.uploads) >= 30 {
			return nil, fmt.Errorf("同时上传的附件过多，请稍后重试")
		}
		file := attachment{ID: uuid.NewString(), Name: attachmentName(name), Size: size}
		file.Path = filepath.Join(root, file.ID, "file-"+file.Name)
		if err := os.MkdirAll(filepath.Dir(file.Path), 0700); err != nil {
			return nil, err
		}
		f, err := os.OpenFile(file.Path+".part", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			return nil, err
		}
		if err := f.Close(); err != nil {
			return nil, err
		}
		a.uploads[file.ID] = &attachmentUpload{File: file, Owner: owner, Updated: time.Now()}
		return map[string]any{"id": file.ID, "chunkSize": attachmentChunkSize}, nil
	}
	id, _ := params["id"].(string)
	parsed, err := uuid.Parse(id)
	if err != nil || parsed.String() != id {
		return nil, fmt.Errorf("invalid attachment id")
	}
	if method == "codexrm/attachment/read" {
		// Reads only address finalized uploads, never an arbitrary local path.
		raw, err := os.ReadFile(filepath.Join(root, id, "metadata.json"))
		if err != nil {
			return nil, fmt.Errorf("附件不存在或已被移除")
		}
		var file attachment
		if err := json.Unmarshal(raw, &file); err != nil {
			return nil, err
		}
		path := filepath.Join(root, id, "file-"+attachmentName(file.Name))
		if file.ID != id || file.Path != path {
			return nil, fmt.Errorf("invalid attachment metadata")
		}
		offset, err := attachmentNumber(params, "offset")
		if err != nil || offset > file.Size {
			return nil, fmt.Errorf("invalid attachment offset")
		}
		f, err := os.Open(path)
		if err != nil {
			return nil, err
		}
		defer f.Close()
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			return nil, err
		}
		data, err := io.ReadAll(io.LimitReader(f, attachmentChunkSize))
		if err != nil {
			return nil, err
		}
		if len(data) == 0 && offset < file.Size {
			return nil, fmt.Errorf("附件内容不完整")
		}
		return map[string]any{"file": file, "data": base64.StdEncoding.EncodeToString(data), "offset": offset + int64(len(data))}, nil
	}
	upload := a.uploads[id]
	if upload == nil || upload.Owner != owner {
		return nil, fmt.Errorf("上传已失效，请重新选择附件")
	}
	upload.Updated = time.Now()
	switch method {
	case "codexrm/attachment/abort":
		if err := os.Remove(upload.File.Path + ".part"); err != nil && !os.IsNotExist(err) {
			return nil, err
		}
		_ = os.Remove(filepath.Dir(upload.File.Path))
		delete(a.uploads, id)
		return map[string]any{}, nil
	case "codexrm/attachment/chunk":
		offset, err := attachmentNumber(params, "offset")
		if err != nil || offset != upload.Offset {
			return nil, fmt.Errorf("附件上传位置不匹配，请重试")
		}
		encoded, _ := params["data"].(string)
		if len(encoded) > base64.StdEncoding.EncodedLen(attachmentChunkSize) {
			return nil, fmt.Errorf("attachment chunk is too large")
		}
		data, err := base64.StdEncoding.DecodeString(encoded)
		if err != nil || len(data) == 0 || upload.Offset+int64(len(data)) > upload.File.Size {
			return nil, fmt.Errorf("invalid attachment chunk")
		}
		f, err := os.OpenFile(upload.File.Path+".part", os.O_WRONLY, 0600)
		if err != nil {
			return nil, err
		}
		n, writeErr := f.WriteAt(data, upload.Offset)
		closeErr := f.Close()
		if writeErr != nil {
			return nil, writeErr
		}
		if closeErr != nil {
			return nil, closeErr
		}
		upload.Offset += int64(n)
		return map[string]any{"offset": upload.Offset}, nil
	case "codexrm/attachment/finish":
		if upload.Offset != upload.File.Size {
			return nil, fmt.Errorf("附件尚未上传完成")
		}
		f, err := os.Open(upload.File.Path + ".part")
		if err != nil {
			return nil, err
		}
		header := make([]byte, 512)
		n, readErr := f.Read(header)
		_ = f.Close()
		if readErr != nil && readErr != io.EOF {
			return nil, readErr
		}
		upload.File.MimeType = strings.Split(http.DetectContentType(header[:n]), ";")[0]
		if err := os.Rename(upload.File.Path+".part", upload.File.Path); err != nil {
			return nil, err
		}
		raw, err := json.Marshal(upload.File)
		if err == nil {
			err = os.WriteFile(filepath.Join(filepath.Dir(upload.File.Path), "metadata.json"), raw, 0600)
		}
		if err != nil {
			_ = os.Remove(upload.File.Path)
			delete(a.uploads, id)
			return nil, err
		}
		delete(a.uploads, id)
		return upload.File, nil
	default:
		return nil, fmt.Errorf("unknown attachment method: %s", method)
	}
}
