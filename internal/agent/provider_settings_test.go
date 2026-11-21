package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sync/atomic"
	"testing"

	"github.com/codexrm/codexrm/internal/appserver"
	"github.com/codexrm/codexrm/internal/providers"
)

func TestProviderModelBlacklistReappliedToUpstreamAndActiveAlias(t *testing.T) {
	ctx := context.Background()
	codexHome := t.TempDir()
	t.Setenv("CODEX_HOME", codexHome)
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
	native, err := nativeModels(ctx, app, "")
	if err != nil || len(native) == 0 {
		t.Fatalf("native test catalog unavailable: %v", err)
	}
	var nativeModel providers.Model
	if err = json.Unmarshal(native[0], &nativeModel); err != nil || nativeModel.Model == "" {
		t.Fatal("native test model unavailable")
	}

	var changed atomic.Bool
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/v1/models" || req.Header.Get("Authorization") != "Bearer secret-1" {
			http.Error(w, "provider configuration mismatch", http.StatusUnauthorized)
			return
		}
		if changed.Load() {
			fmt.Fprint(w, `{"data":[{"id":"blocked"},{"id":"new-upstream"},{"id":"manual"}]}`)
		} else {
			fmt.Fprint(w, `{"data":[{"id":"blocked"}]}`)
		}
	}))
	defer upstream.Close()
	path := providerFixture(t)
	items, err := providers.Load(ctx, path)
	if err != nil || len(items) != 2 {
		t.Fatalf("provider fixtures unavailable: %v", err)
	}
	id := items[0].ID
	base := upstream.URL + "/v1"
	if _, err := providers.SaveProvider(ctx, path, providers.ProviderPatch{ID: id, BaseURL: &base}); err != nil {
		t.Fatal(err)
	}
	config := fmt.Sprintf("model_provider = 'custom'\n[model_providers.custom]\nname = 'Same'\nbase_url = %q\nwire_api = 'responses'\nexperimental_bearer_token = 'secret-1'\nrequires_openai_auth = false\n", base)
	if err := os.WriteFile(filepath.Join(codexHome, "config.toml"), []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	configA := Config{CCSwitchDB: path, DataDir: t.TempDir()}
	a := &Agent{cfg: configA}

	catalog := func(a *Agent, management bool, providerID string) ([]string, []string) {
		t.Helper()
		result, err := a.modelCatalogWithSettings(ctx, app, management)
		if err != nil {
			t.Fatal(err)
		}
		var response struct {
			Providers []struct {
				ID      string
				AliasOf string
				Models  []struct {
					Model       string
					Blacklisted bool
				}
			}
		}
		if err := json.Unmarshal(mustJSON(result), &response); err != nil {
			t.Fatal(err)
		}
		for _, p := range response.Providers {
			if p.ID != providerID {
				continue
			}
			if p.ID == "" && p.AliasOf != id {
				t.Fatalf("unexpected active alias %q", p.AliasOf)
			}
			var models, blocked []string
			for _, m := range p.Models {
				models = append(models, m.Model)
				if m.Blacklisted {
					blocked = append(blocked, m.Model)
				}
			}
			return models, blocked
		}
		t.Fatalf("missing provider %q", providerID)
		return nil, nil
	}
	before, _ := catalog(a, false, id)
	if !containsModelID(before, "blocked") {
		t.Fatal("initial upstream model missing")
	}
	for _, change := range []struct{ model, action string }{{"blocked", "hide"}, {nativeModel.Model, "hide"}, {"manual", "add"}, {"disappeared-upstream", "hide"}} {
		if err := a.changeModel(ctx, id, change.model, change.action); err != nil {
			t.Fatal(err)
		}
	}
	changed.Store(true)
	restarted := &Agent{cfg: configA}
	visible, _ := catalog(restarted, false, id)
	if want := []string{"same-model", "new-upstream", "manual"}; !reflect.DeepEqual(visible, want) {
		t.Fatalf("visible models %v, want %v", visible, want)
	}
	alias, _ := catalog(restarted, false, "")
	if containsModelID(alias, nativeModel.Model) {
		t.Fatal("native alias leaked a blacklisted model")
	}
	all, blocked := catalog(restarted, true, id)
	if want := []string{"blocked", nativeModel.Model, "disappeared-upstream"}; !reflect.DeepEqual(blocked, want) {
		t.Fatalf("blacklist %v, want %v", blocked, want)
	}
	if !reflect.DeepEqual(all[len(all)-len(blocked):], blocked) {
		t.Fatalf("blacklisted models must appear last: %v", all)
	}
	if err := restarted.changeModel(ctx, id, "blocked", "restore"); err != nil {
		t.Fatal(err)
	}
	restored, _ := catalog(restarted, false, id)
	if !containsModelID(restored, "blocked") {
		t.Fatal("restored upstream model missing")
	}
}

