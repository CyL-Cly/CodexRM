package clientconfig

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

const DefaultUpdateServer = "https://codexrm.dagongren.tech/"

// Connection retains the field names used by older installations.
type Connection struct {
	Server            string `json:"server"`
	EnrollmentKey     string `json:"enrollmentKey,omitempty"`
	Workspace         string `json:"workspace,omitempty"`
	CodexPath         string `json:"codexPath,omitempty"`
	AutoUpdate        bool   `json:"autoUpdate"`
	UpdateServer      string `json:"updateServer"`
	UpdateManifestURL string `json:"updateManifestUrl,omitempty"`
}

func Load(path string) (Connection, error) {
	c := Connection{AutoUpdate: true}
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		c.UpdateServer = DefaultUpdateServer
		return c, nil
	}
	if err != nil {
		return c, err
	}
	err = json.Unmarshal(bytes.TrimPrefix(b, []byte{0xef, 0xbb, 0xbf}), &c)
	c.UpdateServer = DefaultUpdateServer
	c.UpdateManifestURL = ""
	return c, err
}

func ServerURL(raw string) (string, error) {
	raw = strings.TrimRight(strings.TrimSpace(raw), "/")
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || strings.ContainsAny(raw, "\\\r\n") {
		return "", errors.New("请输入完整的 HTTP(S) 服务器地址，不支持账号、查询参数或片段")
	}
	return u.String(), nil
}

func (c *Connection) Normalize() error {
	var err error
	if strings.TrimSpace(c.Server) != "" {
		if c.Server, err = ServerURL(c.Server); err != nil {
			return fmt.Errorf("远程服务器：%w", err)
		}
	} else {
		c.Server = ""
	}
	// Older installations may retain custom feeds. They must not override the
	// built-in update service, even when settings are loaded without saving.
	c.UpdateServer = DefaultUpdateServer
	c.UpdateManifestURL = ""
	c.EnrollmentKey = strings.TrimSpace(c.EnrollmentKey)
	return nil
}

func (c Connection) ManifestURL() string {
	return DefaultUpdateServer + "update.json"
}

func Save(path string, c Connection) error {
	if err := c.Normalize(); err != nil {
		return err
	}
	return WriteJSON(path, c)
}

func WriteJSON(path string, value any) error {
	b, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".config-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(b)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), path)
}
