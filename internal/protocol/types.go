package protocol

import "encoding/json"

const Version = "0.6.0"

// AppVersionHeader reports the running agent build. Registration is skipped
// while its token remains valid, so the websocket carries the current version.
const AppVersionHeader = "X-CodexRM-Version"

// Role identifies the peer on a relay websocket.
type Role string

const (
	RoleAgent  Role = "agent"
	RoleClient Role = "client"
)

const (
	ClientKindFull  = "full"
	ClientKindRobot = "robot"
)

const DeviceTypeRobot = "robot"

// ConnectionStatus mirrors Codex remote-control status values.
type ConnectionStatus string

const (
	StatusDisabled   ConnectionStatus = "disabled"
	StatusConnecting ConnectionStatus = "connecting"
	StatusConnected  ConnectionStatus = "connected"
	StatusErrored    ConnectionStatus = "errored"
)

// AgentInfo describes a registered local Codex host.
type AgentInfo struct {
	AgentID        string `json:"agentId"`
	ServerName     string `json:"serverName"`
	InstallationID string `json:"installationId"`
	EnvironmentID  string `json:"environmentId"`
	OS             string `json:"os"`
	Arch           string `json:"arch"`
	AppVersion     string `json:"appVersion,omitempty"`
	Online         bool   `json:"online"`
}

// EnrollRequest is sent by the local agent to register with the server.
type EnrollRequest struct {
	Name           string `json:"name"`
	OS             string `json:"os"`
	Arch           string `json:"arch"`
	AppVersion     string `json:"appVersion"`
	InstallationID string `json:"installationId"`
}

// EnrollResponse returns credentials for the agent websocket.
type EnrollResponse struct {
	AgentID       string `json:"agentId"`
	EnvironmentID string `json:"environmentId"`
	Token         string `json:"token"`
	ExpiresAt     int64  `json:"expiresAt"`
}

// PairStartRequest asks the server to create a short-lived pairing code.
type PairStartRequest struct {
	ManualCode bool `json:"manualCode"`
}

// PairStartResponse is returned to the agent / admin UI.
type PairStartResponse struct {
	PairingCode       string `json:"pairingCode"`
	ManualPairingCode string `json:"manualPairingCode,omitempty"`
	EnvironmentID     string `json:"environmentId"`
	ExpiresAt         int64  `json:"expiresAt"`
}

// PairClaimRequest is used by a web client to claim an agent.
type PairClaimRequest struct {
	Code       string `json:"code"`
	ClientName string `json:"clientName,omitempty"`
	DeviceType string `json:"deviceType,omitempty"`
}

// ClientProfileRequest updates the authenticated browser's device description.
type ClientProfileRequest struct {
	ClientName string `json:"clientName"`
	DeviceType string `json:"deviceType"`
}

// PairClaimResponse grants a client session token.
type PairClaimResponse struct {
	ClientID      string    `json:"clientId"`
	Token         string    `json:"token"`
	EnvironmentID string    `json:"environmentId"`
	Agent         AgentInfo `json:"agent"`
	ExpiresAt     int64     `json:"expiresAt"`
}

type ClientInfo struct {
	ClientID        string `json:"clientId"`
	ClientName      string `json:"clientName"`
	DeviceType      string `json:"deviceType"`
	CreatedAt       string `json:"createdAt"`
	LastConnectedAt string `json:"lastConnectedAt,omitempty"`
	LastIP          string `json:"lastIP,omitempty"`
}

// Envelope wraps a JSON-RPC payload between client and agent.
type Envelope struct {
	Type       string          `json:"type"`
	ClientID   string          `json:"clientId,omitempty"`
	StreamID   string          `json:"streamId,omitempty"`
	SeqID      uint64          `json:"seqId,omitempty"`
	Message    json.RawMessage `json:"message,omitempty"`
	Status     string          `json:"status,omitempty"`
	Error      string          `json:"error,omitempty"`
	Agent      *AgentInfo      `json:"agent,omitempty"`
	PingID     string          `json:"pingId,omitempty"`
	P2P        *P2PSignal      `json:"p2p,omitempty"`
	SkipDirect []string        `json:"skipDirect,omitempty"`
	// ClientKind is assigned by the relay. Clients cannot choose it.
	ClientKind string `json:"clientKind,omitempty"`
}

const (
	TypeClientMessage = "client_message"
	TypeServerMessage = "server_message"
	TypeAgentStatus   = "agent_status"
	TypePing          = "ping"
	TypePong          = "pong"
	TypeError         = "error"
	TypeHello         = "hello"
	TypeWelcome       = "welcome"
)

// JSONRPCMessage is a loosely typed JSON-RPC 2.0 object.
type JSONRPCMessage map[string]any

func (m JSONRPCMessage) Method() string {
	if m == nil {
		return ""
	}
	if v, ok := m["method"].(string); ok {
		return v
	}
	return ""
}

func (m JSONRPCMessage) ID() any {
	if m == nil {
		return nil
	}
	return m["id"]
}
