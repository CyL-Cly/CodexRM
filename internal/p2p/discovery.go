package p2p

import (
	"context"
	"net"
	"net/netip"
	"slices"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

type discoveryQuery struct {
	dst      netip.AddrPort
	request  []byte
	verifier bool
	mapped   netip.AddrPort
	proof    string
	sent     time.Time
	rtt      time.Duration
}

// All requests share one reader and the socket that will do the punching.
// Put verifiers first so the first candidate can carry a legacy receipt.
func discover(ctx context.Context, socket *net.UDPConn, start protocol.P2PSignal) []discoveryQuery {
	var queries []discoveryQuery
	if start.Ticket != "" {
		endpoints := append([]string{start.VerifyUDP}, start.VerifyMore...)
		seen := make(map[netip.AddrPort]bool)
		for _, endpoint := range endpoints {
			dst, err := netip.ParseAddrPort(endpoint)
			if err != nil || dst.Addr().Zone() != "" || (dst.Addr().Is6() && !start.IPv6) || !supportsFamily(socket, dst.Addr().Is6()) || dst.Port() == 0 || seen[dst] {
				continue
			}
			req := verifyRequestFor(start.Ticket, dst.Addr().Is6())
			if req == nil {
				break
			}
			queries = append(queries, discoveryQuery{dst: dst, request: req, verifier: true})
			seen[dst] = true
			if len(queries) == protocol.P2PMaxVerifiers {
				break
			}
		}
	}

	seen := make(map[netip.AddrPort]bool)
	for _, server := range start.STUN {
		dst, err := netip.ParseAddrPort(server)
		if err != nil || (!dst.Addr().Is4() && !start.IPv6) || !supportsFamily(socket, dst.Addr().Is6()) || dst.Addr().Zone() != "" || dst.Port() == 0 || seen[dst] {
			continue
		}
		if req := bindingRequest(); req != nil {
			queries = append(queries, discoveryQuery{dst: dst, request: req})
			seen[dst] = true
		}
	}
	if len(queries) == 0 {
		return queries
	}
	canceled := make(chan struct{})
	stop := context.AfterFunc(ctx, func() {
		_ = socket.SetReadDeadline(time.Now())
		close(canceled)
	})
	defer func() {
		if !stop() {
			<-canceled
		}
		_ = socket.SetReadDeadline(time.Time{})
	}()
	pending := len(queries)
	var buf [2048]byte
	for range 2 {
		deadline := time.Now().Add(700 * time.Millisecond)
		if end, ok := ctx.Deadline(); ok && end.Before(deadline) {
			deadline = end
		}
		_ = socket.SetReadDeadline(deadline)
		if ctx.Err() != nil {
			break
		}
		for i := range queries {
			q := &queries[i]
			if !q.mapped.IsValid() {
				if q.sent.IsZero() {
					q.sent = time.Now()
				}
				_, _ = socket.WriteToUDPAddrPort(q.request, q.dst)
			}
		}
		for pending > 0 && ctx.Err() == nil {
			n, from, err := socket.ReadFromUDPAddrPort(buf[:])
			if err != nil {
				break
			}
			from = netip.AddrPortFrom(from.Addr().Unmap(), from.Port())
			for i := range queries {
				q := &queries[i]
				if from != q.dst || q.mapped.IsValid() {
					continue
				}
				if q.verifier {
					q.mapped, q.proof = verifyReply(q.request, buf[:n])
				} else {
					mapped := bindingReply(q.request, buf[:n])
					if mapped.IsValid() && mapped.Addr().Is4() == q.dst.Addr().Is4() {
						q.mapped = mapped
					}
				}
				if q.mapped.IsValid() {
					q.rtt = time.Since(q.sent)
					pending--
					break
				}
			}
		}
		if pending == 0 {
			break
		}
	}
	return queries
}

// Advertise a hint only when all usable sockets agree. Different source ports
// are distinct mappings and must never be pooled into a single prediction.
func classifyPathMappings(paths [][]discoveryQuery) (string, int) {
	var hint string
	var step int
	found := false
	for _, path := range paths {
		ipv4 := false
		for _, q := range path {
			ipv4 = ipv4 || (q.mapped.Addr().Is4() && !q.verifier)
		}
		if !ipv4 {
			continue
		}
		h, d := classifyMapping(path)
		if found && (h != hint || d != step) {
			return "", 0
		}
		hint, step, found = h, d, true
	}

	return hint, step
}

func mappingHint(queries []discoveryQuery) string {
	hint, _ := classifyMapping(queries)
	return hint
}

// classifyMapping separates endpoint-independent, sequential, and unpredictable
// mappings. Two ports on one server IP are not enough to call a mapping stable,
// and the result says nothing about inbound filtering or the path to the peer.
func classifyMapping(queries []discoveryQuery) (string, int) {
	type sample struct {
		dstIP  netip.Addr
		mapped netip.AddrPort
	}
	var samples []sample
	seenDst := make(map[netip.AddrPort]struct{})
	for _, q := range queries {
		if q.verifier || !q.mapped.Addr().Is4() {
			continue
		}
		if _, ok := seenDst[q.dst]; ok {
			continue
		}
		seenDst[q.dst] = struct{}{}
		samples = append(samples, sample{q.dst.Addr(), q.mapped})
	}
	if len(samples) < 2 {
		return "", 0
	}
	base := samples[0].mapped.Addr()
	distinctIP := false
	ports := make([]int, 0, len(samples))
	seenPort := make(map[uint16]struct{})
	for i, s := range samples {
		if s.mapped.Addr() != base {
			return protocol.P2PMappingVaries, 0
		}
		for _, older := range samples[:i] {
			if s.dstIP != older.dstIP {
				distinctIP = true
			}
		}
		if _, ok := seenPort[s.mapped.Port()]; ok {
			continue
		}
		seenPort[s.mapped.Port()] = struct{}{}
		ports = append(ports, int(s.mapped.Port()))
	}
	if !distinctIP {
		if len(ports) > 1 {
			return protocol.P2PMappingVaries, 0
		}
		return "", 0
	}
	if len(ports) == 1 {
		return protocol.P2PMappingStable, 0
	}
	slices.Sort(ports)
	step := ports[1] - ports[0]
	if step < 1 || step > 32 {
		return protocol.P2PMappingVaries, 0
	}
	for i := 2; i < len(ports); i++ {
		if ports[i]-ports[i-1] != step {
			return protocol.P2PMappingVaries, 0
		}
	}
	return protocol.P2PMappingLinear, step
}
