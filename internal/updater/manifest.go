package updater

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"strings"
)

const MaxPackageSize int64 = 512 << 20

// Manifest matches the update.json format used by SmsForwarder.
type Manifest struct {
	VersionCode  int64  `json:"versionCode"`
	VersionName  string `json:"versionName"`
	DownloadURL  string `json:"downloadUrl"`
	ReleaseNotes string `json:"releaseNotes"`
	FileSize     int64  `json:"fileSize"`
	SHA256       string `json:"sha256"`
}

func ReadManifest(r io.Reader) (Manifest, error) {
	var m Manifest
	data, err := io.ReadAll(io.LimitReader(r, (1<<20)+1))
	if err != nil {
		return m, err
	}
	if len(data) > 1<<20 {
		return m, errors.New("update manifest exceeds 1 MiB")
	}
	dec := json.NewDecoder(bytes.NewReader(bytes.TrimPrefix(data, []byte{0xef, 0xbb, 0xbf})))
	if err := dec.Decode(&m); err != nil {
		return m, fmt.Errorf("decode update manifest: %w", err)
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		return m, errors.New("expected one update manifest")
	}
	return m, m.Validate()
}

func (m Manifest) Validate() error {
	if m.VersionCode <= 0 || strings.TrimSpace(m.VersionName) == "" {
		return errors.New("update versionCode and versionName are required")
	}
	if m.FileSize <= 0 || m.FileSize > MaxPackageSize {
		return errors.New("update fileSize must be between 1 byte and 512 MiB")
	}
	hash, err := hex.DecodeString(m.SHA256)
	if err != nil || len(hash) != 32 {
		return errors.New("update sha256 must contain 64 hexadecimal characters")
	}
	u, err := url.Parse(m.DownloadURL)
	if err != nil || m.DownloadURL == "" || u.User != nil || u.Fragment != "" ||
		(u.IsAbs() && (u.Scheme != "http" && u.Scheme != "https" || u.Host == "")) ||
		(!u.IsAbs() && (u.Host != "" || u.Path == "")) {
		return errors.New("update downloadUrl must be an HTTP(S) URL or a relative path")
	}
	return nil
}
