package p2p

import (
	"bytes"
	"context"
	"errors"
	"net"
	"net/netip"
	"slices"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestExpandedTargetsKeepsMultiplePublicIPs(t *testing.T) {
	a := netip.MustParseAddrPort("203.0.113.1:40000")
	b := netip.MustParseAddrPort("203.0.113.2:65535")
	private := netip.MustParseAddrPort("192.168.2.10:40000")
	exact := []netip.AddrPort{a, b, private}
	extra := expandedTargets(exact, 0, maxProbeTargets)
	if got := extra[0]; got != netip.MustParseAddrPort("203.0.113.1:40001") {
		t.Fatalf("did not begin with the closest neighbor: %v", got)
	}
	if len(extra)+len(exact) != maxProbeTargets {
		t.Fatalf("unexpected probe count: %d", len(extra))
	}
	seen := make(map[netip.AddrPort]bool)
	seenIP := make(map[netip.Addr]bool)
	for _, addr := range extra {
		if seen[addr] || slices.Contains(exact, addr) || addr.Port() < 1024 || (addr.Addr() != a.Addr() && addr.Addr() != b.Addr()) {
			t.Fatalf("unexpected expanded target %v", addr)
		}
		seen[addr], seenIP[addr.Addr()] = true, true
	}
	if !seenIP[a.Addr()] || !seenIP[b.Addr()] {
		t.Fatalf("missing egress: %v", seenIP)
	}
	if got := expandedTargets([]netip.AddrPort{private, netip.MustParseAddrPort("100.64.1.1:40000")}, 0, maxProbeTargets); len(got) != 0 {
		t.Fatal("expanded non-public addresses")
	}
}

func TestDualNATProbeBudget(t *testing.T) {
	exact := []netip.AddrPort{netip.MustParseAddrPort("203.0.113.8:20000"), netip.MustParseAddrPort("192.168.1.8:20000")}
	extra := expandedTargets(exact, 0, dualNATProbeTargets)
	if len(exact)+len(extra) != dualNATProbeTargets {
		t.Fatalf("unexpected dual NAT target count: %d", len(exact)+len(extra))
	}
	seen := make(map[netip.AddrPort]bool)
	for _, addr := range extra {
		if seen[addr] || slices.Contains(exact, addr) || addr.Addr() != exact[0].Addr() || addr.Port() < 1024 {
			t.Fatalf("unexpected expanded address %v", addr)
		}
		seen[addr] = true
	}
	if extra[0].Port() != 20001 {
		t.Fatalf("nearest candidate was not tried first: %v", extra[0])
	}
}

func TestLinearStepPrecedesNeighborhood(t *testing.T) {
	exact := netip.MustParseAddrPort("203.0.113.8:20000")
	extra := expandedTargets([]netip.AddrPort{exact}, 4, maxProbeTargets)
	if got := extra[0]; got != netip.MustParseAddrPort("203.0.113.8:20004") {
		t.Fatalf("did not prioritize predicted port: %v", got)
	}
}

func TestPunchContinuesPastAnUnconfirmedPair(t *testing.T) {
	agent, src := loopbackUDP(t)
	halfOpen, a := loopbackUDP(t)
	working, b := loopbackUDP(t)
	s := &Session{agent: true, id: "a test session", key: bytes.Repeat([]byte{7}, 32), sockets: []*net.UDPConn{agent}}
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
	defer cancel()
	type result struct {
		addr netip.AddrPort
		err  error
	}
	done := make(chan result, 1)
	go func() {
		_, addr, err := s.punchPaths(ctx, []netip.AddrPort{a, b}, nil, nil, exactWindow("", ""), false)
		done <- result{addr, err}
	}()
	waitSelected := func(c *net.UDPConn) {
		t.Helper()
		if err := c.SetDeadline(time.Now().Add(time.Second)); err != nil {
			t.Fatal(err)
		}
		var buf [64]byte
		for {
			if _, err := c.WriteToUDPAddrPort(s.packet(1, punchPing), src); err != nil {
				t.Fatal(err)
			}
			_ = c.SetReadDeadline(time.Now().Add(50 * time.Millisecond))
			n, from, err := c.ReadFromUDPAddrPort(buf[:])
			if err != nil && !errors.Is(err, context.DeadlineExceeded) {
				if e, ok := err.(net.Error); !ok || !e.Timeout() {
					t.Fatal(err)
				}
			}
			if from == src && bytes.Equal(buf[:n], s.packet(2, punchSelected)) {
				return
			}
			if ctx.Err() != nil {
				t.Fatal("Agent did not select the candidate")
			}
		}
	}
	waitSelected(halfOpen)
	// A confirm from an endpoint that hasn't received a selection is ignored.
	if _, err := working.WriteToUDPAddrPort(s.packet(1, punchConfirm), src); err != nil {
		t.Fatal(err)
	}
	waitSelected(working)
	if _, err := working.WriteToUDPAddrPort(s.packet(1, punchConfirm), src); err != nil {
		t.Fatal(err)
	}
	if r := <-done; r.err != nil || r.addr != b {
		t.Fatalf("punch = %+v, want %v", r, b)
	}
}

func TestMobileUsesOneSocketForFastPath(t *testing.T) {
	mobile, dst := loopbackUDP(t)
	agent, src := loopbackUDP(t)
	s := &Session{id: "fast path", key: make([]byte, 32), sockets: []*net.UDPConn{mobile}}
	if _, err := agent.WriteToUDPAddrPort(s.packet(2, punchSelected), dst); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	// The alternate target makes expansion eligible, but we should not reach it.
	targets := []netip.AddrPort{src, netip.MustParseAddrPort("192.0.2.8:40000")}
	i, addr, err := s.punchPaths(ctx, targets, nil, nil, exactWindow(protocol.P2PMappingStable, protocol.P2PMappingStable), false)
	if err != nil || i != 0 || addr != src || len(s.sockets) != 1 {
		t.Fatalf("fast path socket=%d addr=%v err=%v sockets=%d", i, addr, err, len(s.sockets))
	}
}

func TestPunchRejectsUnauthenticatedUnknownSource(t *testing.T) {
	agentConn, agentAddr := loopbackUDP(t)
	mobileConn, _ := loopbackUDP(t)
	agent := &Session{agent: true, id: "peer reflexive", key: bytes.Repeat([]byte{9}, 32), sockets: []*net.UDPConn{agentConn}}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, _, err := agent.punchPaths(ctx, []netip.AddrPort{netip.MustParseAddrPort("127.0.0.2:9")}, nil, nil, time.Hour, false)
		done <- err
	}()
	bad := agent.packet(1, punchPing)
	bad[len(bad)-1] ^= 1
	if _, err := mobileConn.WriteToUDPAddrPort(bad, agentAddr); err != nil {
		t.Fatal(err)
	}
	if err := mobileConn.SetReadDeadline(time.Now().Add(75 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	var buf [64]byte
	if n, _, err := mobileConn.ReadFromUDPAddrPort(buf[:]); err == nil {
		t.Fatalf("unauthenticated source received %d response bytes", n)
	} else if e, ok := err.(net.Error); !ok || !e.Timeout() {
		t.Fatalf("read error = %v, want timeout", err)
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("punch error = %v, want cancellation", err)
	}
}

func TestPunchLearnsAuthenticatedUnknownSource(t *testing.T) {
	agentConn, agentAddr := loopbackUDP(t)
	mobileConn, mobileAddr := loopbackUDP(t)
	key := bytes.Repeat([]byte{9}, 32)
	agent := &Session{agent: true, id: "peer reflexive", key: key, sockets: []*net.UDPConn{agentConn}}
	mobile := &Session{id: "peer reflexive", key: key, sockets: []*net.UDPConn{mobileConn}}
	ctx, cancel := context.WithTimeout(t.Context(), 500*time.Millisecond)
	defer cancel()
	type result struct {
		agent  bool
		socket int
		addr   netip.AddrPort
		err    error
	}
	results := make(chan result, 2)
	go func() {
		i, addr, err := agent.punchPaths(ctx, []netip.AddrPort{netip.MustParseAddrPort("127.0.0.2:9")}, nil, nil, time.Hour, false)
		results <- result{true, i, addr, err}
	}()
	go func() {
		i, addr, err := mobile.punchPaths(ctx, []netip.AddrPort{agentAddr}, nil, nil, time.Hour, false)
		results <- result{false, i, addr, err}
	}()
	for range 2 {
		r := <-results
		if r.err != nil {
			t.Fatalf("authenticated unknown source rejected (agent=%t): %v", r.agent, r.err)
		}
		if r.socket != 0 {
			t.Fatalf("socket = %d, want primary socket", r.socket)
		}
		want := mobileAddr
		if !r.agent {
			want = agentAddr
		}
		if r.addr != want {
			t.Fatalf("selected address = %v, want %v", r.addr, want)
		}
	}
}
