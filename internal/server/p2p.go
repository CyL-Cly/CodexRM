package server

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"time"

	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/google/uuid"
)

// directPair is one RM-side negotiation between a paired Agent and client.
// The boolean flags are the signal phase: starting -> offering -> exchanging
// -> ready -> activating -> active (see p2p.SignalPhaseFromFlags). One pair
// has a single id (the session handle). An Agent may have up to
// protocol.P2PMaxSessions concurrent pairs.
type directPair struct {
	id                                                                  string
	agent                                                               *peer
	deadline                                                            time.Time
	agentTicket, clientTicket                                           string
	lan, ipv6, clientInitiated                                          bool
	agentOffer, clientOffer, agentReady, clientReady, requested, active bool
}

func (p *directPair) phase() p2p.SignalPhase {
	if p == nil {
		return p2p.SignalStarting
	}
	return p2p.SignalPhaseFromFlags(p.agentOffer, p.clientOffer, p.agentReady, p.clientReady, p.requested, p.active)
}

func publicVerifierIP(ip netip.Addr) bool {
	return p2p.IsPublicIPv6(ip) || (ip.Is4() && ip.IsGlobalUnicast() && !ip.IsPrivate())
}

func (s *Server) startVerifier() error {
	if s.cfg.P2PVerifyListen == "" && s.cfg.P2PVerifyPublic == "" && s.cfg.P2PVerifyMore == "" && s.cfg.P2PVerifyKeyFile == "" {
		return nil
	}
	local, le := netip.ParseAddrPort(s.cfg.P2PVerifyListen)
	public, pe := netip.ParseAddrPort(s.cfg.P2PVerifyPublic)
	if len(s.stun) == 0 || le != nil || pe != nil || local.Addr().Zone() != "" || public.Addr().Zone() != "" ||
		local.Addr().Is4In6() || public.Addr().Is4In6() || local.Addr().Is4() != public.Addr().Is4() ||
		(!s.cfg.P2PLocalTest && (local.Port() == 0 || public.Port() == 0 || !publicVerifierIP(public.Addr()))) {
		return fmt.Errorf("P2P verification requires STUN plus local and advertised UDP endpoints of the same IP family")
	}
	if s.cfg.P2PVerifyMore != "" && s.cfg.P2PVerifyKeyFile == "" {
		return fmt.Errorf("additional P2P verifiers require a shared key file")
	}
	for _, value := range strings.Split(s.cfg.P2PVerifyMore, ",") {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		addr, err := netip.ParseAddrPort(value)
		if err != nil || addr.Addr().Zone() != "" || addr.Addr().Is4In6() || addr.Port() == 0 ||
			addr == public || slices.Contains(s.verifyMore, addr.String()) || len(s.verifyMore) >= protocol.P2PMaxVerifiers-1 ||
			(!s.cfg.P2PLocalTest && !publicVerifierIP(addr.Addr())) {
			return fmt.Errorf("P2P verification requires up to %d distinct additional public UDP endpoints", protocol.P2PMaxVerifiers-1)
		}
		s.verifyMore = append(s.verifyMore, addr.String())
	}
	v, err := p2p.ListenVerifier(local, s.cfg.P2PVerifyKeyFile)
	if err != nil {
		return fmt.Errorf("P2P UDP verifier: %w", err)
	}
	if public.Port() == 0 {
		public = netip.AddrPortFrom(public.Addr(), v.Addr().Port())
	}
	s.verifier, s.verifyPublic = v, public.String()
	return nil
}

func stunEndpoints(raw string) ([]string, error) {
	var endpoints []string
	for _, s := range strings.Split(raw, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		a, err := netip.ParseAddrPort(s)
		if err != nil || a.Addr().Zone() != "" || a.Port() == 0 || len(endpoints) >= 4 {
			return nil, fmt.Errorf("P2P STUN requires up to four IPv4:port or [IPv6]:port endpoints")
		}
		endpoints = append(endpoints, netip.AddrPortFrom(a.Addr().Unmap(), a.Port()).String())
	}
	return endpoints, nil
}

