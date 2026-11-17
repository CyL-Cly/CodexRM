package agent

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

type Config struct {
	OnReady                                                                                                         func()
	ServerURL, AppServerURL, AppServerToken, Name, DataDir, InstallationID, EnrollmentKey, CodexPath, Cwd, MockAddr string
	CCSwitchDB                                                                                                      string
	Mock                                                                                                            bool
	NoAutoPair                                                                                                      bool
}
type creds struct {
	AgentID        string `json:"agentId"`
	EnvironmentID  string `json:"environmentId"`
	Token          string `json:"token"`
	InstallationID string `json:"installationId"`
	ServerURL      string `json:"serverUrl"`
	ExpiresAt      int64  `json:"expiresAt"`
}
type approval struct {
	ID       json.RawMessage
	Raw      json.RawMessage
	Owner    string
	ThreadID string
	TurnID   string
	Sending  bool
}
type Agent struct {
	cfg        Config
	creds      creds
	credsDirty bool
	credsMu    sync.RWMutex
	enrollMu   sync.Mutex
	mu         sync.Mutex
	writeMu    sync.Mutex
	relay      *websocket.Conn
	owners     map[string]string
	approvals  map[string]approval
	queueMu    sync.Mutex
	queues     map[string]*messageQueue
	queueCtx   context.Context
}

func New(cfg Config) (*Agent, error) {
	cfg.ServerURL = strings.TrimRight(cfg.ServerURL, "/")
	u, e := url.Parse(cfg.ServerURL)
	if e != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, errors.New("--server must be an http(s) URL")
	}
	if cfg.DataDir == "" {
		home, e := os.UserHomeDir()
		if e != nil {
			return nil, e
		}
		hash := sha256.Sum256([]byte(cfg.ServerURL))
		cfg.DataDir = filepath.Join(home, ".codexrm", hex.EncodeToString(hash[:6]))
	}
	if cfg.Cwd == "" {
		cfg.Cwd, _ = os.Getwd()
	}
	cfg.Cwd, e = filepath.Abs(cfg.Cwd)
	if e != nil {
		return nil, e
	}
	if st, e := os.Stat(cfg.Cwd); e != nil || !st.IsDir() {
		return nil, fmt.Errorf("working directory does not exist: %s", cfg.Cwd)
	}
	if cfg.Name == "" {
		cfg.Name, _ = os.Hostname()
	}
	if cfg.MockAddr == "" {
		cfg.MockAddr = "127.0.0.1:0"
	}
	if e = os.MkdirAll(cfg.DataDir, 0700); e != nil {
		return nil, e
	}
	a := &Agent{cfg: cfg, owners: map[string]string{}, approvals: map[string]approval{}}
	data, e := os.ReadFile(filepath.Join(cfg.DataDir, "agent.json"))
	if e == nil {
		if e = json.Unmarshal(data, &a.creds); e != nil {
			return nil, e
		}
	} else if !errors.Is(e, os.ErrNotExist) {
		return nil, e
	}
	if a.creds.ServerURL != "" && a.creds.ServerURL != cfg.ServerURL {
		return nil, errors.New("agent data belongs to another server; use a separate --data directory")
	}
	if cfg.InstallationID != "" {
		a.creds.InstallationID = cfg.InstallationID
	}
	if a.creds.InstallationID == "" {
		a.creds.InstallationID = uuid.NewString()
	}
	a.creds.ServerURL = cfg.ServerURL
	return a, nil
}

func (a *Agent) StateDir() string { return a.cfg.DataDir }

func (a *Agent) Connected() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.relay != nil
}

func (a *Agent) credentials() (creds, bool) {
	a.credsMu.RLock()
	defer a.credsMu.RUnlock()
	return a.creds, a.credsDirty
}

func (a *Agent) save() error {
	a.credsMu.Lock()
	defer a.credsMu.Unlock()
	b, e := json.MarshalIndent(a.creds, "", "  ")
	if e != nil {
		return e
	}
	p := filepath.Join(a.cfg.DataDir, "agent.json")
	if e = os.WriteFile(p+".tmp", b, 0600); e != nil {
		return e
	}
	if e = os.Rename(p+".tmp", p); e != nil {
		return e
	}
	a.credsDirty = false
	return nil
}
func (a *Agent) request(ctx context.Context, path string, input, output any) error {
	return a.requestMethod(ctx, "POST", path, input, output)
}

