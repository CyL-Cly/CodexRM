// Package providers reads local provider settings without changing the active CLI login.
package providers

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/pelletier/go-toml/v2"
	_ "modernc.org/sqlite"
)

type Model struct {
	ID                        string            `json:"id"`
	Model                     string            `json:"model"`
	DisplayName               string            `json:"displayName"`
	IsDefault                 bool              `json:"isDefault"`
	SupportedReasoningEfforts []ReasoningEffort `json:"supportedReasoningEfforts"`
	DefaultReasoningEffort    string            `json:"defaultReasoningEffort,omitempty"`
}

type ReasoningEffort struct {
	ReasoningEffort string `json:"reasoningEffort"`
	Description     string `json:"description"`
}

// Only these public fields may cross the relay. Connection settings stay local.
type Provider struct {
	ID           string  `json:"id"`
	Name         string  `json:"name"`
	Source       string  `json:"source"`
	DefaultModel string  `json:"defaultModel,omitempty"`
	Models       []Model `json:"models"`
	Warning      string  `json:"warning,omitempty"`
	config       map[string]any
	builtin      string
}

func (p Provider) RuntimeID() string {
	if p.builtin != "" {
		return p.builtin
	}
	return p.ID
}

// Overrides are sent over local app-server stdin, never written into global config.
func (p Provider) Overrides() map[string]any {
	if p.builtin != "" {
		return nil
	}
	return map[string]any{"model_providers." + p.ID: p.config}
}

func (p Provider) Official() bool { return p.builtin == "openai" }

func DefaultPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, ".cc-switch", "cc-switch.db")
}

func Load(ctx context.Context, path string) ([]Provider, error) {
	if path == "" {
		path = DefaultPath()
	}
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return []Provider{}, nil
	} else if err != nil {
		return nil, fmt.Errorf("无法读取 CCSwitch 数据库")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	uriPath := filepath.ToSlash(abs)
	if !strings.HasPrefix(uriPath, "/") {
		uriPath = "/" + uriPath
	}
	u := url.URL{Scheme: "file", Path: uriPath, RawQuery: "mode=ro&_pragma=busy_timeout(3000)"}
	db, err := sql.Open("sqlite", u.String())
	if err != nil {
		return nil, fmt.Errorf("无法打开 CCSwitch 数据库")
	}
	defer db.Close()
	rows, err := db.QueryContext(ctx, "SELECT id, name, settings_config FROM providers WHERE app_type = 'codex' ORDER BY COALESCE(sort_index, 2147483647), rowid")
	if err != nil {
		return nil, fmt.Errorf("无法读取 CCSwitch 的 Codex 供应商")
	}
	defer rows.Close()
	out := []Provider{}
	for rows.Next() {
		var id, name, settings string
		if err := rows.Scan(&id, &name, &settings); err != nil {
			return nil, fmt.Errorf("无法读取 CCSwitch 供应商记录")
		}
		p, err := parse(id, name, settings)
		if err != nil {
			// A broken provider must not hide the other providers or expose config values.
			hash := sha256.Sum256([]byte(id))
			p = Provider{ID: "ccswitch_" + hex.EncodeToString(hash[:8]), Name: name, Source: "CCSwitch", Models: []Model{}, Warning: "供应商配置无效，请在 CCSwitch 中检查"}
		}
		if p.Official() && len(p.Models) == 0 {
			p.Models = officialModels(filepath.Join(filepath.Dir(abs), "codex-official-models.json"))
			if len(p.Models) > 0 {
				p.Source = "CCSwitch 官方模型缓存"
			}
		}
		out = append(out, p)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("读取 CCSwitch 供应商失败")
	}
	counts := map[string]int{}
	labels := map[string]int{}
	for _, p := range out {
		counts[p.Name]++
	}
	for i := range out {
		if counts[out[i].Name] > 1 {
			label := out[i].DefaultModel
			if label == "" {
				label = out[i].ID[len("ccswitch_"):]
			}
			out[i].Name += " · " + label
		}
		labels[out[i].Name]++
		if labels[out[i].Name] > 1 {
			out[i].Name += " · " + out[i].ID[len("ccswitch_"):len("ccswitch_")+6]
		}
	}
	return out, nil
}

