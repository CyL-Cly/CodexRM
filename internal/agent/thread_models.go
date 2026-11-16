package agent

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Persist only routing metadata. Provider credentials are always reread locally.
type threadModel struct {
	Provider string `json:"provider"`
	Model    string `json:"model"`
	Effort   string `json:"effort,omitempty"`
}

func (a *Agent) savedThreadModels() (map[string]threadModel, error) {
	items := map[string]threadModel{}
	if a.cfg.DataDir == "" {
		return items, nil
	}
	data, err := os.ReadFile(filepath.Join(a.cfg.DataDir, "thread-models.json"))
	if errors.Is(err, os.ErrNotExist) {
		return items, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &items); err != nil {
		return nil, err
	}
	if items == nil {
		items = map[string]threadModel{}
	}
	return items, nil
}

func (a *Agent) restoreThreadModel(id, provider, path string) threadModel {
	a.mu.Lock()
	items, _ := a.savedThreadModels()
	model := items[id]
	a.mu.Unlock()
	if model.Provider == provider && model.Model != "" {
		if model.Effort == "" {
			if actual, err := rolloutModel(path); err == nil {
				model.Effort = actual.Effort
			}
		}
		return model
	}
	model, _ = rolloutModel(path)
	model.Provider = provider
	return model
}

func (a *Agent) rememberThreadModel(method string, params map[string]any, raw json.RawMessage) error {
	if a.cfg.DataDir == "" || (method != "thread/start" && method != "thread/resume" && method != "turn/start") {
		return nil
	}
	var result struct {
		Model    string `json:"model"`
		Provider string `json:"modelProvider"`
		Effort   string `json:"reasoningEffort"`
		Thread   struct {
			ID       string `json:"id"`
			Provider string `json:"modelProvider"`
		} `json:"thread"`
	}
	if err := json.Unmarshal(raw, &result); err != nil {
		return err
	}
	id := result.Thread.ID
	if id == "" {
		id, _ = params["threadId"].(string)
	}
	if id == "" {
		return nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	items, err := a.savedThreadModels()
	if err != nil {
		return err
	}
	model := items[id]
	if result.Model != "" {
		model.Model = result.Model
	}
	if result.Provider != "" {
		model.Provider = result.Provider
	}
	if result.Thread.Provider != "" {
		model.Provider = result.Thread.Provider
	}
	if selected, _ := params["model"].(string); selected != "" {
		model.Model = selected
	}
	if selected, _ := params["modelProvider"].(string); selected != "" {
		model.Provider = selected
	}
	if result.Effort != "" {
		model.Effort = result.Effort
	}
	if effort, _ := params["effort"].(string); effort != "" {
		model.Effort = effort
	}
	items[id] = model
	data, err := json.MarshalIndent(items, "", "  ")
	if err != nil {
		return err
	}
	file := filepath.Join(a.cfg.DataDir, "thread-models.json")
	if err := os.WriteFile(file+".tmp", data, 0600); err != nil {
		return err
	}
	return os.Rename(file+".tmp", file)
}

// Older CodexRM sessions have no local routing record. Recover the latest actual
// turn's model instead of silently applying the app-server's global default.
func rolloutModel(path string) (threadModel, error) {
	var model threadModel
	if !strings.HasSuffix(path, ".jsonl") {
		return model, errors.New("no rollout")
	}
	f, err := os.Open(path)
	if err != nil {
		return model, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return model, err
	}
	const tailBytes int64 = 8 << 20
	offset := max(int64(0), info.Size()-tailBytes)
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return model, err
	}
	scanner := bufio.NewScanner(io.LimitReader(f, info.Size()-offset))
	scanner.Buffer(make([]byte, 64*1024), int(tailBytes)+1)
	if offset > 0 {
		scanner.Scan()
	}
	for scanner.Scan() {
		var record struct {
			Type    string `json:"type"`
			Payload struct {
				Model  string `json:"model"`
				Effort string `json:"effort"`
			} `json:"payload"`
		}
		if json.Unmarshal(scanner.Bytes(), &record) == nil && record.Type == "turn_context" && record.Payload.Model != "" {
			model.Model, model.Effort = record.Payload.Model, record.Payload.Effort
		}
	}
	return model, scanner.Err()
}
