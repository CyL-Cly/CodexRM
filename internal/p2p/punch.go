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
	punchPing           byte = 1
	punchSelected       byte = 2
	punchConfirm        byte = 3
	punchTick                = 5 * time.Millisecond
	candidateRetry           = 40 * time.Millisecond
	lanHeadStart             = 600 * time.Millisecond
	maxPunchSockets          = 256
	maxProbeTargets          = 2048
	dualNATPunchTimeout      = 8 * time.Second
	dualNATProbeTargets      = 100
	dualNATPunchSockets      = 32
)

// shouldGrowLocal reports whether this egress should open extra source ports.
// The primary rule is punchStrategy.GrowLocal (local random-symmetric). A
// client offer with no mapping hint still grows when some remote IPv4 path
// is not linear; that fallback is compatibility, not a confirmed NAT type.
func shouldGrowLocal(localMapping string, full, agent, dualNATPunch, allRemoteLinear bool) bool {
	if !full || (agent && !dualNATPunch) {
		return false
	}
	if punchStrategyFor(localMapping, protocol.P2PMappingStable, true, true).GrowLocal {
		return true
	}
	return !agent && localMapping == "" && !allRemoteLinear
}

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
func expandedTargets(exact []netip.AddrPort, step, budget int) []netip.AddrPort {
	seen := make(map[netip.AddrPort]bool, budget)
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
		if !seen[a] && len(seen) < budget {
			seen[a] = true
			out = append(out, a)
		}
	}
	const minPort = 1024
	for _, a := range predictedTargets(exact, step) {
		add(a)
	}
	// Leave room for randomized ports even with the smaller dual-NAT budget.
	nearLimit := budget - budget/4
	for d := 1; d <= 64 && len(seen) < nearLimit; d++ {
		for _, a := range exact {
			if !canExpand(a.Addr()) {
				continue
			}
			for _, port := range [...]int{int(a.Port()) + d, int(a.Port()) - d} {
				if port >= minPort && port <= 65535 && len(seen) < nearLimit {
					add(netip.AddrPortFrom(a.Addr(), uint16(port)))
				}
			}
		}
	}
	for len(seen) < budget {
		p := uint16(minPort + rand.IntN(65536-minPort))
		for _, ip := range ips {
			add(netip.AddrPortFrom(ip, p))
		}
	}
	return out
}

type probe struct {
	socket int
	addr   netip.AddrPort
	kind   byte
}

type punchPlan struct {
	socket  int
	targets []netip.AddrPort
	after   time.Time
	index   int
}

func (s *Session) localHint(socket int) string {
	if socket < len(s.paths) {
		return s.paths[socket].hint.Mapping
	}
	return s.mapping
}

func (s *Session) plans(order []int, targets []netip.AddrPort, hints map[netip.AddrPort]protocol.P2PMappingHint, publicAfter time.Time, override time.Duration, budget int) []punchPlan {
	type group struct {
		ip   netip.Addr
		hint protocol.P2PMappingHint
	}
	grouped := make(map[group][]netip.AddrPort)
	var groups []group
	for _, addr := range targets {
		if !canExpand(addr.Addr()) {
			continue
		}
		g := group{addr.Addr(), hints[addr]}
		if len(grouped[g]) == 0 {
			groups = append(groups, g)
		}
		grouped[g] = append(grouped[g], addr)
	}
	var plans []punchPlan
	for _, socket := range order {
		if !supportsFamily(s.sockets[socket], false) {
			continue
		}
		for _, g := range groups {
			addrs := grouped[g]
			st := punchStrategyFor(s.localHint(socket), g.hint.Mapping, !s.disableFullPunch, s.agent || s.dualNATPunch)
			var expanded []netip.AddrPort
			if st.Predict {
				expanded = predictedTargets(addrs, g.hint.Step)
			}
			if st.Spray {
				// Share the probing budget across source sockets and remote paths.
				expanded = append(expanded, expandedTargets(addrs, 0, len(addrs)+max(1, budget/(len(groups)*len(order))))...)
			}
			if len(expanded) == 0 {
				continue
			}
			wait := exactWindow(s.localHint(socket), g.hint.Mapping)
			if override > 0 {
				wait = override
			}
			plans = append(plans, punchPlan{socket: socket, targets: expanded, after: publicAfter.Add(wait)})
		}
	}
	return plans
}

