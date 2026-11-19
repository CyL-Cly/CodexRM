package clientconfig

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultRelayRequiresMissingSettings(t *testing.T) {
	path := filepath.Join(t.TempDir(), "connection.json")
	fresh, err := Load(path)
	if err != nil || fresh.Server != "https://codexrm.ooth.tech" || fresh.EnrollmentKey != "" || !fresh.AutoUpdate {
		t.Fatalf("unexpected defaults: %+v, %v", fresh, err)
	}
	if fresh.ManifestURL() != "https://codexrm.dagongren.tech/update.json" {
		t.Fatalf("unexpected update manifest: %s", fresh.ManifestURL())
	}
	if err := Save(path, Connection{}); err != nil {
		t.Fatal(err)
	}
	saved, err := Load(path)
	if err != nil || saved.Server != "" || saved.AutoUpdate {
		t.Fatalf("existing empty settings changed: %+v, %v", saved, err)
	}
}

func TestLegacyUpdateSourcesAlwaysUseBuiltInService(t *testing.T) {
	path := filepath.Join(t.TempDir(), "connection.json")
	legacy := `{"server":"https://relay.example","enrollmentKey":"saved-key","autoUpdate":true,"updateServer":"not a URL","updateManifestUrl":"https://old.example/custom.json"}`
	if err := os.WriteFile(path, []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.UpdateServer != DefaultUpdateServer || cfg.UpdateManifestURL != "" || cfg.ManifestURL() != DefaultUpdateServer+"update.json" {
		t.Fatalf("legacy source was retained: %+v", cfg)
	}
	if cfg.Server != "https://relay.example" || cfg.EnrollmentKey != "saved-key" {
		t.Fatal("migration changed connection settings")
	}
	cfg.UpdateServer, cfg.UpdateManifestURL = "https://other.example/", "https://other.example/feed.json"
	if cfg.ManifestURL() != DefaultUpdateServer+"update.json" {
		t.Fatal("unnormalized config overrode the built-in manifest")
	}
	if err = Save(path, cfg); err != nil {
		t.Fatal(err)
	}
	saved, err := Load(path)
	if err != nil || saved.UpdateServer != DefaultUpdateServer || saved.UpdateManifestURL != "" {
		t.Fatalf("fixed update service did not survive saving: %+v, %v", saved, err)
	}
}
