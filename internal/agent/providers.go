package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/providers"
)

func nativeModels(ctx context.Context, app *appserver.Client, provider string) ([]json.RawMessage, error) {
	models := []json.RawMessage{}
	params := map[string]any{"limit": 100}
	if provider != "" {
		params["modelProvider"] = provider
	}
	for {
		raw, err := app.Call(ctx, "model/list", params)
		if err != nil {
			return nil, err
		}
		var page struct {
			Data       []json.RawMessage `json:"data"`
			NextCursor *string           `json:"nextCursor"`
		}
		if err := json.Unmarshal(raw, &page); err != nil {
			return nil, err
		}
		models = append(models, page.Data...)
		if page.NextCursor == nil || *page.NextCursor == "" || params["cursor"] == *page.NextCursor {
			return models, nil
		}
		params["cursor"] = *page.NextCursor
	}
}

func (a *Agent) modelCatalog(ctx context.Context, app *appserver.Client) (any, error) {
	groups := []any{}
	warnings := []string{}
	models, err := nativeModels(ctx, app, "")
	if err != nil {
		warnings = append(warnings, "当前 Codex 模型列表暂不可用")
		models = []json.RawMessage{}
	}
	native := map[string]any{"id": "", "runtimeId": "", "name": "当前 Codex 配置", "source": "Codex", "models": models}
	groups = append(groups, native)
	if !a.cfg.Mock && a.cfg.AppServerURL == "" {
		items, err := providers.Load(ctx, a.cfg.CCSwitchDB)
		if err != nil {
			warnings = append(warnings, err.Error())
		} else {
			if id := providers.CurrentProviderID(items, a.cfg.CCSwitchDB); id != "" {
				native["aliasOf"] = id
			}
			providers.Discover(ctx, items)
			for _, p := range items {
				public := map[string]any{"id": p.ID, "runtimeId": p.RuntimeID(), "name": p.Name, "notes": p.Notes, "source": p.Source, "models": p.Models, "defaultModel": p.DefaultModel, "warning": p.Warning}
				if p.Official() {
					if len(p.Models) == 0 {
						public["warning"] = "CCSwitch 中没有官方模型缓存，请先在 CCSwitch 中登录并获取模型"
					}
				}
				groups = append(groups, public)
			}
		}
	}
	return map[string]any{"providers": groups, "warnings": warnings}, nil
}

// Recover the credentials for the thread?s Codex provider. Codex owns the
// thread?s model and reasoning effort, including changes made by the desktop.
func (a *Agent) prepareProvider(ctx context.Context, app *appserver.Client, method string, params map[string]any) error {
	value, supplied := params["codexrmProvider"]
	delete(params, "codexrmProvider")
	if method != "thread/start" && method != "thread/resume" {
		return nil
	}
	id, _ := value.(string)
	if a.cfg.Mock || a.cfg.AppServerURL != "" {
		if id != "" {
			return fmt.Errorf("此连接器不支持本地 CCSwitch 供应商")
		}
		return nil
	}
	if !supplied && method == "thread/resume" {
		raw, err := app.Call(ctx, "thread/read", map[string]any{"threadId": params["threadId"], "includeTurns": false})
		if err != nil {
			return err
		}
		var result struct {
			Thread struct {
				ModelProvider string `json:"modelProvider"`
			} `json:"thread"`
		}
		if err := json.Unmarshal(raw, &result); err != nil {
			return err
		}
		if strings.HasPrefix(result.Thread.ModelProvider, "ccswitch_") {
			id = result.Thread.ModelProvider
		}
	}
	if id == "" {
		return nil
	}
	items, err := providers.Load(ctx, a.cfg.CCSwitchDB)
	if err != nil {
		return err
	}
	for _, p := range items {
		if p.ID != id {
			continue
		}
		if p.Warning != "" {
			return fmt.Errorf("%s：%s", p.Name, p.Warning)
		}
		params["modelProvider"] = p.RuntimeID()
		if method == "thread/start" && params["model"] == nil && p.DefaultModel != "" {
			params["model"] = p.DefaultModel
		}
		if overrides := p.Overrides(); len(overrides) > 0 {
			config, _ := params["config"].(map[string]any)
			if config == nil {
				config = map[string]any{}
			}
			for key, value := range overrides {
				config[key] = value
			}
			params["config"] = config
		}
		return nil
	}
	return fmt.Errorf("CCSwitch 中已找不到此会话的供应商，请恢复该配置或选择其他供应商新建会话")
}
