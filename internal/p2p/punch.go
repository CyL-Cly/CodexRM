package p2p

import (
	"context"
	"crypto/hmac"
	"fmt"
	"math/rand/v2"
	"net"
	"net/netip"
	"slices"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

const (
	punchPing       byte = 1
	punchSelected   byte = 2
	punchConfirm    byte = 3
	punchTick            = 5 * time.Millisecond
	candidateRetry       = 40 * time.Millisecond
	lanHeadStart         = 600 * time.Millisecond
	maxPunchSockets      = 256
	maxProbeTargets      = 2048
)

func exactWindow(local, remote string) time.Duration {
	switch {
	case local == protocol.P2PMappingVaries || remote == protocol.P2PMappingVaries:
		return 120 * time.Millisecond
	case local == protocol.P2PMappingStable && remote == protocol.P2PMappingStable:
		return 800 * time.Millisecond
	case remote == protocol.P2PMappingLinear:
		return 200 * time.Millisecond
	default:
		return 250 * time.Millisecond
	}
}

var sharedIPv4 = netip.MustParsePrefix("100.64.0.0/10")

func canExpand(ip netip.Addr) bool {
	return ip.Is4() && ip.IsGlobalUnicast() && !ip.IsPrivate() && !sharedIPv4.Contains(ip)
}

// predictedTargets lists a few ports at a repeated interval around candidates the
// peer already published. It does not claim to know the NAT's next allocation.
func predictedTargets(exact []netip.AddrPort, step int) []netip.AddrPort {
	if step < 1 || step > 32 {
		return nil
	}
	seen := make(map[netip.AddrPort]bool, len(exact))
	for _, a := range exact {
		seen[a] = true
	}
	var out []netip.AddrPort
	add := func(ip netip.Addr, port int) {
		if port < 1024 || port > 65535 || !canExpand(ip) {
			return
		}
		a := netip.AddrPortFrom(ip, uint16(port))
		if !seen[a] {
			seen[a] = true
			out = append(out, a)
		}
	}
	for n := 1; n <= 8; n++ {
		for _, a := range exact {
			add(a.Addr(), int(a.Port())+n*step)
			add(a.Addr(), int(a.Port())-n*step)
		}
	}
	return out
}

// Start with the observed step, then nearby ports, then other ports on the same addresses.
func expandedTargets(exact []netip.AddrPort, step int) []netip.AddrPort {
	seen := make(map[netip.AddrPort]bool, maxProbeTargets)
	var out []netip.AddrPort
	var ips []netip.Addr
	for _, a := range exact {
		seen[a] = true
		if canExpand(a.Addr()) && !slices.Contains(ips, a.Addr()) {
			ips = append(ips, a.Addr())
		}
	}
	if len(ips) == 0 {
		return nil
	}
	add := func(a netip.AddrPort) {
		if !seen[a] && len(seen) < maxProbeTargets {
			seen[a] = true
			out = append(out, a)
		}
	}
	const minPort = 1024
	for _, a := range predictedTargets(exact, step) {
		add(a)
	}
	for d := 1; d <= 64; d++ {
		for _, a := range exact {
			if !canExpand(a.Addr()) {
				continue
			}
			for _, port := range [...]int{int(a.Port()) + d, int(a.Port()) - d} {
				if port >= minPort && port <= 65535 {
					add(netip.AddrPortFrom(a.Addr(), uint16(port)))
				}
			}
		}
	}
	for len(seen) < maxProbeTargets {
		p := uint16(minPort + rand.IntN(65536-minPort))
		for _, ip := range ips {
			add(netip.AddrPortFrom(ip, p))
		}
	}
	return out
}

type agentProbes struct {
	exact, extra           []netip.AddrPort
	exactIndex, extraIndex int
	step                   int
	expanded               bool
	nextExact              time.Time
}

func (p *agentProbes) next(now, expandAt time.Time) netip.AddrPort {
	if len(p.exact) == 0 {
		return netip.AddrPort{}
	}
	if !now.Before(expandAt) && !p.expanded {
		p.extra, p.expanded = expandedTargets(p.exact, p.step), true
	}
	if p.exactIndex < len(p.exact) || !now.Before(p.nextExact) {
		a := p.exact[p.exactIndex%len(p.exact)]
		p.exactIndex++
		p.nextExact = now.Add(candidateRetry)
		return a
	}
	if len(p.extra) != 0 {
		a := p.extra[p.extraIndex%len(p.extra)]
		p.extraIndex++
		return a
	}
	return netip.AddrPort{}
}

type probe struct {
	socket int
	addr   netip.AddrPort
	kind   byte
}

func (s *Session) punch(ctx context.Context, targets, local []netip.AddrPort, exactFor time.Duration, step int) (int, netip.AddrPort, error) {
	role, other := byte(1), byte(2)
	if s.agent {
		role, other = other, role
	}
	packet, expected := [4][]byte{}, [4][]byte{}
	for _, kind := range [...]byte{punchPing, punchSelected, punchConfirm} {
		packet[kind], expected[kind] = s.packet(role, kind), s.packet(other, kind)
	}
	var wg sync.WaitGroup
	stop := make(chan struct{})
	defer func() {
		close(stop)
		for _, c := range s.sockets {
			_ = c.SetReadDeadline(time.Now())
		}
		wg.Wait()
		for _, c := range s.sockets {
			_ = c.SetReadDeadline(time.Time{})
		}
	}()
	events := make(chan probe, 1024)
	publicIPs, localIPs := make(map[netip.Addr]bool), make(map[netip.Addr]bool)
	grow := false
	for _, a := range targets {
		publicIPs[a.Addr()] = true
		grow = grow || canExpand(a.Addr())
	}
	for _, a := range local {
		localIPs[a.Addr()] = true
	}
	listen := func(i int, c *net.UDPConn) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var buf [64]byte
			for {
				n, from, err := c.ReadFromUDPAddrPort(buf[:])
				if err != nil {
					return
				}
				if n != 38 || (!publicIPs[from.Addr()] && !localIPs[from.Addr()]) {
					continue
				}
				kind := buf[5]
				if kind == 0 || kind > punchConfirm || !hmac.Equal(expected[kind], buf[:n]) {
					continue
				}
				select {
				case <-stop:
					return
				case events <- probe{i, from, kind}:
				default:
				}
			}
		}()
	}
	listen(0, s.sockets[0])
	send := func(socket int, kind byte, dst netip.AddrPort) {
		_, _ = s.sockets[socket].WriteToUDPAddrPort(packet[kind], dst)
	}
	pingAll := func(socket int, addrs []netip.AddrPort) {
		for _, a := range addrs {
			send(socket, punchPing, a)
		}
	}
	tick := time.NewTicker(punchTick)
	defer tick.Stop()
	publicAfter := time.Now()
	if len(local) > 0 {
		publicAfter = publicAfter.Add(lanHeadStart)
	}
	expandAt := publicAfter.Add(exactFor)
	var nextPrimary, nextLocal, nextSelect, nextRepeat time.Time
	var lastSelected netip.AddrPort
	extraIndex := 1
	probes := agentProbes{exact: targets, step: step}
	predicted := predictedTargets(targets, step)
	predIndex := 0
	var nextPred time.Time
	// A candidate is only nominated after a confirm from that endpoint. Keep
	// probing meanwhile so a half-open pair cannot starve the other candidates.
	pending := make(map[netip.AddrPort]time.Time)
	const nominationLifetime = 2 * time.Second
	for {
		select {
		case <-ctx.Done():
			return -1, netip.AddrPort{}, fmt.Errorf("%w (sockets=%d): %w", ErrUDPPunch, len(s.sockets), ctx.Err())
		case e := <-events:
			now := time.Now()
			if now.Before(publicAfter) && !localIPs[e.addr.Addr()] {
				continue
			}
			if !s.agent {
				if e.kind == punchSelected {
					send(e.socket, punchConfirm, e.addr)
					return e.socket, e.addr, nil
				}
				continue
			}
			last := pending[e.addr]
			if e.kind == punchConfirm && !last.IsZero() && now.Sub(last) < nominationLifetime {
				return 0, e.addr, nil
			}
			if e.kind != punchPing || now.Before(nextSelect) || now.Sub(last) < 100*time.Millisecond {
				continue
			}
			for a, sent := range pending {
				if now.Sub(sent) >= nominationLifetime {
					delete(pending, a)
				}
			}
			send(0, punchSelected, e.addr)
			pending[e.addr] = now
			lastSelected = e.addr
			nextRepeat = now.Add(100 * time.Millisecond)
			nextSelect = now.Add(20 * time.Millisecond)
		case <-tick.C:
			now := time.Now()
			if lastSelected.IsValid() && !now.Before(nextRepeat) && now.Sub(pending[lastSelected]) < nominationLifetime {
				send(0, punchSelected, lastSelected)
				nextRepeat = now.Add(100 * time.Millisecond)
			}
			if len(local) > 0 && !now.Before(nextLocal) {
				pingAll(0, local)
				nextLocal = now.Add(candidateRetry)
			}
			if len(targets) == 0 || now.Before(publicAfter) {
				continue
			}
			if s.agent {
				if a := probes.next(now, expandAt); a.IsValid() {
					send(0, punchPing, a)
				}
				continue
			}
			if !now.Before(nextPrimary) {
				pingAll(0, targets)
				nextPrimary = now.Add(candidateRetry)
			}
			if now.Before(expandAt) {
				continue
			}
			if len(predicted) > 0 && !now.Before(nextPred) {
				send(0, punchPing, predicted[predIndex%len(predicted)])
				predIndex++
				nextPred = now.Add(punchTick)
			}
			// Extra source ports help when our own mapping changes per destination.
			// A sequential remote mapping is handled by the predicted ports above.
			if step >= 1 && step <= 32 && s.mapping != protocol.P2PMappingVaries {
				continue
			}
			if grow && len(s.sockets) < maxPunchSockets {
				c, err := s.openSocket()
				if err != nil {
					// Existing sockets still work even when we hit a local resource limit.
					grow = false
				} else {
					i := len(s.sockets) - 1
					listen(i, c)
					pingAll(i, targets)
					continue
				}
			}
			if len(s.sockets) > 1 {
				pingAll(extraIndex, targets)
				extraIndex = 1 + extraIndex%(len(s.sockets)-1)
			}
		}
	}
}
