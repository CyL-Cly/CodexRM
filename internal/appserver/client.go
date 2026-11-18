package appserver

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/gorilla/websocket"
	"os"
	"os/exec"
	"sync"
	"sync/atomic"
	"time"
)

// Client multiplexes RPC calls and server-initiated approvals over stdio or WS.
type managedProcess interface {
	Kill() error
	Close() error
}

type Client struct {
	mu       sync.Mutex
	writeMu  sync.Mutex
	once     sync.Once
	nextID   atomic.Int64
	pending  map[string]chan json.RawMessage
	notify   func(json.RawMessage)
	closed   chan struct{}
	read     func() ([]byte, error)
	write    func([]byte) error
	shutdown func() error
}
type RPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

func (e *RPCError) Error() string { return e.Message }
func newClient() *Client {
	return &Client{pending: map[string]chan json.RawMessage{}, closed: make(chan struct{})}
}
func Dial(ctx context.Context, rawURL string, token ...string) (*Client, error) {
	headers := map[string][]string{}
	if len(token) > 0 && token[0] != "" {
		headers["Authorization"] = []string{"Bearer " + token[0]}
	}
	conn, _, err := (&websocket.Dialer{HandshakeTimeout: 10 * time.Second}).DialContext(ctx, rawURL, headers)
	if err != nil {
		return nil, fmt.Errorf("dial app-server: %w", err)
	}
	conn.SetReadLimit(32 << 20)
	c := newClient()
	c.read = func() ([]byte, error) { _, b, e := conn.ReadMessage(); return b, e }
	c.write = func(b []byte) error {
		_ = conn.SetWriteDeadline(time.Now().Add(15 * time.Second))
		return conn.WriteMessage(websocket.TextMessage, b)
	}
	c.shutdown = conn.Close
	go c.readLoop()
	return c, nil
}

// Launch inherits the local login and config. Relay never receives login secrets.
func Launch(ctx context.Context, executable, cwd string) (*Client, error) {
	executable, err := resolveCodexExecutable(executable)
	if err != nil {
		return nil, err
	}
	// Release the durable writer as soon as the Agent unsubscribes an idle
	// thread, so the desktop app can resume it immediately. This override is
	// private to this process and does not change the user's Codex config.
	cmd := exec.CommandContext(ctx, executable, "-c", "thread_unload_delay_secs=0", "app-server", "--listen", "stdio://")
	hideChildProcess(cmd)
	cmd.WaitDelay = 5 * time.Second
	cmd.Dir = cwd
	cmd.Stderr = os.Stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return nil, err
	}
	process, err := startManagedProcess(cmd)
	if err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		return nil, fmt.Errorf("start Codex (install Codex desktop or pass --codex): %w", err)
	}
	scanner := bufio.NewScanner(stdout)
	scanner.Buffer(make([]byte, 64*1024), 32<<20)
	c := newClient()
	c.read = func() ([]byte, error) {
		if scanner.Scan() {
			return append([]byte(nil), scanner.Bytes()...), nil
		}
		if e := scanner.Err(); e != nil {
			return nil, e
		}
		return nil, errors.New("Codex process exited")
	}
	c.write = func(b []byte) error { _, e := stdin.Write(append(b, '\n')); return e }
	c.shutdown = func() error { _ = stdin.Close(); return process.Kill() }
	go func() { _ = cmd.Wait(); _ = c.Close(); _ = process.Close() }()
	go c.readLoop()
	return c, nil
}
func (c *Client) Done() <-chan struct{} { return c.closed }
func (c *Client) Close() error {
	var err error
	c.once.Do(func() { close(c.closed); err = c.shutdown() })
	return err
}
func (c *Client) OnNotification(fn func(json.RawMessage)) { c.mu.Lock(); c.notify = fn; c.mu.Unlock() }
func (c *Client) readLoop() {
	defer c.Close()
	for {
		data, err := c.read()
		if err != nil {
			return
		}
		var msg struct {
			ID     json.RawMessage `json:"id"`
			Method string          `json:"method"`
		}
		if json.Unmarshal(data, &msg) != nil {
			continue
		}
		c.mu.Lock()
		ch := c.pending[string(msg.ID)]
		fn := c.notify
		c.mu.Unlock()
		if msg.Method == "" && ch != nil {
			select {
			case ch <- json.RawMessage(data):
			default:
			}
			continue
		}
		if msg.Method != "" && fn != nil {
			fn(json.RawMessage(data))
		}
	}
}
func (c *Client) WriteRaw(raw json.RawMessage) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	select {
	case <-c.closed:
		return errors.New("app-server connection closed")
	default:
	}
	return c.write(raw)
}
func (c *Client) Notify(method string, params any) error {
	b, e := json.Marshal(map[string]any{"method": method, "params": params})
	if e != nil {
		return e
	}
	return c.WriteRaw(b)
}
func (c *Client) Call(ctx context.Context, method string, params any) (json.RawMessage, error) {
	id := fmt.Sprint(c.nextID.Add(1))
	ch := make(chan json.RawMessage, 1)
	c.mu.Lock()
	c.pending[id] = ch
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.pending, id); c.mu.Unlock() }()
	raw, err := json.Marshal(map[string]any{"id": json.Number(id), "method": method, "params": params})
	if err != nil {
		return nil, err
	}
	if err = c.WriteRaw(raw); err != nil {
		return nil, err
	}
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-c.closed:
		return nil, errors.New("app-server connection closed")
	case raw := <-ch:
		var msg struct {
			Result json.RawMessage `json:"result"`
			Error  *RPCError       `json:"error"`
		}
		if err := json.Unmarshal(raw, &msg); err != nil {
			return nil, err
		}
		if msg.Error != nil {
			return nil, msg.Error
		}
		return msg.Result, nil
	}
}
func (c *Client) Initialize(ctx context.Context, name, version string) (json.RawMessage, error) {
	result, err := c.Call(ctx, "initialize", map[string]any{"clientInfo": map[string]any{"name": name, "title": "CodexRM", "version": version}, "capabilities": map[string]any{"experimentalApi": true}})
	if err != nil {
		return nil, err
	}
	return result, c.Notify("initialized", nil)
}
