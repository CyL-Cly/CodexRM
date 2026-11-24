package p2p

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

const (
	changePort = 2
	changeIP   = 4
	natRound   = 700 * time.Millisecond
	natSpacing = 400 * time.Millisecond
)

func natRequest(legacy bool, flags uint32) []byte {
	b := bindingRequest()
	if b == nil {
		return nil
	}
	if legacy {
		// RFC 3489 uses all 16 bytes as its transaction ID, with no cookie.
		if _, err := rand.Read(b[4:8]); err != nil {
			return nil
		}
		if binary.BigEndian.Uint32(b[4:8]) == 0x2112a442 {
			b[4] ^= 1
		}
	}
	if flags != 0 {
		b = append(b, 0, 3, 0, 4, 0, 0, 0, byte(flags))
		binary.BigEndian.PutUint16(b[2:], 8)
	}
	return b
}

// One STUN service can rate-limit an entire client IP, not just a socket.
// Pace initial sends and retransmissions across the two independent suites.
type natPacer struct {
	mu   sync.Mutex
	next time.Time
}

func (p *natPacer) wait(ctx context.Context) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if delay := time.Until(p.next); delay > 0 {
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return false
		case <-timer.C:
		}
	}
	if ctx.Err() != nil {
		return false
	}
	p.next = time.Now().Add(natSpacing)
	return true
}

// Only configured STUN endpoints and their validated alternate addresses may
// be queried. Accept responses from the expected address and transaction only.
func natQuery(ctx context.Context, c *net.UDPConn, pace *natPacer, dst, source netip.AddrPort, legacy bool, flags uint32) (stunAddresses, string) {
	request := natRequest(legacy, flags)
	var buf [2048]byte
	failure := protocol.NATNoResponse
	for range 2 {
		if request == nil || !pace.wait(ctx) {
			break
		}
		end := time.Now().Add(natRound)
		if deadline, ok := ctx.Deadline(); ok && deadline.Before(end) {
			end = deadline
		}
		_ = c.SetReadDeadline(end)
		if _, err := c.WriteToUDPAddrPort(request, dst); err != nil {
			break
		}
		for ctx.Err() == nil {
			n, from, err := c.ReadFromUDPAddrPort(buf[:])
			if err != nil {
				break
			}
			if from != source && from != dst {
				continue
			}
			if code := bindingErrorCode(request, buf[:n]); code != 0 {
				if code == 420 && flags != 0 {
					return stunAddresses{}, protocol.NATUnsupported
				}
				// Explicit errors (auth, server overload, etc.) are not evidence of NAT filtering.
				failure = protocol.NATUnknown
				continue
			}
			res := parseBindingReply(request, buf[:n])
			if !res.mapped.Addr().Is4() {
				continue
			}
			if from == source {
				return res, ""
			}
			failure = protocol.NATUnsupported
		}
		if failure == protocol.NATUnsupported {
			break
		}
	}
	return stunAddresses{}, failure
}

func validNATAlternate(base, alt netip.AddrPort) bool {
	return alt.Addr().Is4() && alt.Port() != 0 && alt.Addr() != base.Addr() && alt.Port() != base.Port() &&
		(canExpand(alt.Addr()) || (base.Addr().IsLoopback() && alt.Addr().IsLoopback()))
}

type natBaseline struct {
	pace             *natPacer
	socket           *net.UDPConn
	dst, alt, mapped netip.AddrPort
}

type natOutcome struct {
	mapping, filtering string // Classic mode stores its classification in mapping only.
}

func (r natOutcome) score() int {
	score := func(value string) int {
		switch value {
		case "", protocol.NATNoResponse:
			return 0
		case protocol.NATUnsupported:
			return 1
		case protocol.NATUnknown:
			return 2
		default:
			return 10
		}
	}
	return score(r.mapping) + score(r.filtering)
}

func natAttempt(ctx context.Context, dst netip.AddrPort, pace *natPacer, legacy bool) natOutcome {
	failed := func(status string) natOutcome {
		if legacy {
			return natOutcome{mapping: status}
		}
		return natOutcome{mapping: status, filtering: status}
	}
	c, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return failed(protocol.NATUnknown)
	}
	defer c.Close()
	stop := context.AfterFunc(ctx, func() { _ = c.Close() })
	defer stop()
	r, status := natQuery(ctx, c, pace, dst, dst, legacy, 0)
	if !r.mapped.IsValid() {
		return failed(status)
	}
	alt := r.other
	if legacy {
		alt = r.changed
	}
	if !validNATAlternate(dst, alt) {
		return failed(protocol.NATUnsupported)
	}
	p := natBaseline{pace, c, dst, alt, r.mapped}
	if legacy {
		return natOutcome{mapping: natClassic(ctx, p)}
	}
	// Filtering must run before this socket contacts any alternate destination.
	filtering := natFiltering(ctx, p)
	return natOutcome{mapping: natMapping(ctx, p), filtering: filtering}
}

func runNATSuite(ctx context.Context, endpoints []netip.AddrPort, pacers map[netip.Addr]*natPacer, legacy bool) natOutcome {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	// Each endpoint gets an independent socket. A broken server can advertise
	// an alternate but fail subsequent probes, so race complete tests, not
	// Binding replies. Never combine fields from two different paths.
	results := make(chan natOutcome, len(endpoints))
	for _, dst := range endpoints {
		go func() { results <- natAttempt(ctx, dst, pacers[dst.Addr()], legacy) }()
	}
	best := natOutcome{mapping: protocol.NATNoResponse}
	complete := 10
	if !legacy {
		best.filtering = protocol.NATNoResponse
		complete = 20
	}
	for range endpoints {
		r := <-results
		if r.score() >= complete {
			return r
		}
		if r.score() > best.score() {
			best = r
		}
	}
	return best
}

