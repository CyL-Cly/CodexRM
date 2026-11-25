package p2p

// ConnState is this process's view of one direct-session attempt.
//
// Signaling uses the existing RM WebSocket and protocol.P2PSignal kinds
// (hello, begin, start, offer, ready, activate, active, lease, renew, stop).
// After both offers arrive, the peers punch UDP and open QUIC. These names
// describe that local flow; they are not Socket.IO, SDP, WebRTC ICE, or
// DataChannel states from another product.
type ConnState string

const (
	ConnIdle       ConnState = "idle"
	ConnPreparing  ConnState = "preparing"  // STUN and path discovery
	ConnOffering   ConnState = "offering"   // local offer is ready
	ConnExchanging ConnState = "exchanging" // peer offer received
	ConnPunching   ConnState = "punching"
	ConnTransport  ConnState = "transport" // QUIC over the nominated UDP socket, TLS 1.3
	ConnControl    ConnState = "control"   // first bidirectional stream + ControlMarker
	ConnConnected  ConnState = "connected"
	ConnRelay      ConnState = "relay" // force-relay or later fallback to RM WebSocket
	ConnFailed     ConnState = "failed"
	ConnClosed     ConnState = "closed"
)

// ControlMarker is exchanged on the first bidirectional QUIC stream after
// TLS 1.3 completes. A link is not connected until both sides accept it.
// CodexRM uses this stream for RPC envelopes. The Agent may accept one
// additional stream for an Android-to-desktop file transfer. Other
// application channel types are not defined by this protocol.
const ControlMarker = "codexrm-direct/1"

const (
	agentIncomingStreams  = 3
	clientIncomingStreams = 1
)

// DirectReady is true only after punch, QUIC/TLS, and the control-stream
// marker have all succeeded.
func DirectReady(state ConnState) bool {
	return state == ConnConnected
}

// SignalPhase is the RM-visible negotiation phase for one pair id.
// It is derived from the server's pair flags; there is no extra wire field.
type SignalPhase string

const (
	SignalStarting   SignalPhase = "starting"
	SignalOffering   SignalPhase = "offering"
	SignalExchanging SignalPhase = "exchanging"
	SignalReady      SignalPhase = "ready"
	SignalActivating SignalPhase = "activating"
	SignalActive     SignalPhase = "active"
)

// SignalPhaseFromFlags maps the server pair booleans to a SignalPhase.
func SignalPhaseFromFlags(agentOffer, clientOffer, agentReady, clientReady, requested, active bool) SignalPhase {
	switch {
	case active:
		return SignalActive
	case requested:
		return SignalActivating
	case agentReady && clientReady:
		return SignalReady
	case agentOffer && clientOffer:
		return SignalExchanging
	case agentOffer || clientOffer:
		return SignalOffering
	default:
		return SignalStarting
	}
}

// MarkRelay records that this session gave up the direct path and is staying
// on (or returning to) the RM WebSocket relay. Close preserves this state.
func (s *Session) MarkRelay() {
	if s == nil {
		return
	}
	s.setState(ConnRelay)
}
