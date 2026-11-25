package server

import (
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/codexrm/codexrm/internal/securechannel"
)

func (s *Server) forwardSecure(p *peer, env protocol.Envelope) {
	f := env.Secure
	if f == nil {
		return
	}
	clean := protocol.Envelope{Type: protocol.TypeSecure, Secure: f}
	if p.role == protocol.RoleClient {
		if !p.secure || !validSecureClientFrame(f) {
			return
		}
		clean.ClientID = p.clientID
		s.dispatchClientRPC(p, clean)
		return
	}
	if f.Route != "" || (f.Step == protocol.SecureReply && (len(f.Data) != 48 && len(f.Data) != 80)) ||
		(f.Step == protocol.SecureData && len(f.Data) < 16) ||
		(f.Step == protocol.SecureConfirm && len(f.Data) != 21) ||
		(f.Step != protocol.SecureReply && f.Step != protocol.SecureData && f.Step != protocol.SecureConfirm) {
		return
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	c := s.clients[env.ClientID]
	if s.agents[p.agentID] == p && c != nil && c.secure && c.agentID == p.agentID {
		if !s.send(c, clean) {
			// Noise records are ordered; losing a frame invalidates the channel.
			c.close()
		}
	}
}

func validSecureClientFrame(f *protocol.SecureFrame) bool {
	switch f.Step {
	case protocol.SecureHello:
		return len(f.Data) == 48 && (f.Route == "" || securechannel.ValidRoute(f.Route))
	case protocol.SecureConfirm:
		return f.Route == "" && len(f.Data) == 21
	case protocol.SecureData:
		return f.Route == "" && len(f.Data) >= 16
	default:
		return false
	}
}