func officialModels(path string) []Model {
	data, err := os.ReadFile(path)
	if err != nil {
		return []Model{}
	}
	var entries map[string]struct {
		FetchedAt int64 `json:"fetched_at"`
		Models    []struct {
			Slug   string `json:"slug"`
			Name   string `json:"display_name"`
			Levels []struct {
				Effort      string `json:"effort"`
				Description string `json:"description"`
			} `json:"supported_reasoning_levels"`
			DefaultLevel string `json:"default_reasoning_level"`
		} `json:"models"`
	}
	if json.Unmarshal(data, &entries) != nil {
		return []Model{}
	}
	models := []Model{}
	var latest int64
	for _, entry := range entries {
		if entry.FetchedAt < latest || len(entry.Models) == 0 {
			continue
		}
		latest = entry.FetchedAt
		models = []Model{}
		for _, m := range entry.Models {
			if m.Slug == "" {
				continue
			}
			model := Model{ID: m.Slug, Model: m.Slug, DisplayName: m.Name, DefaultReasoningEffort: m.DefaultLevel, SupportedReasoningEfforts: []ReasoningEffort{}}
			for _, r := range m.Levels {
				model.SupportedReasoningEfforts = append(model.SupportedReasoningEfforts, ReasoningEffort{ReasoningEffort: r.Effort, Description: r.Description})
			}
			models = append(models, model)
		}
	}
	return models
}

func parse(id, name, raw string) (Provider, error) {
	hash := sha256.Sum256([]byte(id))
	p := Provider{ID: "ccswitch_" + hex.EncodeToString(hash[:8]), Name: name, Source: "CCSwitch", Models: []Model{}}
	var settings struct {
		Auth    map[string]any `json:"auth"`
		Config  string         `json:"config"`
		Catalog struct {
			Models []struct {
				Model        string   `json:"model"`
				DisplayName  string   `json:"displayName"`
				Levels       []string `json:"reasoningLevels"`
				DefaultLevel string   `json:"defaultReasoningLevel"`
			} `json:"models"`
		} `json:"modelCatalog"`
	}
	if err := json.Unmarshal([]byte(raw), &settings); err != nil {
		return p, err
	}
	var cfg struct {
		Model     string                    `toml:"model"`
		Provider  string                    `toml:"model_provider"`
		Providers map[string]map[string]any `toml:"model_providers"`
	}
	if err := toml.Unmarshal([]byte(settings.Config), &cfg); err != nil {
		return p, err
	}
	p.DefaultModel = cfg.Model
	if cfg.Provider == "" || cfg.Provider == "openai" {
		p.builtin = "openai"
	} else {
		p.config = cfg.Providers[cfg.Provider]
		if p.config == nil {
			return p, fmt.Errorf("missing provider")
		}
		wire, _ := p.config["wire_api"].(string)
		if wire != "" && wire != "responses" {
			return p, fmt.Errorf("unsupported wire API")
		}
		if key, _ := settings.Auth["OPENAI_API_KEY"].(string); key != "" {
			// Each provider owns its bearer token even when CCSwitch calls all of them custom.
			delete(p.config, "env_key")
			p.config["experimental_bearer_token"] = key
			p.config["requires_openai_auth"] = false
		}
	}
	seen := map[string]bool{}
	for _, entry := range settings.Catalog.Models {
		if entry.Model == "" || seen[entry.Model] {
			continue
		}
		seen[entry.Model] = true
		m := Model{ID: entry.Model, Model: entry.Model, DisplayName: entry.DisplayName, IsDefault: entry.Model == cfg.Model, DefaultReasoningEffort: entry.DefaultLevel, SupportedReasoningEfforts: []ReasoningEffort{}}
		if m.DisplayName == "" {
			m.DisplayName = m.Model
		}
		for _, level := range entry.Levels {
			m.SupportedReasoningEfforts = append(m.SupportedReasoningEfforts, ReasoningEffort{ReasoningEffort: level, Description: level})
		}
		p.Models = append(p.Models, m)
	}
	if cfg.Model != "" && !seen[cfg.Model] {
		p.Models = append(p.Models, Model{ID: cfg.Model, Model: cfg.Model, DisplayName: cfg.Model, IsDefault: true, SupportedReasoningEfforts: []ReasoningEffort{}})
	}
	return p, nil
}

