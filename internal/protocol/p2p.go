package protocol

import "time"

const (
	TypeP2P          = "p2p"
	P2PVersion       = 1
	P2PAttempt       = 20 * time.Second
	P2PLease         = 12 * time.Second
	P2PMaxSessions   = 4
	P2PMaxCandidates = 8
	P2PMaxVerifiers  = 4
	P2PMappingStable = "stable"
	P2PMappingLinear = "linear"
	P2PMappingVaries = "varies"
	MaxEnvelopeSize  = 16 << 20
	DirectFilePrefix = "codexrm/directFile/"
)

const P2PReasonDualUnpredictableNAT = "dual_unpredictable_nat"

type P2PSignal struct {
	Version      int               `json:"version"`
	Kind         string            `json:"kind"`
	ID           string            `json:"id,omitempty"`
	Secret       string            `json:"secret,omitempty"`
	STUN         []string          `json:"stun,omitempty"`
	Candidates   []string          `json:"candidates,omitempty"`
	Mapping      string            `json:"mapping,omitempty"`      // Advisory timing hint, not address verification.
	Step         int               `json:"step,omitempty"`         // Positive port delta when Mapping is linear.
	IPv6         bool              `json:"ipv6,omitempty"`         // Capability in hello/begin, negotiated by RM in start.
	DualNATPunch bool              `json:"dualNatPunch,omitempty"` // Capability in hello/begin, negotiated by RM in start.
	LAN          bool              `json:"lan,omitempty"`
	Local        []string          `json:"local,omitempty"`
	Pin          string            `json:"pin,omitempty"`
	Clients      []string          `json:"clients,omitempty"`
	VerifyUDP    string            `json:"verifyUdp,omitempty"`
	VerifyMore   []string          `json:"verifyMore,omitempty"`
	Ticket       string            `json:"ticket,omitempty"`
	Proof        string            `json:"proof,omitempty"`
	Proofs       map[string]string `json:"proofs,omitempty"` // Receipt bound to each candidate’s address and port.
	Reason       string            `json:"reason,omitempty"`
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