func (s *Server) directIP(r *http.Request) netip.Addr {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip, _ := netip.ParseAddr(host)
	ip = ip.Unmap()
	if ip.IsLoopback() {
		if s.cfg.P2PTrustProxy && r.Header.Get("X-Forwarded-Proto") == "https" {
			ip, _ = netip.ParseAddr(r.Header.Get("X-Real-IP"))
			ip = ip.Unmap()
		} else if s.cfg.P2PLocalTest && r.TLS == nil {
			return ip
		}
	}
	if r.TLS == nil && !(s.cfg.P2PTrustProxy && net.ParseIP(host).IsLoopback() && r.Header.Get("X-Forwarded-Proto") == "https") {
		return netip.Addr{}
	}
	if (ip.Is4() && ip.IsGlobalUnicast()) || p2p.IsPublicIPv6(ip) || (s.cfg.P2PLocalTest && ip.IsLoopback()) {
		return ip
	}
	return netip.Addr{}
}

func stunForPair(endpoints []string, ipv6 bool) []string {
	if ipv6 {
		return endpoints
	}
	return slices.DeleteFunc(slices.Clone(endpoints), func(raw string) bool {
		return netip.MustParseAddrPort(raw).Addr().Is6()
	})
}

func (s *Server) sendDirect(to, client *peer, sig *protocol.P2PSignal) {
	env := protocol.Envelope{Type: protocol.TypeP2P, P2P: sig}
	if to.role == protocol.RoleAgent {
		env.ClientID = client.clientID
	}
	s.send(to, env)
}

// Caller holds s.mu. Pair state belongs to the specific upgraded sockets.
func (s *Server) stopDirect(client *peer) {
	s.stopDirectWithReason(client, "")
}

func (s *Server) stopDirectWithReason(client *peer, reason string) {
	if pair := client.direct; pair != nil {
		client.direct = nil
		stop := protocol.P2P(protocol.P2PKindStop, pair.id)
		stop.Reason = reason
		s.sendDirect(pair.agent, client, stop)
		s.sendDirect(client, client, stop)
	}
}

func directAlive(p *peer, now time.Time) bool {
	return diagnosticPeerAlive(p, now) && p.directIP.IsValid()
}

func (s *Server) lanEligible(agent, client *peer) bool {
	if !s.cfg.P2PLAN || !agent.lanCapable || !agent.directIP.Is4() || !client.directIP.Is4() {
		return false
	}
	a, c := agent.directIP, client.directIP
	if !a.IsGlobalUnicast() || !c.IsGlobalUnicast() {
		return false
	}
	if a.IsPrivate() || c.IsPrivate() {
		return a.IsPrivate() && c.IsPrivate() && netip.PrefixFrom(a, 24).Contains(c)
	}
	return a == c
}

func (s *Server) directAvailable(agent, client *peer) bool {
	return agent.directIP.IsValid() && client.directIP.IsValid() && (len(s.stun) > 0 || s.lanEligible(agent, client))
}

