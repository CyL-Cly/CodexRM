package release

// Server releases have their own monotonically increasing code, independent of the clients.
const ServerVersionCode int64 = 1
const ServerVersionName = "0.1.0"

const ServerManifestName = "server-update.json"

type ServerHealth struct {
	OK              bool   `json:"ok"`
	Service         string `json:"service"`
	ProtocolVersion string `json:"version"`
	VersionCode     int64  `json:"serverVersionCode"`
	VersionName     string `json:"serverVersionName"`
}
