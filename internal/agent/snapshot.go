package agent

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"strings"

	"github.com/codexrm/codexrm/internal/appserver"
)

// A separate app-server normalizes unloaded in-progress turns to interrupted.
// Keep its snapshot intact and attach durable lifecycle evidence for the UI.
func (a *Agent) threadSnapshot(ctx context.Context, app *appserver.Client, params map[string]any) (json.RawMessage, error) {
	id, _ := params["threadId"].(string)
	if id == "" {
		return nil, &appserver.RPCError{Code: -32602, Message: "threadId is required"}
	}
	raw, err := app.Call(ctx, "thread/read", map[string]any{"threadId": id, "includeTurns": false})
	if err != nil {
		return nil, err
	}
	var result struct {
		Thread map[string]any `json:"thread"`
	}
	if err = json.Unmarshal(raw, &result); err != nil {
		return nil, err
	}
	thread := result.Thread
	if thread == nil {
		return nil, errors.New("thread/read returned no thread")
	}
	var cursor any
	if thread["historyMode"] == "paginated" {
		raw, err = app.Call(ctx, "thread/turns/list", map[string]any{"threadId": id, "limit": 30, "sortDirection": "desc", "itemsView": "full"})
		if err != nil {
			return nil, err
		}
		var page struct {
			Data       []any `json:"data"`
			NextCursor any   `json:"nextCursor"`
		}
		if err = json.Unmarshal(raw, &page); err != nil {
			return nil, err
		}
		for i, j := 0, len(page.Data)-1; i < j; i, j = i+1, j-1 {
			page.Data[i], page.Data[j] = page.Data[j], page.Data[i]
		}
		thread["turns"], cursor = page.Data, page.NextCursor
	} else {
		raw, err = app.Call(ctx, "thread/read", map[string]any{"threadId": id, "includeTurns": true})
		if err != nil {
			return nil, err
		}
		if err = json.Unmarshal(raw, &result); err != nil {
			return nil, err
		}
		thread = result.Thread
	}
	// Paths from a remote app-server belong to that machine, never the agent.
	if a.cfg.AppServerURL == "" {
		path, _ := thread["path"].(string)
		if states, err := rolloutTurnStates(path); err == nil {
			thread["codexrmTurnStates"] = states
		}
	}
	status, _ := thread["status"].(map[string]any)
	statusType, _ := status["type"].(string)
	return mustJSON(map[string]any{"thread": thread, "nextCursor": cursor, "active": statusType == "active"}), nil
}

// Read a bounded tail. Missing evidence stays unknown; it never proves a stop.
func rolloutTurnStates(path string) (map[string]string, error) {
	if !strings.HasSuffix(path, ".jsonl") {
		return nil, errors.New("no plain rollout available")
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	const tailBytes int64 = 2 << 20
	offset := max(int64(0), info.Size()-tailBytes)
	if _, err = f.Seek(offset, io.SeekStart); err != nil {
		return nil, err
	}
	scanner := bufio.NewScanner(io.LimitReader(f, info.Size()-offset))
	scanner.Buffer(make([]byte, 64*1024), int(tailBytes)+1)
	if offset > 0 {
		scanner.Scan() // The first line may begin in the middle of a JSON record.
	}
	states := map[string]string{}
	current := ""
	for scanner.Scan() {
		var record struct {
			Type    string `json:"type"`
			Payload struct {
				Type   string `json:"type"`
				TurnID string `json:"turn_id"`
			} `json:"payload"`
		}
		if json.Unmarshal(scanner.Bytes(), &record) != nil || record.Type != "event_msg" {
			continue
		}
		p := record.Payload
		id := p.TurnID
		if id == "" {
			id = current
		}
		if id == "" {
			continue
		}
		switch p.Type {
		case "task_started", "turn_started":
			current, states[id] = id, "inProgress"
		case "task_complete", "turn_complete":
			states[id] = "completed"
		case "turn_aborted":
			states[id] = "interrupted"
		}
	}
	return states, scanner.Err()
}