func (a *Agent) requestMethod(ctx context.Context, method, path string, input, output any) error {
	raw, e := json.Marshal(input)
	if e != nil {
		return e
	}
	req, e := http.NewRequestWithContext(ctx, method, a.cfg.ServerURL+path, bytes.NewReader(raw))
	if e != nil {
		return e
	}
	req.Header.Set("Content-Type", "application/json")
	credentials, _ := a.credentials()
	req.Header.Set("Authorization", "Bearer "+credentials.Token)
	req.Header.Set("X-Enrollment-Key", a.cfg.EnrollmentKey)
	resp, e := (&http.Client{Timeout: 15 * time.Second}).Do(req)
	if e != nil {
		return e
	}
	defer resp.Body.Close()
	data, e := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if e != nil {
		return e
	}
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s: HTTP %d: %s", path, resp.StatusCode, strings.TrimSpace(string(data)))
	}
	if output == nil {
		return nil
	}
	return json.Unmarshal(data, output)
}
func (a *Agent) ensureEnrolled(ctx context.Context) error {
	a.enrollMu.Lock()
	defer a.enrollMu.Unlock()
	credentials, dirty := a.credentials()
	if dirty {
		if err := a.save(); err != nil {
			return err
		}
	}
	if credentials.Token != "" && (credentials.ExpiresAt == 0 || credentials.ExpiresAt > time.Now().Add(24*time.Hour).Unix()) {
		return nil
	}
	var out protocol.EnrollResponse
	if e := a.request(ctx, "/api/v1/agent/enroll", protocol.EnrollRequest{Name: a.cfg.Name, OS: runtime.GOOS, Arch: runtime.GOARCH, AppVersion: protocol.Version, InstallationID: credentials.InstallationID}, &out); e != nil {
		return e
	}
	a.credsMu.Lock()
	a.creds.AgentID = out.AgentID
	a.creds.EnvironmentID = out.EnvironmentID
	a.creds.Token = out.Token
	a.creds.ExpiresAt = out.ExpiresAt
	a.credsDirty = true
	a.credsMu.Unlock()
	return a.save()
}
func (a *Agent) Pair(ctx context.Context) (*protocol.PairStartResponse, error) {
	if e := a.ensureEnrolled(ctx); e != nil {
		return nil, e
	}
	var out protocol.PairStartResponse
	if e := a.request(ctx, "/api/v1/agent/pair", protocol.PairStartRequest{ManualCode: true}, &out); e != nil {
		return nil, e
	}
	return &out, nil
}
func (a *Agent) ListDevices(ctx context.Context) ([]protocol.ClientInfo, error) {
	if err := a.ensureEnrolled(ctx); err != nil {
		return nil, err
	}
	var out struct {
		Data []protocol.ClientInfo `json:"data"`
	}
	if err := a.requestMethod(ctx, "GET", "/api/v1/agent/clients", nil, &out); err != nil {
		return nil, err
	}
	return out.Data, nil
}

func (a *Agent) RemoveDevice(ctx context.Context, clientID string) error {
	if err := a.ensureEnrolled(ctx); err != nil {
		return err
	}
	return a.requestMethod(ctx, "DELETE", "/api/v1/agent/clients/"+url.PathEscape(clientID), nil, nil)
}

