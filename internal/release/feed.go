package release

import (
	"os"
	"strings"
)

// Configured by the release build with -ldflags -X. Source builds leave it empty.
var updateBaseURL string

// UpdateEndpoint prefers an explicit local override, including an empty value
// to opt out, over the optional release default.
func UpdateEndpoint(env, filename string) string {
	if value, ok := os.LookupEnv(env); ok {
		return strings.TrimSpace(value)
	}
	base := strings.TrimRight(strings.TrimSpace(updateBaseURL), "/")
	if base == "" || filename == "" {
		return base
	}
	return base + "/" + filename
}
