package agent

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRestoreModelFromActualRollout(t *testing.T) {
	path := filepath.Join(t.TempDir(), "rollout.jsonl")
	data := "{\"type\":\"turn_context\",\"payload\":{\"model\":\"old-model\",\"effort\":\"low\"}}\n" +
		"{\"type\":\"response_item\",\"payload\":{\"model\":\"ignore-this-text\"}}\n" +
		"{\"type\":\"turn_context\",\"payload\":{\"model\":\"grok-latest\",\"effort\":\"high\"}}\n"
	if err := os.WriteFile(path, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	a := &Agent{cfg: Config{DataDir: t.TempDir()}}
	model := a.restoreThreadModel("thread", "ccswitch_example", path)
	if model.Model != "grok-latest" || model.Effort != "high" || model.Provider != "ccswitch_example" {
		t.Fatalf("model not restored: %#v", model)
	}
	if err := a.rememberThreadModel("thread/start", map[string]any{"model": "saved-model", "modelProvider": "ccswitch_example"}, mustJSON(map[string]any{"thread": map[string]any{"id": "thread"}})); err != nil {
		t.Fatal(err)
	}
	model = a.restoreThreadModel("thread", "ccswitch_example", path)
	if model.Model != "saved-model" {
		t.Fatal("new local selection was overwritten by old rollout")
	}
	raw, _ := os.ReadFile(filepath.Join(a.cfg.DataDir, "thread-models.json"))
	if strings.Contains(string(raw), "config") || strings.Contains(string(raw), "token") {
		t.Fatal("routing file contains connection settings")
	}
}