func (a *Agent) Run(ctx context.Context) error {
	for {
		e := a.ensureEnrolled(ctx)
		if e == nil {
			break
		}
		if _, dirty := a.credentials(); !dirty {
			return e
		}
		log.Printf("save renewed agent credentials: %v; retrying", e)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(3 * time.Second):
		}
	}
	if !a.cfg.NoAutoPair {
		if p, e := a.Pair(ctx); e == nil {
			instructions, e := a.PairingInstructions(p)
			if e != nil {
				return e
			}
			log.Print(instructions)
		} else {
			return e
		}
	}
	if a.cfg.Mock {
		mock, e := appserver.StartMock(a.cfg.MockAddr)
		if e != nil {
			return e
		}
		defer mock.Close()
		a.cfg.AppServerURL = "ws://" + mock.Addr
	}
	for ctx.Err() == nil {
		var app *appserver.Client
		var e error
		if a.cfg.AppServerURL != "" {
			app, e = appserver.Dial(ctx, a.cfg.AppServerURL, a.cfg.AppServerToken)
		} else {
			app, e = appserver.Launch(ctx, a.cfg.CodexPath, a.cfg.Cwd)
		}
		if e != nil {
			return e
		}
		initCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		_, e = app.Initialize(initCtx, "codexrm-agent", protocol.Version)
		cancel()
		if e != nil {
			_ = app.Close()
			return fmt.Errorf("initialize Codex: %w", e)
		}
		log.Printf("Codex ready; workspace: %s; mock=%v", a.cfg.Cwd, a.cfg.Mock)
		if a.cfg.OnReady != nil {
			a.cfg.OnReady()
		}
		a.mu.Lock()
		a.approvals = map[string]approval{}
		a.mu.Unlock()
		appCtx, stop := context.WithCancel(ctx)
		a.queueMu.Lock()
		a.queues = map[string]*messageQueue{}
		a.queueCtx = appCtx
		a.queueMu.Unlock()
		app.OnNotification(func(raw json.RawMessage) {
			a.forward(raw)
			a.queueNotification(appCtx, app, raw)
		})
		go func() {
			select {
			case <-app.Done():
				stop()
			case <-appCtx.Done():
			}
		}()
		for appCtx.Err() == nil {
			if e = a.ensureEnrolled(appCtx); e != nil {
				log.Printf("renew agent registration: %v", e)
				select {
				case <-appCtx.Done():
				case <-time.After(3 * time.Second):
				}
				continue
			}
			e = a.session(appCtx, app)
			if appCtx.Err() != nil {
				break
			}
			log.Printf("relay disconnected: %v; retry in 3s", e)
			select {
			case <-appCtx.Done():
			case <-time.After(3 * time.Second):
			}
		}
		stop()
		_ = app.Close()
		if ctx.Err() == nil {
			log.Printf("Codex exited; restarting in 3s")
			select {
			case <-ctx.Done():
			case <-time.After(3 * time.Second):
			}
		}
	}
	return ctx.Err()
}
func (a *Agent) write(conn *websocket.Conn, env protocol.Envelope) error {
	if conn == nil {
		return errors.New("relay offline")
	}
	a.writeMu.Lock()
	defer a.writeMu.Unlock()
	_ = conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
	return conn.WriteJSON(env)
}
func (a *Agent) forward(raw json.RawMessage) {
	var m map[string]json.RawMessage
	if json.Unmarshal(raw, &m) != nil {
		return
	}
	a.mu.Lock()
	relay := a.relay
	owner := ""
	var method string
	_ = json.Unmarshal(m["method"], &method)
	var meta struct {
		ThreadID  string          `json:"threadId"`
		TurnID    string          `json:"turnId"`
		RequestID json.RawMessage `json:"requestId"`
		Turn      struct {
			ID string `json:"id"`
		} `json:"turn"`
	}
	_ = json.Unmarshal(m["params"], &meta)
	if method == "serverRequest/resolved" {
		for key, pending := range a.approvals {
			if string(pending.ID) == string(meta.RequestID) && pending.ThreadID == meta.ThreadID {
				delete(a.approvals, key)
				var params map[string]json.RawMessage
				_ = json.Unmarshal(m["params"], &params)
				params["requestId"] = mustJSON(key)
				m["params"] = mustJSON(params)
				raw = mustJSON(m)
				owner = pending.Owner
				break
			}
		}
	}
	if method == "turn/completed" {
		for key, pending := range a.approvals {
			if pending.ThreadID == meta.ThreadID && pending.TurnID == meta.Turn.ID {
				delete(a.approvals, key)
			}
		}
	}
	if id := m["id"]; len(id) > 0 && string(id) != "null" {
		owner = a.owners[meta.ThreadID]
		key := "approval_" + uuid.NewString()
		m["id"] = mustJSON(key)
		raw = mustJSON(m)
		a.approvals[key] = approval{ID: id, Raw: raw, Owner: owner, ThreadID: meta.ThreadID, TurnID: meta.TurnID}
	}
	a.mu.Unlock()
	if relay != nil {
		_ = a.write(relay, protocol.Envelope{Type: protocol.TypeServerMessage, ClientID: owner, Message: raw})
	}
}
func (a *Agent) session(ctx context.Context, app *appserver.Client) error {
	u, _ := url.Parse(a.cfg.ServerURL + "/api/v1/agent/ws")
	if u.Scheme == "https" {
		u.Scheme = "wss"
	} else {
		u.Scheme = "ws"
	}
	credentials, _ := a.credentials()
	conn, _, e := (&websocket.Dialer{HandshakeTimeout: 10 * time.Second}).DialContext(ctx, u.String(), http.Header{"Authorization": []string{"Bearer " + credentials.Token}})
	if e != nil {
		return e
	}
	defer conn.Close()
	conn.SetReadLimit(16 << 20)
	a.mu.Lock()
	a.relay = conn
	a.mu.Unlock()
	defer func() {
		a.mu.Lock()
		if a.relay == conn {
			a.relay = nil
		}
		a.mu.Unlock()
	}()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() { <-ctx.Done(); _ = conn.Close() }()
	log.Printf("relay connected as %s (%s)", credentials.AgentID, a.cfg.Name)
	slots := make(chan struct{}, 32)
	for {
		var env protocol.Envelope
		if e = conn.ReadJSON(&env); e != nil {
			return e
		}
		if env.Type == protocol.TypePing {
			_ = a.write(conn, protocol.Envelope{Type: protocol.TypePong, PingID: env.PingID})
			continue
		}
		if env.Type != protocol.TypeClientMessage {
			continue
		}
		select {
		case slots <- struct{}{}:
			go func() { defer func() { <-slots }(); a.handle(ctx, app, conn, env) }()
		default:
			a.respond(conn, env, nil, &appserver.RPCError{Code: -32001, Message: "too many concurrent requests"})
		}
	}
}
func (a *Agent) handle(ctx context.Context, app *appserver.Client, conn *websocket.Conn, env protocol.Envelope) {
	var req struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params json.RawMessage `json:"params"`
		Result json.RawMessage `json:"result"`
		Error  json.RawMessage `json:"error"`
	}
	if json.Unmarshal(env.Message, &req) != nil {
		return
	}
	if req.Method == "" {
		if len(req.Result) == 0 && len(req.Error) == 0 {
			return
		}
		var key string
		_ = json.Unmarshal(req.ID, &key)
		a.mu.Lock()
		p, ok := a.approvals[key]
		if ok && !p.Sending && (p.Owner == "" || p.Owner == env.ClientID) {
			p.Sending = true
			a.approvals[key] = p
		} else {
			ok = false
		}
		a.mu.Unlock()
		if ok {
			raw := map[string]json.RawMessage{"id": p.ID}
			if len(req.Error) > 0 {
				raw["error"] = req.Error
			} else if len(req.Result) > 0 {
				raw["result"] = req.Result
			} else {
				return
			}
			err := app.WriteRaw(mustJSON(raw))
			a.mu.Lock()
			if current, exists := a.approvals[key]; exists {
				if err == nil {
					delete(a.approvals, key)
				} else {
					current.Sending = false
					a.approvals[key] = current
				}
			}
			a.mu.Unlock()
			if err == nil {
				_ = a.write(conn, protocol.Envelope{Type: protocol.TypeServerMessage, Message: mustJSON(map[string]any{"method": "codexrm/request/resolved", "params": map[string]any{"id": key}})})
			} else {
				_ = a.write(conn, protocol.Envelope{Type: protocol.TypeServerMessage, ClientID: env.ClientID, Message: mustJSON(map[string]any{"method": "codexrm/request/failed", "params": map[string]any{"id": key, "message": err.Error()}})})
			}
		}
		return
	}
	if req.Method == "initialize" || req.Method == "initialized" {
		a.respond(conn, env, nil, &appserver.RPCError{Code: -32600, Message: "Agent owns initialization"})
		return
	}
	if req.Method == "codexrm/info" {
		a.respond(conn, env, mustJSON(map[string]any{"name": a.cfg.Name, "cwd": a.cfg.Cwd, "mock": a.cfg.Mock, "version": protocol.Version, "os": runtime.GOOS, "workspaces": true, "threadSnapshots": true, "providerModels": true, "messageQueue": true, "desktopControl": runtime.GOOS == "windows", "transport": map[bool]string{true: "websocket", false: "stdio"}[a.cfg.AppServerURL != ""]}), nil)
		a.mu.Lock()
		pending := []approval{}
		for _, p := range a.approvals {
			if p.Owner == "" || p.Owner == env.ClientID {
				pending = append(pending, p)
			}
		}
		a.mu.Unlock()
		for _, p := range pending {
			_ = a.write(conn, protocol.Envelope{Type: protocol.TypeServerMessage, ClientID: env.ClientID, Message: p.Raw})
		}
		return
	}
	var params map[string]any
	if len(req.Params) > 0 && string(req.Params) != "null" {
		if e := json.Unmarshal(req.Params, &params); e != nil {
			a.respond(conn, env, nil, &appserver.RPCError{Code: -32602, Message: e.Error()})
			return
		}
	}
	if params == nil {
		params = map[string]any{}
	}
	if req.Method == "codexrm/model/list" {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		result, err := a.modelCatalog(callCtx, app)
		a.respond(conn, env, mustJSON(result), err)
		return
	}
	if req.Method == "codexrm/thread/snapshot" {
		callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
		result, err := a.threadSnapshot(callCtx, app, params)
		a.respond(conn, env, result, err)
		return
	}
	if strings.HasPrefix(req.Method, "codexrm/queue/") {
		result, err := a.queueRPC(ctx, app, env.ClientID, req.Method, params)
		a.respond(conn, env, mustJSON(result), err)
		return
	}
	if strings.HasPrefix(req.Method, "codexrm/desktop/") {
		result, err := a.desktopRPC(req.Method)
		a.respond(conn, env, mustJSON(result), err)
		return
	}
	if strings.HasPrefix(req.Method, "codexrm/") {
		result, err := a.workspaceRPC(req.Method, params)
		a.respond(conn, env, mustJSON(result), err)
		return
	}
	if req.Method == "thread/start" && params["cwd"] == nil {
		params["cwd"] = a.cfg.Cwd
	}
	if id, ok := params["threadId"].(string); ok && req.Method == "turn/start" {
		a.mu.Lock()
		a.owners[id] = env.ClientID
		a.mu.Unlock()
	}
	if len(req.ID) == 0 {
		_ = app.Notify(req.Method, params)
		return
	}
	callCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	if err := a.prepareProvider(callCtx, app, req.Method, params); err != nil {
		a.respond(conn, env, nil, err)
		return
	}
	result, e := app.Call(callCtx, req.Method, params)
	if e == nil {
		if err := a.rememberThreadModel(req.Method, params, result); err != nil {
			log.Printf("unable to save thread model selection: %v", err)
		}
	}
	if e == nil && req.Method == "thread/resume" {
		if id, ok := params["threadId"].(string); ok {
			a.mu.Lock()
			a.owners[id] = env.ClientID
			a.mu.Unlock()
		}
	}
	if e == nil && req.Method == "thread/start" {
		var out struct {
			Thread struct {
				ID string `json:"id"`
			} `json:"thread"`
		}
		_ = json.Unmarshal(result, &out)
		a.mu.Lock()
		a.owners[out.Thread.ID] = env.ClientID
		a.mu.Unlock()
	}
	a.respond(conn, env, result, e)
}
func (a *Agent) respond(conn *websocket.Conn, env protocol.Envelope, result json.RawMessage, err error) {
	var req struct {
		ID json.RawMessage `json:"id"`
	}
	_ = json.Unmarshal(env.Message, &req)
	if len(req.ID) == 0 {
		return
	}
	resp := map[string]any{"id": req.ID}
	if err != nil {
		rpc := &appserver.RPCError{Code: -32000, Message: err.Error()}
		var original *appserver.RPCError
		if errors.As(err, &original) {
			rpc = original
		}
		resp["error"] = rpc
	} else {
		if len(result) == 0 {
			result = json.RawMessage("null")
		}
		resp["result"] = result
	}
	_ = a.write(conn, protocol.Envelope{Type: protocol.TypeServerMessage, ClientID: env.ClientID, StreamID: env.StreamID, Message: mustJSON(resp)})
}
func mustJSON(v any) json.RawMessage { b, _ := json.Marshal(v); return b }
