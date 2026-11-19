package server

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
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

type directPair struct {
	id                                                                  string
	agent                                                               *peer
	deadline                                                            time.Time
	agentTicket, clientTicket                                           string
	lan, ipv6                                                           bool
	agentOffer, clientOffer, agentReady, clientReady, requested, active bool
}

func (s *Server) startVerifier() error {
	if s.cfg.P2PVerifyListen == "" && s.cfg.P2PVerifyPublic == "" && s.cfg.P2PVerifyMore == "" && s.cfg.P2PVerifyKeyFile == "" {
		return nil
	}
	local, le := netip.ParseAddrPort(s.cfg.P2PVerifyListen)
	public, pe := netip.ParseAddrPort(s.cfg.P2PVerifyPublic)
	if len(s.stun) == 0 || le != nil || !local.Addr().Is4() || pe != nil || !public.Addr().Is4() ||
		(!s.cfg.P2PLocalTest && (local.Port() == 0 || public.Port() == 0 || !public.Addr().IsGlobalUnicast() || public.Addr().IsPrivate())) {
		return fmt.Errorf("P2P verification requires STUN plus local and advertised IPv4:UDP endpoints")
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
		if err != nil || !addr.Addr().Is4() || addr.Port() == 0 ||
			addr == public || slices.Contains(s.verifyMore, addr.String()) || len(s.verifyMore) >= protocol.P2PMaxVerifiers-1 ||
			(!s.cfg.P2PLocalTest && (!addr.Addr().IsGlobalUnicast() || addr.Addr().IsPrivate())) {
			return fmt.Errorf("P2P verification requires up to %d distinct additional public IPv4:UDP endpoints", protocol.P2PMaxVerifiers-1)
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
		stop := protocol.P2P("stop", pair.id)
		stop.Reason = reason
		s.sendDirect(pair.agent, client, stop)
		s.sendDirect(client, client, stop)
	}
}

func directAlive(p *peer, now time.Time) bool {
	if p == nil || !p.directIP.IsValid() || (!p.expires.IsZero() && !now.Before(p.expires)) {
		return false
	}
	select {
	case <-p.closed:
		return false
	default:
		return true
	}
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
	if (len(s.stun) == 0 && !s.cfg.P2PLAN) || sig == nil || sig.Version != protocol.P2PVersion {
		return
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	if !directAlive(p, now) {
		return
	}
	var agent, client *peer
	if p.role == protocol.RoleAgent {
		if s.agents[p.agentID] != p {
			return
		}
		if sig.Kind == "hello" {
			if p.directCapable {
				return
			}
			p.directCapable = true
			p.dualNATPunchCapable = sig.DualNATPunch
			p.ipv6Capable = sig.IPv6
			p.lanCapable = sig.LAN
			routing := protocol.P2P("routing", "")
			for id := range s.byAgent[p.agentID] {
				routing.Clients = append(routing.Clients, id)
			}
			s.send(p, protocol.Envelope{Type: protocol.TypeP2P, P2P: routing})
			for _, c := range s.byAgent[p.agentID] {
				if directAlive(c, now) && s.directAvailable(p, c) {
					s.sendDirect(c, c, protocol.P2P("available", ""))
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
	if sig.Kind == "begin" && p == client {
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
			s.sendDirect(client, client, protocol.P2P("busy", ""))
			return
		}
		var key [32]byte
		if _, err := rand.Read(key[:]); err != nil {
			return
		}
		pair = &directPair{id: uuid.NewString(), agent: agent, deadline: now.Add(protocol.P2PAttempt), lan: lan, ipv6: ipv6}
		start := protocol.P2P("start", pair.id)
		start.Secret, start.STUN = hex.EncodeToString(key[:]), stun
		start.LAN, start.IPv6 = lan, ipv6
		start.DualNATPunch = len(stun) > 0 && agent.dualNATPunchCapable && sig.DualNATPunch
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
	forward := protocol.P2P(sig.Kind, pair.id)
	switch sig.Kind {
	case "stop":
		if p == agent && !pair.active && pair.agentOffer && pair.clientOffer && sig.Reason == protocol.P2PReasonDualUnpredictableNAT {
			s.stopDirectWithReason(client, sig.Reason)
		} else {
			s.stopDirect(client)
		}
		return
	case "offer":
		if (p == agent && pair.agentOffer) || (p == client && pair.clientOffer) {
			return
		}
		pin, err := hex.DecodeString(sig.Pin)
		if err != nil || len(pin) != 32 || len(sig.Candidates) > protocol.P2PMaxCandidates || len(sig.Local) > protocol.P2PMaxCandidates || len(sig.Proofs) > protocol.P2PMaxVerifiers {
			s.stopDirectWithReason(client, "invalid_offer")
			return
		}
		forward.Pin = sig.Pin
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
			if s.verifier != nil && ((a.Addr().IsGlobalUnicast() && !a.Addr().IsPrivate()) || (s.cfg.P2PLocalTest && a.Addr().IsLoopback())) {
				verified = (i == 0 && s.verifier.Check(ticket, sig.Proof, a, now)) ||
					(len(s.verifyMore) > 0 && s.verifier.Check(ticket, sig.Proofs[a.String()], a, now))
			}
			if a.Addr() == p.directIP || verified {
				forward.Candidates = append(forward.Candidates, a.String())
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
		if len(forward.Candidates) == 0 && len(forward.Local) == 0 {
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
	case "ready":
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
	case "activate":
		if p != client || !pair.agentReady || !pair.clientReady || pair.requested {
			return
		}
		pair.requested = true
	case "active":
		if p != agent || !pair.requested || pair.active {
			return
		}
		pair.active = true
		pair.deadline = now.Add(protocol.P2PLease)
		s.sendDirect(agent, client, protocol.P2P("lease", pair.id))
	case "renew":
		if p != client || !pair.active {
			return
		}
		pair.deadline = now.Add(protocol.P2PLease)
		lease := protocol.P2P("lease", pair.id)
		s.sendDirect(agent, client, lease)
		s.sendDirect(client, client, lease)
		return
	default:
		return
	}
	s.sendDirect(other, client, forward)
}