func (s *Session) punchPaths(ctx context.Context, targets, local []netip.AddrPort, hints map[netip.AddrPort]protocol.P2PMappingHint, exactFor time.Duration, dualNAT bool) (int, netip.AddrPort, error) {
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
	localIPs := make(map[netip.Addr]bool)
	var ipv4, ipv6 []netip.AddrPort
	for _, a := range targets {
		if a.Addr().Is4() {
			ipv4 = append(ipv4, a)
		} else {
			ipv6 = append(ipv6, a)
		}
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
				from = netip.AddrPortFrom(from.Addr().Unmap(), from.Port())
				if n != 38 || (!from.Addr().IsGlobalUnicast() && !from.Addr().IsLoopback()) {
					continue
				}
				kind := buf[5]
				if kind == 0 || kind > punchConfirm || !hmac.Equal(expected[kind], buf[:n]) {
					continue
				}
				// The session MAC authenticates an otherwise unknown source. Its observed
				// address may be the only usable mapping when NAT egress varies by destination.
				select {
				case <-stop:
					return
				case events <- probe{i, from, kind}:
				default:
				}
			}
		}()
	}
	if s.preflight && outboundBlocked(ctx) {
		s.disableFullPunch = true
	}
	order := s.rankedPaths()
	pathSockets := len(order)
	for _, i := range order {
		listen(i, s.sockets[i])
	}
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
	var nextPrimary, nextLocal, nextSelect, nextRepeat, nextReply time.Time
	var lastSelected probe
	extraIndex := pathSockets
	probeBudget, socketLimit := maxProbeTargets, maxPunchSockets
	if dualNAT {
		probeBudget, socketLimit = dualNATProbeTargets, dualNATPunchSockets
	}
	passive := s.agent && len(targets) == 0 && len(local) == 0
	publicRetry := candidateRetry
	if pathSockets*len(targets) > 1 {
		publicRetry = 100 * time.Millisecond
	}
	plans := s.plans(order, ipv4, hints, publicAfter, exactFor, probeBudget)
	nextPlan := 0
	var growPaths []int
	var growTargets []netip.AddrPort
	allLinear := true
	for _, addr := range ipv4 {
		if canExpand(addr.Addr()) {
			growTargets = append(growTargets, addr)
			allLinear = allLinear && hints[addr].Mapping == protocol.P2PMappingLinear
		}
	}
	for _, i := range order {
		if !supportsFamily(s.sockets[i], false) {
			continue
		}
		// Grow extra local ports when the NAT-pair strategy says so.
		// Linear and cone mappings stay on the sockets already bound.
		if shouldGrowLocal(s.localHint(i), !s.disableFullPunch, s.agent, s.dualNATPunch, allLinear) {
			growPaths = append(growPaths, i)
		}
	}
	if s.agent {
		// Keep unsolicited Agent traffic bounded. Old clients do not reply
		// to a ping from a new source socket.
		socketLimit = min(socketLimit, dualNATPunchSockets)
	}
	growAfter := publicAfter.Add(exactWindow(s.mapping, ""))
	if exactFor > 0 {
		growAfter = publicAfter.Add(exactFor)
	}
	nextGrow := 0
	// A candidate is only nominated after a confirm from that endpoint. Keep
	// probing meanwhile so a half-open pair cannot starve the other candidates.
	pending := make(map[probe]time.Time)
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
				if s.dualNATPunch && e.kind == punchPing && !now.Before(nextReply) {
					// An authenticated ping may arrive from a different mapping
					// than the address the Agent observed during STUN.
					send(e.socket, punchPing, e.addr)
					nextReply = now.Add(candidateRetry)
				}
				continue
			}
			nominee := probe{socket: e.socket, addr: e.addr}
			last := pending[nominee]
			if e.kind == punchConfirm && !last.IsZero() && now.Sub(last) < nominationLifetime {
				return e.socket, e.addr, nil
			}
			if e.kind != punchPing || now.Before(nextSelect) || now.Sub(last) < 100*time.Millisecond {
				continue
			}
			for a, sent := range pending {
				if now.Sub(sent) >= nominationLifetime {
					delete(pending, a)
				}
			}
			send(e.socket, punchSelected, e.addr)
			pending[nominee] = now
			// When there are no verified targets, only reply to incoming pings.
			if !passive {
				lastSelected = nominee
				nextRepeat = now.Add(100 * time.Millisecond)
			}
			nextSelect = now.Add(20 * time.Millisecond)
		case <-tick.C:
			now := time.Now()
			if lastSelected.addr.IsValid() && !now.Before(nextRepeat) && now.Sub(pending[lastSelected]) < nominationLifetime {
				send(lastSelected.socket, punchSelected, lastSelected.addr)
				nextRepeat = now.Add(100 * time.Millisecond)
			}
			if len(local) > 0 && !now.Before(nextLocal) {
				for _, i := range order {
					for _, a := range local {
						if i == 0 || (i < len(s.paths) && s.paths[i].source.LAN && onLinkIPv4(a.Addr(), []netip.Prefix{s.paths[i].source.Prefix})) {
							send(i, punchPing, a)
						}
					}
				}
				nextLocal = now.Add(candidateRetry)
			}
			if now.Before(publicAfter) {
				continue
			}
			if !now.Before(nextPrimary) {
				for _, i := range order {
					if supportsFamily(s.sockets[i], false) {
						pingAll(i, ipv4)
					}
					if supportsFamily(s.sockets[i], true) {
						pingAll(i, ipv6)
					}
				}
				nextPrimary = now.Add(publicRetry)
			}
			// One additional port guess per tick, round robin across ready pairs.
			for range plans {
				plan := &plans[nextPlan]
				nextPlan = (nextPlan + 1) % len(plans)
				if !now.Before(plan.after) {
					send(plan.socket, punchPing, plan.targets[plan.index])
					plan.index = (plan.index + 1) % len(plan.targets)
					break
				}
			}
			if len(growTargets) == 0 || now.Before(growAfter) {
				continue
			}
			// Vary the local UDP port on the same pinned source network. A stable
			// local mapping uses the original socket and needs no extra source ports.
			if len(growPaths) > 0 && len(s.sockets) < socketLimit {
				n := nextGrow % len(growPaths)
				i := growPaths[n]
				var c *net.UDPConn
				var err error
				if i != 0 && i < len(s.paths) {
					c, err = listenSource(ctx, s.paths[i].source)
					if err == nil {
						s.sockets = append(s.sockets, c)
					}
				} else {
					c, err = s.openSocket()
				}
				if err == nil {
					nextGrow++
					i = len(s.sockets) - 1
					listen(i, c)
					pingAll(i, growTargets)
					continue
				}
				growPaths = slices.Delete(growPaths, n, n+1)
			}
			if len(s.sockets) > pathSockets {
				pingAll(extraIndex, growTargets)
				extraIndex = pathSockets + (extraIndex-pathSockets+1)%(len(s.sockets)-pathSockets)
			}
		}
	}
}
