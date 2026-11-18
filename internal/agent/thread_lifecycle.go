package agent

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/codexrm/codexrm/internal/appserver"
)

// Calls and idle release share a per-thread lock. Never hold it in the reader
// callback: an RPC needs that reader to deliver its response.
func (a *Agent) threadQueue(threadID string) *messageQueue {
	a.queueMu.Lock()
	defer a.queueMu.Unlock()
	return a.queueLocked(threadID)
}

func (a *Agent) callThread(ctx context.Context, app *appserver.Client, owner, method string, params map[string]any) (json.RawMessage, error) {
	if params == nil {
		params = map[string]any{}
	}
	id, _ := params["threadId"].(string)
	var q *messageQueue
	if id != "" {
		q = a.threadQueue(id)
		q.RPCMu.Lock()
		defer q.RPCMu.Unlock()
	}
	if id != "" && (method == "thread/resume" || method == "turn/start") {
		defer func() {
			a.queueMu.Lock()
			lifetime := a.queueCtx
			a.queueMu.Unlock()
			if lifetime != nil {
				go a.releaseIdleThread(lifetime, app, id)
			}
		}()
	}
	// Keep caller-supplied runtime settings, before provider preparation adds
	// credentials. Every automatic resume reloads current provider credentials.
	settings := resumeSettings(params)
	if method == "turn/start" && q != nil {
		if err := a.resumeDetachedThread(ctx, app, id, q); err != nil {
			return nil, err
		}
	}
	prepareUserInput(method, params)
	if err := a.prepareProvider(ctx, app, method, params); err != nil {
		return nil, err
	}
	if method == "turn/start" && id != "" {
		a.mu.Lock()
		a.owners[id] = owner
		a.mu.Unlock()
	}
	raw, err := app.Call(ctx, method, params)
	if err == nil {
		a.recordStartedTurn(method, params, raw)
		if saveErr := a.rememberThreadModel(method, params, raw); saveErr != nil {
			log.Printf("unable to save thread model selection: %v", saveErr)
		}
		if method == "thread/start" || method == "thread/resume" || method == "thread/fork" {
			var out struct {
				Thread struct {
					ID        string
					Ephemeral bool
					Status    struct{ Type string }
					Turns     []struct{ ID, Status string }
				}
			}
			if json.Unmarshal(raw, &out) == nil && out.Thread.ID != "" {
				id = out.Thread.ID
				a.queueMu.Lock()
				loaded := a.queueLocked(id)
				loaded.Loaded, loaded.Detached = true, false
				loaded.Ephemeral = out.Thread.Ephemeral
				if loaded.ResumeParams == nil {
					loaded.ResumeParams = map[string]any{}
				}
				for key, value := range settings {
					loaded.ResumeParams[key] = value
				}
				// A resume can join an existing running thread. Do not release it
				// simply because its turn/started notification predates our join.
				if out.Thread.Status.Type == "active" {
					for _, turn := range out.Thread.Turns {
						if turn.Status == "inProgress" && loaded.LastCompleted != turn.ID {
							loaded.ActiveTurn = turn.ID
						}
					}
				}
				a.queueMu.Unlock()
				a.mu.Lock()
				a.owners[id] = owner
				a.mu.Unlock()
			}
		}
		if method == "turn/start" && q != nil {
			a.queueMu.Lock()
			q.Loaded = true
			a.queueMu.Unlock()
		}
		if method == "thread/start" || method == "thread/resume" || method == "thread/fork" || method == "turn/start" {
			a.publishThreadState("loaded", id)
		}
	}
	return raw, err
}

func resumeSettings(params map[string]any) map[string]any {
	settings := map[string]any{}
	for _, key := range []string{"model", "codexrmProvider", "serviceTier", "cwd", "runtimeWorkspaceRoots", "approvalPolicy", "approvalsReviewer", "sandbox", "permissions", "config", "baseInstructions", "developerInstructions"} {
		if value, ok := params[key]; ok {
			settings[key] = value
		}
	}
	// Maps such as config will be mutated while preparing a provider.
	var copy map[string]any
	_ = json.Unmarshal(mustJSON(settings), &copy)
	return copy
}

// The caller holds q.RPCMu. The user's next message restores the thread from
// disk, including any turns completed by the desktop since our last release.
func (a *Agent) resumeDetachedThread(ctx context.Context, app *appserver.Client, id string, q *messageQueue) error {
	a.queueMu.Lock()
	detached := q.Detached
	params := resumeSettings(q.ResumeParams)
	a.queueMu.Unlock()
	if !detached {
		return nil
	}
	params["threadId"], params["excludeTurns"] = id, true
	prepareUserInput("thread/resume", params)
	if err := a.prepareProvider(ctx, app, "thread/resume", params); err != nil {
		return err
	}
	if _, err := app.Call(ctx, "thread/resume", params); err != nil {
		return err
	}
	a.queueMu.Lock()
	q.Loaded, q.Detached = true, false
	// Snapshots taken while detached may have observed a desktop-owned turn.
	q.Known, q.ActiveTurn = false, ""
	a.queueMu.Unlock()
	return nil
}

func (a *Agent) releaseIdleThread(ctx context.Context, app *appserver.Client, id string) {
	if app == nil || ctx.Err() != nil {
		return
	}
	q := a.threadQueue(id)
	q.RPCMu.Lock()
	defer q.RPCMu.Unlock()
	a.queueMu.Lock()
	busy := !q.Loaded || q.Detached || q.Ephemeral || q.ActiveTurn != "" || q.Starting || (!q.Paused && len(q.Entries) > 0)
	a.queueMu.Unlock()
	if busy || ctx.Err() != nil {
		return
	}
	// Optional questions can remain answerable after turn/completed.
	a.mu.Lock()
	for _, pending := range a.approvals {
		if pending.ThreadID == id {
			a.mu.Unlock()
			return
		}
	}
	a.mu.Unlock()
	callCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	// An excludeTurns resume can join an active thread without returning its
	// turn ID. Confirm the live state before unsubscribing in that case too.
	raw, err := app.Call(callCtx, "thread/read", map[string]any{"threadId": id, "includeTurns": false})
	if err != nil {
		log.Printf("read idle thread %s before release: %v", id, err)
		return
	}
	var snapshot struct {
		Thread struct{ Status struct{ Type string } }
	}
	if json.Unmarshal(raw, &snapshot) != nil || snapshot.Thread.Status.Type != "idle" {
		return
	}
	a.queueMu.Lock()
	unloaded := make(chan struct{})
	q.Unloaded = unloaded
	a.queueMu.Unlock()
	raw, err = app.Call(callCtx, "thread/unsubscribe", map[string]any{"threadId": id})
	if err != nil {
		log.Printf("release idle thread %s: %v", id, err)
		return
	}
	var result struct{ Status string }
	_ = json.Unmarshal(raw, &result)
	if result.Status == "unsubscribed" && a.cfg.AppServerURL == "" {
		// Unsubscribe acknowledges before shutdown finishes. Keep the gate
		// until thread/closed so an immediate next message can safely resume.
		select {
		case <-unloaded:
		case <-callCtx.Done():
			log.Printf("wait for idle thread %s to unload: %v", id, callCtx.Err())
		}
	}
	a.queueMu.Lock()
	q.Detached, q.Loaded, q.Known = true, false, false
	a.queueMu.Unlock()
	a.mu.Lock()
	delete(a.owners, id)
	a.mu.Unlock()
	a.publishThreadState("detached", id)
}
