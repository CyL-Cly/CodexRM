package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"path/filepath"
	"testing"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/providers"
)

func providerFixture(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "providers.db")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err = db.Exec("CREATE TABLE providers (id TEXT, app_type TEXT, name TEXT, settings_config TEXT, sort_index INTEGER)"); err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 2; i++ {
		raw := mustJSON(map[string]any{"auth": map[string]any{"OPENAI_API_KEY": fmt.Sprintf("secret-%d", i)}, "config": fmt.Sprintf("model = 'same-model'\nmodel_provider = 'custom'\n[model_providers.custom]\nname = 'Same'\nbase_url = 'https://example.test/v1'\nwire_api = 'responses'\n")})
		if _, err = db.Exec("INSERT INTO providers VALUES (?, 'codex', 'Same', ?, ?)", fmt.Sprint(i), string(raw), i); err != nil {
			t.Fatal(err)
		}
	}
	return path
}

func TestThreadProviderIsolationAndResume(t *testing.T) {
	ctx := context.Background()
	path := providerFixture(t)
	items, err := providers.Load(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	mock, err := appserver.StartMock("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer mock.Close()
	app, err := appserver.Dial(ctx, "ws://"+mock.Addr)
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	a := &Agent{cfg: Config{CCSwitchDB: path, DataDir: t.TempDir()}}
	for i, provider := range items {
		params := map[string]any{"cwd": t.TempDir(), "codexrmProvider": provider.ID, "config": map[string]any{"model_reasoning_effort": "low"}}
		if err := a.prepareProvider(ctx, app, "thread/start", params); err != nil {
			t.Fatal(err)
		}
		config := params["config"].(map[string]any)
		connection := config["model_providers."+provider.ID].(map[string]any)
		if params["codexrmProvider"] != nil || params["modelProvider"] != provider.ID || connection["experimental_bearer_token"] != fmt.Sprintf("secret-%d", i+1) || config["model_reasoning_effort"] != "low" {
			t.Fatal("thread uses incorrect provider or loses other config overrides")
		}
		raw, err := app.Call(ctx, "thread/start", params)
		if err != nil {
			t.Fatal(err)
		}
		var result struct {
			Thread struct {
				ID string `json:"id"`
			} `json:"thread"`
		}
		if err := json.Unmarshal(raw, &result); err != nil {
			t.Fatal(err)
		}
		if err := a.rememberThreadModel("thread/start", params, raw); err != nil {
			t.Fatal(err)
		}
		if err := a.rememberThreadModel("turn/start", map[string]any{"threadId": result.Thread.ID, "model": "alternate-model", "effort": "max"}, mustJSON(map[string]any{"turn": map[string]any{"id": "turn"}})); err != nil {
			t.Fatal(err)
		}
		// Simulate a fresh agent: the rollout provider ID must restore the correct key.
		b := &Agent{cfg: a.cfg}
		resume := map[string]any{"threadId": result.Thread.ID}
		if err := b.prepareProvider(ctx, app, "thread/resume", resume); err != nil {
			t.Fatal(err)
		}
		restored := resume["config"].(map[string]any)["model_providers."+provider.ID].(map[string]any)
		if restored["experimental_bearer_token"] != connection["experimental_bearer_token"] || resume["modelProvider"] != provider.ID {
			t.Fatal("resume changed provider credentials")
		}
		if resume["model"] != "alternate-model" || resume["config"].(map[string]any)["model_reasoning_effort"] != "max" {
			t.Fatal("resume lost the session model or reasoning effort")
		}
	}
	if err := a.prepareProvider(ctx, app, "thread/start", map[string]any{"codexrmProvider": "ccswitch_removed"}); err == nil {
		t.Fatal("missing provider silently fell back to another supplier")
	}
}