func (s *Server) handleDirect(p *peer, env protocol.Envelope) {
	sig := env.P2P
	if sig == nil || sig.Version != protocol.P2PVersion {
		return
	}
	if sig.Kind == protocol.P2PKindDiagnose || sig.Kind == protocol.P2PKindDiagnoseResult {
		s.handleNATDiagnostic(p, env)
		return
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	if p.role == protocol.RoleAgent && sig.Kind == protocol.P2PKindHello && s.agents[p.agentID] == p {
		p.natDiagnosticCapable = sig.NATDiagnostic
	}
	if (len(s.stun) == 0 && !s.cfg.P2PLAN) || !directAlive(p, now) {
		return
	}
	var agent, client *peer
	if p.role == protocol.RoleAgent {
		if s.agents[p.agentID] != p {
			return
		}
		if sig.Kind == protocol.P2PKindHello {
			if p.directCapable {
				return
			}
			p.directCapable = true
			p.dualNATPunchCapable = sig.DualNATPunch
			p.clientInitiatedCapable = sig.ClientInitiated
			p.ipv6Capable = sig.IPv6
			p.lanCapable = sig.LAN
			routing := protocol.P2P(protocol.P2PKindRouting, "")
			for id := range s.byAgent[p.agentID] {
				routing.Clients = append(routing.Clients, id)
			}
			s.send(p, protocol.Envelope{Type: protocol.TypeP2P, P2P: routing})
			for _, c := range s.byAgent[p.agentID] {
				if directAlive(c, now) && s.directAvailable(p, c) {
					s.sendDirect(c, c, protocol.P2P(protocol.P2PKindAvailable, ""))
				}
			}
			return
		}
		agent, client = p, s.byAgent[p.agentID][env.ClientID]
	} else {
		if s.clients[p.clientID] != p {
			return
		}
		agent, client = s.agents[p.agentID], p
	}
	if !directAlive(agent, now) || !directAlive(client, now) || !agent.directCapable {
		return
	}
	if client.direct != nil && (!now.Before(client.direct.deadline) || client.direct.agent != agent) {
		s.stopDirect(client)
	}
	pair := client.direct
	if sig.Kind == protocol.P2PKindBegin && p == client {
		lan := sig.LAN && s.lanEligible(agent, client)
		ipv6 := agent.ipv6Capable && sig.IPv6 && len(s.stun) > 0
		stun := stunForPair(s.stun, ipv6)
		if !lan && len(stun) == 0 {
			return
		}
		if pair != nil || now.Sub(client.lastDirect) < protocol.P2PAttempt {
			return
		}
		client.lastDirect = now
		count := 0
		for _, c := range s.byAgent[p.agentID] {
			if c.direct != nil && !now.Before(c.direct.deadline) {
				s.stopDirect(c)
			}
			if c.direct != nil {
				count++
			}
		}
		if count >= protocol.P2PMaxSessions {
			s.sendDirect(client, client, protocol.P2P(protocol.P2PKindBusy, ""))
			return
		}
		var key [32]byte
		if _, err := rand.Read(key[:]); err != nil {
			return
		}
		pair = &directPair{id: uuid.NewString(), agent: agent, deadline: now.Add(protocol.P2PAttempt), lan: lan, ipv6: ipv6}
		start := protocol.P2P(protocol.P2PKindStart, pair.id)
		start.Secret, start.STUN = hex.EncodeToString(key[:]), stun
		start.LAN, start.IPv6, start.MultiPath = lan, ipv6, lan || len(stun) > 0
		pair.clientInitiated = agent.clientInitiatedCapable && sig.ClientInitiated
		start.ClientInitiated = pair.clientInitiated
		start.DualNATPunch = len(stun) > 0 && agent.dualNATPunchCapable && sig.DualNATPunch
		start.ForceRelay = sig.ForceRelay
		start.DisableFullPunch = sig.DisableFullPunch
		if s.verifier != nil {
			var err error
			if pair.agentTicket, err = s.verifier.Ticket(now); err != nil {
				return
			}
			if pair.clientTicket, err = s.verifier.Ticket(now); err != nil {
				return
			}
			start.VerifyUDP, start.Ticket = s.verifyPublic, pair.agentTicket
			start.VerifyMore = s.verifyMore
		}
		client.direct = pair
		clientStart := *start
		clientStart.Ticket = pair.clientTicket
		log.Printf("p2p begin id=%s phase=%s forceRelay=%t disableFullPunch=%t", pair.id, pair.phase(), start.ForceRelay, start.DisableFullPunch)
		s.sendDirect(agent, client, start)
		s.sendDirect(client, client, &clientStart)
		return
	}
	if pair == nil || sig.ID != pair.id {
		return
	}
	other := agent
	if p == agent {
		other = client
	}
	prevPhase := pair.phase()
	forward := protocol.P2P(sig.Kind, pair.id)
	switch sig.Kind {
	case protocol.P2PKindStop:
		if p == agent && !pair.active && pair.agentOffer && pair.clientOffer && sig.Reason == protocol.P2PReasonDualUnpredictableNAT {
			s.stopDirectWithReason(client, sig.Reason)
		} else {
			s.stopDirect(client)
		}
		return
	case protocol.P2PKindOffer:
		if (p == agent && pair.agentOffer) || (p == client && pair.clientOffer) {
			return
		}
		pin, err := hex.DecodeString(sig.Pin)
		if err != nil || len(pin) != 32 || len(sig.Candidates) > protocol.P2PMaxCandidates || len(sig.Local) > protocol.P2PMaxCandidates || len(sig.Proofs) > protocol.P2PMaxCandidates || len(sig.PathMappings) > protocol.P2PMaxCandidates {
			s.stopDirectWithReason(client, "invalid_offer")
			return
		}
		forward.Pin = sig.Pin
		if client.secure {
			if len(sig.PinMAC) != 44 {
				s.stopDirectWithReason(client, "invalid_offer")
				return
			}
			forward.PinMAC = sig.PinMAC
		}
		forward.Mapping, forward.Step = protocol.NormalizeMapping(sig.Mapping, sig.Step)
		ticket := pair.clientTicket
		if p == agent {
			ticket = pair.agentTicket
		}
		// Receipts are never sent to the other peer. The scalar receipt is for
		// the first candidate, for clients using a single verifier.
		for i, value := range sig.Candidates {
			if len(s.stun) == 0 {
				break
			}
			a, err := netip.ParseAddrPort(value)
			if err != nil || a.Port() == 0 {
				continue
			}
			if a.Addr().Is6() && (!pair.ipv6 || (!p2p.IsPublicIPv6(a.Addr()) && !(s.cfg.P2PLocalTest && a.Addr().IsLoopback()))) {
				continue
			}
			verified := false
			if s.verifier != nil && (publicVerifierIP(a.Addr()) || (s.cfg.P2PLocalTest && a.Addr().IsLoopback())) {
				verified = (i == 0 && s.verifier.Check(ticket, sig.Proof, a, now)) ||
					s.verifier.Check(ticket, sig.Proofs[a.String()], a, now)
			}
			if a.Addr() == p.directIP || verified {
				forward.Candidates = append(forward.Candidates, a.String())
				if a.Addr().Is4() {
					hint := sig.PathMappings[a.String()]
					mapping, step := protocol.NormalizeMapping(hint.Mapping, hint.Step)
					if mapping != "" {
						if forward.PathMappings == nil {
							forward.PathMappings = make(map[string]protocol.P2PMappingHint)
						}
						forward.PathMappings[a.String()] = protocol.P2PMappingHint{Mapping: mapping, Step: step}
					}
				}
			}
		}
		if pair.lan {
			for _, value := range sig.Local {
				a, err := netip.ParseAddrPort(value)
				if err == nil && a.Port() != 0 && p2p.IsLANIPv4(a.Addr()) &&
					(!p.directIP.IsPrivate() || a.Addr() == p.directIP) {
					forward.Local = append(forward.Local, a.String())
				}
			}
		}
		// With a pin-only client offer, the Agent learns the address from a
		// session-authenticated UDP probe. The client still needs an Agent candidate.
		if len(forward.Candidates) == 0 && len(forward.Local) == 0 && (p == agent || !pair.clientInitiated) {
			reason := "client_candidate_unverified"
			if p == agent {
				reason = "agent_candidate_unverified"
			}
			s.stopDirectWithReason(client, reason)
			return
		}
		if p == agent {
			pair.agentOffer = true
		} else {
			pair.clientOffer = true
		}
	case protocol.P2PKindReady:
		if !pair.agentOffer || !pair.clientOffer {
			return
		}
		if p == agent {
			if pair.agentReady {
				return
			}
			pair.agentReady = true
		} else {
			if pair.clientReady {
				return
			}
			pair.clientReady = true
		}
	case protocol.P2PKindActivate:
		if p != client || !pair.agentReady || !pair.clientReady || pair.requested {
			return
		}
		pair.requested = true
	case protocol.P2PKindActive:
		if p != agent || !pair.requested || pair.active {
			return
		}
		pair.active = true
		pair.deadline = now.Add(protocol.P2PLease)
		s.sendDirect(agent, client, protocol.P2P(protocol.P2PKindLease, pair.id))
	case protocol.P2PKindRenew:
		if p != client || !pair.active {
			return
		}
		pair.deadline = now.Add(protocol.P2PLease)
		lease := protocol.P2P(protocol.P2PKindLease, pair.id)
		s.sendDirect(agent, client, lease)
		s.sendDirect(client, client, lease)
		return
	default:
		return
	}
	if next := pair.phase(); next != prevPhase {
		log.Printf("p2p %s id=%s phase %s -> %s", sig.Kind, pair.id, prevPhase, next)
	}
	s.sendDirect(other, client, forward)
}
