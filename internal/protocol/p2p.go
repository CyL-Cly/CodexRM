package protocol

import (
	"strings"
	"time"
)

// Signaling for a direct session rides on the existing RM WebSocket
// (Envelope.Type == TypeP2P). It is not Socket.IO, SDP, or a WebRTC
// offer/answer/candidate channel. Kind values are listed below.
//
// P2PAttempt is the RM deadline for finishing offer exchange, UDP punch,
// QUIC/TLS, the control-stream marker, and activate/active. P2PLease is
// the post-active authorization window; the client renews it. The 12s
// lease is a CodexRM keep-alive, not a post-candidate connect countdown.
const (
	TypeP2P             = "p2p"
	P2PVersion          = 1
	P2PAttempt          = 20 * time.Second
	P2PLease            = 12 * time.Second
	P2PMaxSessions      = 4
	P2PMaxCandidates    = 8
	P2PMaxVerifiers     = 4
	P2PMappingStable    = "stable"
	P2PMappingLinear    = "linear"
	P2PMappingVaries    = "varies"
	MaxEnvelopeSize     = 16 << 20
	PhoneTransferPrefix = "codexrm/phoneTransfer/"
	DirectFilePrefix    = "codexrm/directFile/"

	P2PKindHello          = "hello"
	P2PKindRouting        = "routing"
	P2PKindAvailable      = "available"
	P2PKindBegin          = "begin"
	P2PKindStart          = "start"
	P2PKindOffer          = "offer"
	P2PKindReady          = "ready"
	P2PKindActivate       = "activate"
	P2PKindActive         = "active"
	P2PKindLease          = "lease"
	P2PKindRenew          = "renew"
	P2PKindStop           = "stop"
	P2PKindBusy           = "busy"
	P2PKindDiagnose       = "diagnose"
	P2PKindDiagnoseStart  = "diagnose_start"
	P2PKindDiagnoseResult = "diagnose_result"
	P2PKindDiagnoseError  = "diagnose_error"
	P2PKindJoin           = "join"
	P2PKindLeave          = "leave"
)

const (
	DirectShareIDSize = 36 // Canonical UUID of a pending upload or an authorized read.
	DirectFileMaxSize = 512 << 20
	// Old uploads start with a lowercase UUID. Non-ASCII-hex prefixes select downloads.
	DirectFileDownload byte = 2
	PhoneFileDownload  byte = 3
	DirectShareOK           = 1
	DirectShareTimeout      = 10 * time.Minute
)

const (
	DesktopShareDestination    = "downloads"
	DesktopShareDirectRequired = "发送到电脑仅支持 P2P 直连"
)

const FileTransferDirectRequired = "文件传输仅支持 P2P 直连"

func IsFileTransferRPC(method string) bool {
	return strings.HasPrefix(method, DirectFilePrefix) ||
		(strings.HasPrefix(method, PhoneTransferPrefix) && method != PhoneTransferPrefix+"list") ||
		strings.HasPrefix(method, "codexrm/attachment/") || method == "codexrm/image/read"
}

const P2PReasonDualUnpredictableNAT = "dual_unpredictable_nat"

type P2PSignal struct {
	Version      int                       `json:"version"`
	Kind         string                    `json:"kind"`
	ID           string                    `json:"id,omitempty"`
	Secret       string                    `json:"secret,omitempty"`
	STUN         []string                  `json:"stun,omitempty"`
	Candidates   []string                  `json:"candidates,omitempty"`
	Mapping      string                    `json:"mapping,omitempty"`      // Advisory timing hint, not address verification.
	Step         int                       `json:"step,omitempty"`         // Positive port delta when Mapping is linear.
	PathMappings map[string]P2PMappingHint `json:"pathMappings,omitempty"` // Per-candidate IPv4 hint, filtered by RM.
	IPv6         bool                      `json:"ipv6,omitempty"`         // Capability in hello/begin, negotiated by RM in start.
	DualNATPunch bool                      `json:"dualNatPunch,omitempty"` // Capability in hello/begin, negotiated by RM in start.
	MultiPath    bool                      `json:"multiPath,omitempty"`    // RM accepts verifier receipts from multiple sockets.
	LAN          bool                      `json:"lan,omitempty"`
	Local        []string                  `json:"local,omitempty"`
	Pin          string                    `json:"pin,omitempty"`
	Clients      []string                  `json:"clients,omitempty"`
	VerifyUDP    string                    `json:"verifyUdp,omitempty"`
	VerifyMore   []string                  `json:"verifyMore,omitempty"`
	Ticket       string                    `json:"ticket,omitempty"`
	Proof        string                    `json:"proof,omitempty"`
	Proofs       map[string]string         `json:"proofs,omitempty"` // Receipt bound to each candidate’s address and port.
	Reason       string                    `json:"reason,omitempty"`

	NATDiagnostic   bool       `json:"natDiagnostic,omitempty"` // Support in Agent hello.
	NATResult       *NATReport `json:"natResult,omitempty"`
	NATRetryAfterMs int64      `json:"natRetryAfterMs,omitempty"` // Cooldown reported by RM.
	NATUsesP2PSTUN  bool       `json:"natUsesP2PStun,omitempty"`  // No separate diagnostic endpoints configured.

	ClientInitiated bool `json:"clientInitiated,omitempty"` // Negotiated permission for an addressless client offer.

	ForceRelay       bool `json:"forceRelay,omitempty"`       // Skip punching and stay on the RM relay.
	DisableFullPunch bool `json:"disableFullPunch,omitempty"` // Exact published ports only; no prediction, spray, or extra sockets.
}

type P2PMappingHint struct {
	Mapping string `json:"mapping"`
	Step    int    `json:"step,omitempty"`
}

func P2P(kind, id string) *P2PSignal {
	return &P2PSignal{Version: P2PVersion, Kind: kind, ID: id}
}

// NormalizeMapping keeps a sequential-port hint only with a small positive step.
// A linear label without a usable step is treated as an unpredictable mapping.
func NormalizeMapping(mapping string, step int) (string, int) {
	switch mapping {
	case P2PMappingStable:
		return P2PMappingStable, 0
	case P2PMappingLinear:
		if step >= 1 && step <= 32 {
			return P2PMappingLinear, step
		}
		return P2PMappingVaries, 0
	case P2PMappingVaries:
		return P2PMappingVaries, 0
	default:
		return "", 0
	}
}