// Discover merges the advertised API models with configured metadata. Failure keeps
// the saved catalog usable. Each request uses only that provider's credentials.
func Discover(ctx context.Context, items []Provider) {
	var wg sync.WaitGroup
	slots := make(chan struct{}, 4)
	for i := range items {
		if items[i].Official() || items[i].Warning != "" {
			continue
		}
		wg.Add(1)
		go func(p *Provider) {
			defer wg.Done()
			select {
			case slots <- struct{}{}:
				defer func() { <-slots }()
			case <-ctx.Done():
				return
			}
			if err := p.discover(ctx); err != nil {
				p.Warning = "在线模型列表暂不可用，显示 CCSwitch 已保存的模型"
			}
		}(&items[i])
	}
	wg.Wait()
}

func (p *Provider) discover(ctx context.Context) error {
	base, _ := p.config["base_url"].(string)
	u, err := url.Parse(base)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") {
		return fmt.Errorf("invalid endpoint")
	}
	paths := []string{strings.TrimRight(u.Path, "/") + "/models"}
	if u.Path == "" || u.Path == "/" {
		paths = append(paths, "/v1/models")
	}
	if params, ok := p.config["query_params"].(map[string]any); ok {
		query := u.Query()
		for name, value := range params {
			if text, ok := value.(string); ok {
				query.Set(name, text)
			}
		}
		u.RawQuery = query.Encode()
	}
	client := &http.Client{Timeout: 8 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	for _, path := range paths {
		u.Path = path
		req, err := http.NewRequestWithContext(ctx, "GET", u.String(), nil)
		if err != nil {
			return fmt.Errorf("invalid request")
		}
		if headers, ok := p.config["http_headers"].(map[string]any); ok {
			for name, value := range headers {
				if text, ok := value.(string); ok {
					req.Header.Set(name, text)
				}
			}
		}
		if headers, ok := p.config["env_http_headers"].(map[string]any); ok {
			for name, value := range headers {
				if variable, ok := value.(string); ok {
					if text := os.Getenv(variable); text != "" {
						req.Header.Set(name, text)
					}
				}
			}
		}
		key, _ := p.config["experimental_bearer_token"].(string)
		if envKey, _ := p.config["env_key"].(string); key == "" && envKey != "" {
			key = os.Getenv(envKey)
		}
		if key != "" {
			req.Header.Set("Authorization", "Bearer "+key)
		}
		resp, err := client.Do(req)
		if err != nil {
			return fmt.Errorf("discovery unavailable")
		}
		var data struct {
			Data []struct {
				ID string `json:"id"`
			} `json:"data"`
		}
		err = json.NewDecoder(io.LimitReader(resp.Body, 2<<20)).Decode(&data)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK || err != nil || len(data.Data) == 0 {
			continue
		}
		seen := map[string]bool{}
		for _, m := range p.Models {
			seen[m.Model] = true
		}
		for _, m := range data.Data {
			if m.ID != "" && !seen[m.ID] {
				seen[m.ID] = true
				p.Models = append(p.Models, Model{ID: m.ID, Model: m.ID, DisplayName: m.ID, SupportedReasoningEfforts: []ReasoningEffort{}})
			}
		}
		return nil
	}
	return fmt.Errorf("no model catalog")
}
