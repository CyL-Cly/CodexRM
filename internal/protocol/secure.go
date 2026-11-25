package protocol

// SecureFrame carries only an opaque Noise frame and routing metadata.
// Step and Route are untrusted; endpoint handshakes bind the latter to the key.
type SecureFrame struct {
	Step  string `json:"step"`
	Route string `json:"route,omitempty"`
	Data  []byte `json:"data"`
}

const (
	SecureHello   = "hello"
	SecureReply   = "reply"
	SecureConfirm = "confirm"
	SecureData    = "data"
)
