package agenthost

import (
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/clientconfig"
	"github.com/codexrm/codexrm/internal/installation"
	"github.com/codexrm/codexrm/internal/release"
	"github.com/skip2/go-qrcode"
)

//go:embed web/*
var assets embed.FS

type settingsPage struct {
	mu         sync.Mutex
	server     *http.Server
	url, token string
}

// SettingsURL starts one lazy, loopback-only listener for this process lifetime.
func (h *Host) SettingsURL() (string, error) {
	h.page.mu.Lock()
	defer h.page.mu.Unlock()
	h.mu.Lock()
	ctx := h.ctx
	h.mu.Unlock()
	if ctx == nil || ctx.Err() != nil {
		return "", errors.New("agent 尚未启动或已退出")
	}
	if h.page.server != nil {
		return h.page.url + "/#" + h.page.token, nil
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	secret := make([]byte, 32)
	if _, err = rand.Read(secret); err != nil {
		listener.Close()
		return "", err
	}
	h.page.token = base64.RawURLEncoding.EncodeToString(secret)
	h.page.url = "http://" + listener.Addr().String()
	mux := http.NewServeMux()
	mux.HandleFunc("/api/settings", h.settings)
	mux.HandleFunc("/api/pair", h.pair)
	mux.HandleFunc("/api/devices", h.devices)
	mux.HandleFunc("/api/devices/", h.devices)
	root, _ := fs.Sub(assets, "web")
	mux.Handle("/", http.FileServer(http.FS(root)))
	h.page.server = &http.Server{Handler: h.guard(mux), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: 40 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	go h.page.server.Serve(listener)
	return h.page.url + "/#" + h.page.token, nil
}

func (h *Host) closePage() {
	h.page.mu.Lock()
	defer h.page.mu.Unlock()
	if h.page.server != nil {
		_ = h.page.server.Close()
	}
}

func (h *Host) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
		if r.Host != strings.TrimPrefix(h.page.url, "http://") || (r.Header.Get("Origin") != "" && r.Header.Get("Origin") != h.page.url) || r.Header.Get("Sec-Fetch-Site") == "cross-site" {
			http.Error(w, "仅允许本机设置页面访问", http.StatusForbidden)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/") {
			token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if subtle.ConstantTimeCompare([]byte(token), []byte(h.page.token)) != 1 {
				http.Error(w, "请从托盘重新打开设置页面", http.StatusUnauthorized)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

func respond(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func failure(w http.ResponseWriter, err error) {
	respond(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
}

func (h *Host) snapshot() map[string]any {
	h.mu.Lock()
	defer h.mu.Unlock()
	status := "未配置"
	if h.config.Server != "" {
		status = "正在连接"
		if h.lastError != "" {
			status = "连接失败"
		}
		if h.active != nil && h.activeVersion == h.version && h.active.Connected() {
			status = "已连接"
		}
	}
	return map[string]any{"server": h.config.Server, "enrollmentKeySet": h.config.EnrollmentKey != "", "updateServer": h.config.UpdateServer, "updateManifestUrl": h.config.ManifestURL(), "status": status, "error": h.lastError, "version": release.VersionName, "packaged": installation.IsPackaged()}
}

func (h *Host) settings(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		respond(w, 200, h.snapshot())
		return
	}
	if r.Method != http.MethodPut {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var input struct {
		Server        string  `json:"server"`
		EnrollmentKey *string `json:"enrollmentKey"`
		UpdateServer  string  `json:"updateServer"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&input); err != nil {
		failure(w, err)
		return
	}
	h.opMu.Lock()
	defer h.opMu.Unlock()
	h.mu.Lock()
	cfg := h.config
	stopping := h.ctx == nil || h.ctx.Err() != nil
	h.mu.Unlock()
	if stopping {
		http.Error(w, "Agent 正在退出", http.StatusServiceUnavailable)
		return
	}
	server, err := clientconfig.ServerURL(input.Server)
	if err != nil {
		failure(w, err)
		return
	}
	if server != cfg.Server {
		cfg.EnrollmentKey = ""
	}
	cfg.Server = server
	if input.EnrollmentKey != nil {
		cfg.EnrollmentKey = *input.EnrollmentKey
	}
	if input.UpdateServer != cfg.UpdateServer {
		cfg.UpdateManifestURL = ""
	}
	cfg.UpdateServer = input.UpdateServer
	if err := cfg.Normalize(); err != nil {
		failure(w, err)
		return
	}
	if err := clientconfig.Save(h.path, cfg); err != nil {
		failure(w, err)
		return
	}
	h.mu.Lock()
	h.config = cfg
	h.version++
	h.lastError = ""
	h.mu.Unlock()
	select {
	case h.changed <- struct{}{}:
	default:
	}
	respond(w, 200, h.snapshot())
}

func (h *Host) pair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	h.opMu.Lock()
	defer h.opMu.Unlock()
	a, ctx, cancel, err := h.current(r.Context())
	if err != nil {
		failure(w, err)
		return
	}
	defer cancel()
	pair, err := a.Pair(ctx)
	if err != nil {
		failure(w, err)
		return
	}
	code := pair.ManualPairingCode
	if code == "" {
		code = pair.PairingCode
	}
	h.mu.Lock()
	link := h.config.Server + "/#pair=" + url.QueryEscape(code)
	h.mu.Unlock()
	png, err := qrcode.Encode(link, qrcode.Medium, 224)
	if err != nil {
		failure(w, err)
		return
	}
	respond(w, 200, map[string]any{"code": code, "url": link, "expiresAt": pair.ExpiresAt, "qr": "data:image/png;base64," + base64.StdEncoding.EncodeToString(png)})
}

func (h *Host) devices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodDelete {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	h.opMu.Lock()
	defer h.opMu.Unlock()
	a, ctx, cancel, err := h.current(r.Context())
	if err != nil {
		failure(w, err)
		return
	}
	defer cancel()
	if r.Method == http.MethodDelete {
		id := strings.TrimPrefix(r.URL.Path, "/api/devices/")
		if id == "" || id == r.URL.Path || strings.Contains(id, "/") {
			http.NotFound(w, r)
			return
		}
		if err := a.RemoveDevice(ctx, id); err != nil {
			failure(w, err)
			return
		}
		respond(w, 200, map[string]bool{"ok": true})
		return
	}
	devices, err := a.ListDevices(ctx)
	if err != nil {
		failure(w, err)
		return
	}
	if devices == nil {
		respond(w, 200, []any{})
		return
	}
	respond(w, 200, devices)
}
