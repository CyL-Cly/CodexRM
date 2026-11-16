package appserver

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/gorilla/websocket"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type MockServer struct {
	Addr string
	http *http.Server
	hub  *mockHub
}
type mockHub struct {
	mu          sync.Mutex
	threads     []map[string]any
	next        atomic.Int64
	active      map[string]context.CancelFunc
	connections map[*mockConn]bool
}
type mockConn struct {
	hub       *mockHub
	conn      *websocket.Conn
	mu        sync.Mutex
	pendingMu sync.Mutex
	pending   map[string]chan json.RawMessage
	ctx       context.Context
}

func StartMock(addr string) (*MockServer, error) {
	listener, e := net.Listen("tcp", addr)
	if e != nil {
		return nil, e
	}
	h := &mockHub{active: map[string]context.CancelFunc{}, connections: map[*mockConn]bool{}}
	mux := http.NewServeMux()
	up := websocket.Upgrader{}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		conn, e := up.Upgrade(w, r, nil)
		if e == nil {
			h.serve(conn)
		}
	})
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = srv.Serve(listener) }()
	return &MockServer{Addr: listener.Addr().String(), http: srv, hub: h}, nil
}
func (s *MockServer) Close() error {
	s.hub.mu.Lock()
	for _, cancel := range s.hub.active {
		cancel()
	}
	for c := range s.hub.connections {
		_ = c.conn.Close()
	}
	s.hub.mu.Unlock()
	return s.http.Close()
}
func (c *mockConn) write(v any) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return c.conn.WriteJSON(v)
}
func (c *mockConn) event(method string, p any) {
	_ = c.write(map[string]any{"method": method, "params": p})
}
func (c *mockConn) reply(id json.RawMessage, v any) {
	if len(id) > 0 {
		_ = c.write(map[string]any{"id": id, "result": v})
	}
}
func clone(v any) any { b, _ := json.Marshal(v); var out any; _ = json.Unmarshal(b, &out); return out }
func (h *mockHub) threadLocked(id string) map[string]any {
	for _, t := range h.threads {
		if t["id"] == id {
			return t
		}
	}
	return nil
}
func (h *mockHub) serve(conn *websocket.Conn) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	defer conn.Close()
	c := &mockConn{hub: h, conn: conn, pending: map[string]chan json.RawMessage{}, ctx: ctx}
	h.mu.Lock()
	h.connections[c] = true
	h.mu.Unlock()
	defer func() { h.mu.Lock(); delete(h.connections, c); h.mu.Unlock() }()
	for {
		var msg struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
			Params map[string]any  `json:"params"`
			Result json.RawMessage `json:"result"`
		}
		if conn.ReadJSON(&msg) != nil {
			return
		}
		if msg.Method == "" {
			c.pendingMu.Lock()
			ch := c.pending[string(msg.ID)]
			c.pendingMu.Unlock()
			if ch != nil {
				select {
				case ch <- msg.Result:
				default:
				}
			}
			continue
		}
		p := msg.Params
		id := msg.ID
		tid, _ := p["threadId"].(string)
		switch msg.Method {
		case "initialize":
			c.reply(id, map[string]any{"userAgent": "codexrm-mock/0.2.0", "platformFamily": "mock"})
		case "initialized":
		case "model/list":
			c.reply(id, map[string]any{"data": []any{map[string]any{"id": "mock-codex", "model": "mock-codex", "displayName": "Codex · 演示", "isDefault": true, "supportedReasoningEfforts": []any{map[string]any{"reasoningEffort": "medium", "description": "默认"}}}}, "nextCursor": nil})
		case "account/read":
			c.reply(id, map[string]any{"account": map[string]any{"type": "mock", "email": "本地模拟模式"}, "requiresOpenaiAuth": false})
		case "thread/list":
			h.mu.Lock()
			data := []any{}
			for _, t := range h.threads {
				archived, _ := t["archived"].(bool)
				want, _ := p["archived"].(bool)
				cwd, _ := p["cwd"].(string)
				if archived == want && (cwd == "" || t["cwd"] == cwd) {
					th := clone(t).(map[string]any)
					delete(th, "turns")
					data = append(data, th)
				}
			}
			h.mu.Unlock()
			c.reply(id, map[string]any{"data": data, "nextCursor": nil})
		case "thread/start":
			cwd, _ := p["cwd"].(string)
			if cwd == "" {
				cwd = "mock-workspace"
			}
			model, _ := p["model"].(string)
			if model == "" {
				model = "mock-codex"
			}
			provider, _ := p["modelProvider"].(string)
			t := map[string]any{"id": fmt.Sprintf("thread_%d", h.next.Add(1)), "name": "新会话", "preview": "", "createdAt": time.Now().Unix(), "updatedAt": time.Now().Unix(), "cwd": cwd, "modelProvider": provider, "model": model, "status": map[string]any{"type": "idle"}, "turns": []any{}}
			h.mu.Lock()
			h.threads = append([]map[string]any{t}, h.threads...)
			copy := clone(t)
			h.mu.Unlock()
			c.reply(id, map[string]any{"thread": copy, "model": model, "modelProvider": provider, "cwd": cwd})
			c.event("thread/started", map[string]any{"thread": copy})
		case "thread/resume", "thread/read":
			h.mu.Lock()
			t := h.threadLocked(tid)
			var copy any
			if t != nil {
				copy = clone(t)
			}
			h.mu.Unlock()
			if copy == nil {
				c.rpcError(id, "thread not found")
			} else {
				thread := copy.(map[string]any)
				c.reply(id, map[string]any{"thread": thread, "model": thread["model"], "modelProvider": thread["modelProvider"]})
			}
		case "thread/name/set":
			h.mu.Lock()
			t := h.threadLocked(tid)
			if t != nil {
				t["name"] = p["name"]
			}
			h.mu.Unlock()
			c.reply(id, map[string]any{})
			c.event("thread/name/updated", map[string]any{"threadId": tid, "threadName": p["name"]})
		case "thread/archive":
			h.mu.Lock()
			t := h.threadLocked(tid)
			if t != nil {
				t["archived"] = true
			}
			h.mu.Unlock()
			c.reply(id, map[string]any{})
			c.event("thread/archived", map[string]any{"threadId": tid})
		case "turn/interrupt":
			h.mu.Lock()
			cancel := h.active[tid]
			h.mu.Unlock()
			if cancel != nil {
				cancel()
			}
			c.reply(id, map[string]any{})
		case "turn/start":
			h.mu.Lock()
			t := h.threadLocked(tid)
			if t == nil {
				h.mu.Unlock()
				c.rpcError(id, "thread not found")
				continue
			}
			if h.active[tid] != nil {
				h.mu.Unlock()
				c.rpcError(id, "turn already active")
				continue
			}
			turnCtx, stop := context.WithCancel(ctx)
			h.active[tid] = stop
			text := ""
			if input, ok := p["input"].([]any); ok {
				for _, item := range input {
					if v, ok := item.(map[string]any); ok {
						if s, ok := v["text"].(string); ok {
							text += s
						}
					}
				}
			}
			turn := map[string]any{"id": fmt.Sprintf("turn_%d", h.next.Add(1)), "status": "inProgress", "items": []any{map[string]any{"id": fmt.Sprintf("user_%d", h.next.Add(1)), "type": "userMessage", "content": []any{map[string]any{"type": "text", "text": text}}}}}
			t["turns"] = append(t["turns"].([]any), turn)
			t["status"] = map[string]any{"type": "active"}
			t["updatedAt"] = time.Now().Unix()
			if t["preview"] == "" {
				title := []rune(text)
				if len(title) > 28 {
					title = title[:28]
				}
				t["name"] = string(title)
				t["preview"] = text
			}
			copy := clone(turn)
			name := t["name"]
			h.mu.Unlock()
			c.reply(id, map[string]any{"turn": copy})
			c.event("turn/started", map[string]any{"threadId": tid, "turn": copy})
			c.event("thread/name/updated", map[string]any{"threadId": tid, "threadName": name})
			go c.runTurn(turnCtx, tid, turn, text)
		default:
			c.rpcError(id, "mock method not implemented: "+msg.Method)
		}
	}
}
func (c *mockConn) rpcError(id json.RawMessage, msg string) {
	if len(id) > 0 {
		_ = c.write(map[string]any{"id": id, "error": map[string]any{"code": -32601, "message": msg}})
	}
}
func (c *mockConn) runTurn(ctx context.Context, tid string, turn map[string]any, text string) {
	h := c.hub
	turnID := turn["id"]
	status := "completed"
	defer func() {
		h.mu.Lock()
		if ctx.Err() != nil {
			status = "interrupted"
		}
		turn["status"] = status
		delete(h.active, tid)
		if t := h.threadLocked(tid); t != nil {
			t["status"] = map[string]any{"type": "idle"}
		}
		copy := clone(turn)
		h.mu.Unlock()
		c.event("turn/completed", map[string]any{"threadId": tid, "turn": copy})
	}()
	reply := "已收到你的任务：**" + text + "**\n\n当前是 CodexRM 演示模式。这条回复通过本地 Agent 和服务器实时转发，未调用真实模型。\n\n连接真实 Codex 后，你可以在这里：\n- 让 Codex 阅读、修改本地项目\n- 查看命令输出和文件变更\n- 处理审批，随时停止任务\n\n```sh\ncodexrm-agent --server http://your-server:8080 --cwd /your/project\n```"
	if strings.Contains(text, "审批") || strings.Contains(text, "approval") {
		count := 1
		if strings.Contains(text, "multi approval") {
			count = 3
		}
		channels := make([]chan json.RawMessage, count)
		for i := range channels {
			key := fmt.Sprintf("mock-approval-%d", h.next.Add(1))
			ch := make(chan json.RawMessage, 1)
			channels[i] = ch
			c.pendingMu.Lock()
			c.pending[string(mustMockJSON(key))] = ch
			c.pendingMu.Unlock()
			defer func() { c.pendingMu.Lock(); delete(c.pending, string(mustMockJSON(key))); c.pendingMu.Unlock() }()
			_ = c.write(map[string]any{"id": key, "method": "item/commandExecution/requestApproval", "params": map[string]any{"threadId": tid, "turnId": turnID, "itemId": "mock-command", "command": "go test ./...", "cwd": "mock-workspace", "reason": "演示审批流程，不会执行真实命令。"}})
		}
		decisions := []string{}
		for _, ch := range channels {
			select {
			case <-ctx.Done():
				return
			case result := <-ch:
				var r struct {
					Decision string `json:"decision"`
				}
				_ = json.Unmarshal(result, &r)
				decisions = append(decisions, r.Decision)
			}
		}
		reply = "审批结果：**" + strings.Join(decisions, ", ") + "**。演示流程已完成，没有执行真实命令。"
	}
	itemID := fmt.Sprintf("item_%d", h.next.Add(1))
	item := map[string]any{"id": itemID, "type": "agentMessage", "text": ""}
	h.mu.Lock()
	turn["items"] = append(turn["items"].([]any), item)
	h.mu.Unlock()
	c.event("item/started", map[string]any{"threadId": tid, "turnId": turnID, "item": clone(item)})
	runes := []rune(reply)
	for i := 0; i < len(runes); i += 6 {
		select {
		case <-ctx.Done():
			return
		case <-time.After(35 * time.Millisecond):
		}
		end := i + 6
		if end > len(runes) {
			end = len(runes)
		}
		delta := string(runes[i:end])
		h.mu.Lock()
		item["text"] = item["text"].(string) + delta
		h.mu.Unlock()
		c.event("item/agentMessage/delta", map[string]any{"threadId": tid, "turnId": turnID, "itemId": itemID, "delta": delta})
	}
	h.mu.Lock()
	copy := clone(item)
	h.mu.Unlock()
	c.event("item/completed", map[string]any{"threadId": tid, "turnId": turnID, "item": copy})
}
func mustMockJSON(v any) json.RawMessage { b, _ := json.Marshal(v); return b }