// Reachability is checked only AFTER the changed-source probes. Priming the
// tested socket before filtering would open the very NAT path we are testing.
func natReachable(ctx context.Context, p natBaseline, legacy bool, endpoints ...netip.AddrPort) bool {
	c, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return false
	}
	defer c.Close()
	for _, addr := range endpoints {
		r, _ := natQuery(ctx, c, p.pace, addr, addr, legacy, 0)
		if !r.mapped.IsValid() {
			return false
		}
	}
	return true
}

func natFiltering(ctx context.Context, p natBaseline) string {
	if r, failure := natQuery(ctx, p.socket, p.pace, p.dst, p.alt, false, changeIP|changePort); r.mapped.IsValid() {
		return protocol.NATEndpointIndependent
	} else if failure != protocol.NATNoResponse {
		return failure
	}
	changedPort := netip.AddrPortFrom(p.dst.Addr(), p.alt.Port())
	r, failure := natQuery(ctx, p.socket, p.pace, p.dst, changedPort, false, changePort)
	if failure != "" && failure != protocol.NATNoResponse {
		return failure
	}
	if r.mapped.IsValid() {
		if !natReachable(ctx, p, false, p.alt) {
			return protocol.NATUnknown
		}
		return protocol.NATAddressDependent
	}
	if !natReachable(ctx, p, false, p.dst, p.alt, changedPort) {
		return protocol.NATUnknown
	}
	return protocol.NATAddressPortDependent
}

func natMapping(ctx context.Context, p natBaseline) string {
	otherIP := netip.AddrPortFrom(p.alt.Addr(), p.dst.Port())
	r, _ := natQuery(ctx, p.socket, p.pace, otherIP, otherIP, false, 0)
	if !r.mapped.IsValid() {
		return protocol.NATUnknown
	}
	if r.mapped == p.mapped {
		return protocol.NATEndpointIndependent
	}
	a, _ := natQuery(ctx, p.socket, p.pace, p.alt, p.alt, false, 0)
	if !a.mapped.IsValid() {
		return protocol.NATUnknown
	}
	if r.mapped == a.mapped {
		return protocol.NATAddressDependent
	}
	return protocol.NATAddressPortDependent
}

func natClassic(ctx context.Context, p natBaseline) string {
	r, failure := natQuery(ctx, p.socket, p.pace, p.dst, p.alt, true, changeIP|changePort)
	if failure != "" && failure != protocol.NATNoResponse {
		return failure
	}
	// Discover the routed local address without sending a datagram; use the
	// port of the single socket that actually performed the STUN tests.
	if route, err := net.DialUDP("udp4", nil, net.UDPAddrFromAddrPort(p.dst)); err == nil {
		localIP := route.LocalAddr().(*net.UDPAddr).AddrPort().Addr().Unmap()
		_ = route.Close()
		if p.mapped == netip.AddrPortFrom(localIP, p.socket.LocalAddr().(*net.UDPAddr).AddrPort().Port()) {
			if r.mapped.IsValid() {
				return "open_internet"
			}
			if natReachable(ctx, p, true, p.dst, p.alt) {
				return "udp_firewall"
			}
			return protocol.NATUnknown
		}
	} else {
		return protocol.NATUnknown
	}
	if r.mapped.IsValid() {
		return "full_cone"
	}
	alt, _ := natQuery(ctx, p.socket, p.pace, p.alt, p.alt, true, 0)
	if !alt.mapped.IsValid() {
		return protocol.NATUnknown
	}
	if alt.mapped != p.mapped {
		return "symmetric"
	}
	changedPort := netip.AddrPortFrom(p.dst.Addr(), p.alt.Port())
	if res, failure := natQuery(ctx, p.socket, p.pace, p.dst, changedPort, true, changePort); res.mapped.IsValid() {
		return "restricted_cone"
	} else if failure != protocol.NATNoResponse {
		return failure
	}
	if !natReachable(ctx, p, true, p.dst, changedPort) {
		return protocol.NATUnknown
	}
	return "port_restricted_cone"
}

// DiagnoseNAT runs the two IPv4 tests concurrently on independent sockets. A
// basic STUN reply alone is not proof of support for either behavior test.
func DiagnoseNAT(ctx context.Context, configured []string) protocol.NATReport {
	ctx, cancel := context.WithTimeout(ctx, protocol.NATProbeTimeout)
	defer cancel()
	var endpoints []netip.AddrPort
	var normalized []string
	seen := make(map[netip.AddrPort]bool)
	pacers := make(map[netip.Addr]*natPacer)
	for _, raw := range configured {
		a, err := netip.ParseAddrPort(raw)
		if err == nil && a.Addr().Is4() && a.Port() != 0 && !seen[a] {
			endpoints = append(endpoints, a)
			normalized = append(normalized, a.String())
			seen[a] = true
			if pacers[a.Addr()] == nil {
				pacers[a.Addr()] = &natPacer{}
			}
		}
		if len(endpoints) == 4 {
			break
		}
	}
	classic := make(chan natOutcome, 1)
	go func() { classic <- runNATSuite(ctx, endpoints, pacers, true) }()
	modern := runNATSuite(ctx, endpoints, pacers, false)
	r := protocol.NATReport{Mapping: modern.mapping, Filtering: modern.filtering}
	if r.Mapping == protocol.NATUnsupported && len(endpoints) > 1 {
		if c, err := net.ListenUDP("udp4", nil); err == nil {
			defer c.Close()
			r.MappingSample, _ = classifyMapping(discover(ctx, c, protocol.P2PSignal{STUN: normalized}))
		}
	}
	r.RFC3489 = (<-classic).mapping
	return r
}
