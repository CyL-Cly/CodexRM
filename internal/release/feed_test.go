package release

import (
	"os"
	"testing"
)

func TestUpdateEndpoint(t *testing.T) {
	const env = "TEST_CODEXRM_RELEASE_UPDATE_OVERRIDE"
	t.Setenv(env, "")
	if err := os.Unsetenv(env); err != nil {
		t.Fatal(err)
	}
	previous := updateBaseURL
	t.Cleanup(func() { updateBaseURL = previous })
	updateBaseURL = ""
	if got := UpdateEndpoint(env, "update.json"); got != "" {
		t.Fatalf("unconfigured source build returned %q", got)
	}
	updateBaseURL = "https://updates.example/"
	if got := UpdateEndpoint(env, ""); got != "https://updates.example" {
		t.Fatalf("wrong packaged origin: %q", got)
	}
	for _, name := range []string{"update.json", "linux-update.json", ServerManifestName} {
		if got := UpdateEndpoint(env, name); got != "https://updates.example/"+name {
			t.Fatalf("wrong packaged endpoint for %s: %q", name, got)
		}
	}
	t.Setenv(env, " https://override.example/custom.json ")
	if got := UpdateEndpoint(env, ServerManifestName); got != "https://override.example/custom.json" {
		t.Fatalf("override was ignored: %q", got)
	}
	t.Setenv(env, "")
	if got := UpdateEndpoint(env, ServerManifestName); got != "" {
		t.Fatalf("empty override did not opt out: %q", got)
	}
}
