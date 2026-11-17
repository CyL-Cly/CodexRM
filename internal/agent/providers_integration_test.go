package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/providers"
)

// Opt in with a local Codex CLI. All credentials, endpoints and state are test-only.
func TestProviderRoutingWithLocalCodex(t *testing.T) {
	executable := os.Getenv("CODEXRM_TEST_CODEX")
	if executable == "" {
		t.Skip("set CODEXRM_TEST_CODEX to test the local Codex app-server")
	}
	t.Setenv("CODEX_HOME", t.TempDir())
	t.Setenv("RUST_LOG", "error")
	type request struct{ Path, Token, Model string }
	requests := make(chan request, 20)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		var body struct{ Model string }
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid test request", 400)
			return
		}
		requests <- request{r.URL.Path, r.Header.Get("Authorization"), body.Model}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp-test\"}}\n\n"+
			"event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-test\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":0}}}\n\n")
	}))
	defer upstream.Close()
	config := fmt.Sprintf("model = 'same-model'\nmodel_provider = 'baseline'\n[model_providers.baseline]\nname = 'Baseline'\nbase_url = %q\nwire_api = 'responses'\nrequires_openai_auth = false\n", upstream.URL+"/baseline")
	if err := os.WriteFile(filepath.Join(os.Getenv("CODEX_HOME"), "config.toml"), []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	path := providerFixture(t)
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	update := func(id, token string) {
		t.Helper()
		config := fmt.Sprintf("model = 'same-model'\nmodel_provider = 'custom'\n[model_providers.custom]\nname = 'Same'\nbase_url = %q\nwire_api = 'responses'\nrequires_openai_auth = true\n", upstream.URL+"/"+id)
		raw := mustJSON(map[string]any{"config": config, "auth": map[string]any{"OPENAI_API_KEY": token}})
		if _, err := db.Exec("UPDATE providers SET settings_config = ? WHERE id = ?", string(raw), id); err != nil {
			t.Fatal(err)
		}
	}
	update("1", "test-key-one")
	update("2", "test-key-two")
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	items, err := providers.Load(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	type completion struct {
		ThreadID string                  `json:"threadId"`
		Turn     struct{ Status string } `json:"turn"`
	}
	completed := make(chan completion, 20)
	cwd := t.TempDir()
	launch := func() *appserver.Client {
		t.Helper()
		app, err := appserver.Launch(ctx, executable, cwd)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = app.Close() })
		app.OnNotification(func(raw json.RawMessage) {
			var event struct {
				Method string     `json:"method"`
				Params completion `json:"params"`
			}
			if json.Unmarshal(raw, &event) == nil && event.Method == "turn/completed" {
				completed <- event.Params
			}
		})
		if _, err := app.Initialize(ctx, "codexrm-provider-test", "0.0.0"); err != nil {
			t.Fatal(err)
		}
		return app
	}
	a := &Agent{cfg: Config{CCSwitchDB: path, DataDir: t.TempDir()}}
	app := launch()
	threads := []string{}
	for _, provider := range items {
		params := map[string]any{"cwd": cwd, "codexrmProvider": provider.ID, "approvalPolicy": "never", "sandbox": "read-only"}
		if err := a.prepareProvider(ctx, app, "thread/start", params); err != nil {
			t.Fatal(err)
		}
		raw, err := app.Call(ctx, "thread/start", params)
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Thread struct{ ID string } `json:"thread"`
		}
		if err := json.Unmarshal(raw, &result); err != nil || result.Thread.ID == "" {
			t.Fatalf("start did not return a thread: %v", err)
		}
		threads = append(threads, result.Thread.ID)
		if err := a.rememberThreadModel("thread/start", params, raw); err != nil {
			t.Fatal(err)
		}
	}
	turn := func(index int, token string) {
		t.Helper()
		params := map[string]any{"threadId": threads[index], "model": "same-model", "effort": "low", "input": []any{map[string]any{"type": "text", "text": "Return immediately. Do not use tools."}}}
		raw, err := app.Call(ctx, "turn/start", params)
		if err != nil {
			t.Fatal(err)
		}
		select {
		case got := <-requests:
			if got.Path != fmt.Sprintf("/%d/responses", index+1) || got.Token != "Bearer "+token || got.Model != "same-model" {
				t.Fatalf("wrong supplier request: %#v", got)
			}
		case <-ctx.Done():
			t.Fatal("Codex did not reach the local supplier")
		}
		select {
		case result := <-completed:
			if result.ThreadID != threads[index] || result.Turn.Status != "completed" {
				t.Fatalf("turn did not complete: %#v", result)
			}
		case <-ctx.Done():
			t.Fatal("Codex did not complete the test turn")
		}
		if err := a.rememberThreadModel("turn/start", params, raw); err != nil {
			t.Fatal(err)
		}
	}
	turn(0, "test-key-one")
	turn(1, "test-key-two")
	turn(0, "test-key-one")
	_ = app.Close()
	update("1", "rotated-test-key-one")
	app = launch()
	a = &Agent{cfg: a.cfg}
	for i := range items {
		params := map[string]any{"threadId": threads[i]}
		if err := a.prepareProvider(ctx, app, "thread/resume", params); err != nil {
			t.Fatal(err)
		}
		if params["modelProvider"] != items[i].RuntimeID() || params["model"] != "same-model" {
			t.Fatal("cold resume lost the original supplier or model")
		}
		if params["config"].(map[string]any)["model_reasoning_effort"] != "low" {
			t.Fatal("cold resume lost the original reasoning effort")
		}
		if _, err := app.Call(ctx, "thread/resume", params); err != nil {
			t.Fatal(err)
		}
	}
	turn(0, "rotated-test-key-one")
	turn(1, "test-key-two")
	if _, err := db.Exec("DELETE FROM providers WHERE id = '1'"); err != nil {
		t.Fatal(err)
	}
	if err := a.prepareProvider(ctx, app, "thread/resume", map[string]any{"threadId": threads[0]}); err == nil {
		t.Fatal("missing supplier silently fell back on resume")
	}
}