func TestProviderEditsDoNotSendCredentialsThroughRelay(t *testing.T) {
	ctx := context.Background()
	a := &Agent{cfg: Config{DataDir: t.TempDir(), CCSwitchDB: providerFixture(t)}}
	items, err := providers.Load(ctx, a.cfg.CCSwitchDB)
	if err != nil {
		t.Fatal(err)
	}
	for _, patch := range []map[string]string{
		{"id": items[0].ID, "apiKey": "replacement"},
		{"id": items[0].ID, "baseUrl": "https://different.example/v1"},
	} {
		if _, err := a.providerSettingsRPC(ctx, nil, "codexrm/provider/save", mustJSON(patch), false); err == nil {
			t.Fatal("sensitive provider edit accepted over relay")
		}
	}
	if _, err := a.providerSettingsRPC(ctx, nil, "codexrm/provider/save", mustJSON(map[string]string{"id": items[0].ID, "name": "Renamed"}), false); err != nil {
		t.Fatalf("ordinary metadata edit rejected: %v", err)
	}
}

func TestProviderModelBatchCommitsTogether(t *testing.T) {
	ctx := context.Background()
	a := &Agent{cfg: Config{DataDir: t.TempDir(), CCSwitchDB: providerFixture(t)}}
	items, err := providers.Load(ctx, a.cfg.CCSwitchDB)
	if err != nil || len(items) < 2 {
		t.Fatalf("load fixture providers: %v", err)
	}
	id := items[0].ID
	other := items[1].ID
	for _, supplier := range []string{id, other} {
		if err := a.changeModel(ctx, supplier, "previously-hidden", "hide"); err != nil {
			t.Fatal(err)
		}
	}
	file := filepath.Join(a.cfg.DataDir, "model-customizations.json")
	before, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	save := func(changes []modelChange) error {
		_, err := a.providerSettingsRPC(ctx, nil, "codexrm/provider/models", mustJSON(modelChangesRequest{ID: &id, Changes: changes}), false)
		return err
	}
	changes := []modelChange{
		{Model: "added", Action: "add"},
		{Model: "newly-hidden", Action: "hide"},
		{Model: "previously-hidden", Action: "restore"},
	}
	if err := save(append(changes, modelChange{Model: "invalid", Action: "unknown"})); err == nil {
		t.Fatal("invalid changes were accepted")
	}
	after, err := os.ReadFile(file)
	if err != nil || string(after) != string(before) {
		t.Fatalf("invalid batch left partial writes: %v", err)
	}
	for attempt := 0; attempt < 2; attempt++ {
		if err := save(changes); err != nil {
			t.Fatal(err)
		}
	}
	settings, err := a.readModelSettings()
	if err != nil {
		t.Fatal(err)
	}
	if want := (modelCustomization{Added: []string{"added"}, Hidden: []string{"newly-hidden"}}); !reflect.DeepEqual(settings[id], want) {
		t.Fatalf("got %+v, want %+v", settings[id], want)
	}
	if !reflect.DeepEqual(settings[other].Hidden, []string{"previously-hidden"}) {
		t.Fatal("unrelated provider was modified")
	}
}
