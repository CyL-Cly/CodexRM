package server

import (
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/google/uuid"
)

type natDiagnostic struct {
	id      string
	agent   *peer
	expires time.Time
}

// The NAT test uses relay authorization but does not depend on an existing
// direct session, the presence of a trusted proxy address, or peer addresses.
// Only RM-configured endpoints are sent to the Agent and Android.
func (s *Server) handleNATDiagnostic(p *peer, env protocol.Envelope) {
	sig := env.P2P
	if sig.Version != protocol.P2PVersion || len(sig.ID) != 36 {
		return
	}
	id, err := uuid.Parse(sig.ID)
	if err != nil || id.String() != sig.ID {
		return
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	switch sig.Kind {
	case protocol.P2PKindDiagnose:
		if p.role != protocol.RoleClient || s.clients[p.clientID] != p || !diagnosticPeerAlive(p, now) {
			return
		}
		reject := func(reason string, retryAfter time.Duration) {
			e := protocol.P2P(protocol.P2PKindDiagnoseError, sig.ID)
			e.Reason = reason
			e.NATRetryAfterMs = retryAfter.Milliseconds()
			s.sendDirect(p, p, e)
		}
		agent := s.agents[p.agentID]
		if !diagnosticPeerAlive(agent, now) {
			reject("agent_unavailable", 0)
			return
		}
		if !agent.natDiagnosticCapable {
			reject("agent_unsupported", 0)
			return
		}
		stun := s.natSTUN
		if len(stun) == 0 {
			reject("no_ipv4_stun", 0)
			return
		}
		last := p.lastNATDiagnostic
		if agent.lastNATDiagnostic.After(last) {
			last = agent.lastNATDiagnostic
		}
		if remaining := protocol.NATDiagnosticWindow - now.Sub(last); remaining > 0 {
			reject("diagnose_busy", remaining.Round(time.Millisecond))
			return
		}
		p.lastNATDiagnostic, agent.lastNATDiagnostic = now, now
		p.natDiagnostic = &natDiagnostic{id: sig.ID, agent: agent, expires: now.Add(protocol.NATDiagnosticWindow)}
		start := protocol.P2P(protocol.P2PKindDiagnoseStart, sig.ID)
		start.STUN = stun
		start.NATRetryAfterMs = protocol.NATDiagnosticWindow.Milliseconds()
		start.NATUsesP2PSTUN = s.cfg.P2PNATSTUN == ""
		s.sendDirect(agent, p, start)
		s.sendDirect(p, p, start)
	case protocol.P2PKindDiagnoseResult:
		if p.role != protocol.RoleAgent || s.agents[p.agentID] != p || !diagnosticPeerAlive(p, now) || sig.NATResult == nil || !sig.NATResult.Valid() {
			return
		}
		c := s.byAgent[p.agentID][env.ClientID]
		if !diagnosticPeerAlive(c, now) || c.natDiagnostic == nil || c.natDiagnostic.agent != p || c.natDiagnostic.id != sig.ID || !now.Before(c.natDiagnostic.expires) {
			return
		}
		c.natDiagnostic = nil
		result := protocol.P2P(protocol.P2PKindDiagnoseResult, sig.ID)
		result.NATResult = sig.NATResult
		s.sendDirect(c, c, result)
	}
}

func diagnosticPeerAlive(p *peer, now time.Time) bool {
	if p == nil || (!p.expires.IsZero() && !now.Before(p.expires)) {
		return false
	}
	select {
	case <-p.closed:
		return false
	default:
		return true
	}
}
