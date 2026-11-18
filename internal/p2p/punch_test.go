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

func TestAgentProbesStartExactThenExpandAndRetry(t *testing.T) {
	a := netip.MustParseAddrPort("203.0.113.1:40000")
	b := netip.MustParseAddrPort("203.0.113.2:65535")
	private := netip.MustParseAddrPort("192.168.2.10:40000")
	p := agentProbes{exact: []netip.AddrPort{a, b, private}}
	now := time.Now()
	expandAt := now.Add(exactWindow(protocol.P2PMappingStable, protocol.P2PMappingStable))
	for i, want := range p.exact {
		if got := p.next(now.Add(time.Duration(i)*punchTick), expandAt); got != want {
			t.Fatalf("exact candidate %d: got %v want %v", i, got, want)
		}
	}
	if p.next(now.Add(3*punchTick), expandAt).IsValid() || p.expanded {
		t.Fatal("expanded ahead of the exact window")
	}
	if got := p.next(expandAt, expandAt); !slices.Contains(p.exact, got) {
		t.Fatalf("lost the exact retry: %v", got)
	}
	if got := p.next(expandAt.Add(punchTick), expandAt); got != netip.MustParseAddrPort("203.0.113.1:40001") {
		t.Fatalf("did not begin with the closest neighbor: %v", got)
	}
	if len(p.extra)+len(p.exact) != maxProbeTargets {
		t.Fatalf("unexpected probe budget: %d", len(p.extra))
	}
	seen := make(map[netip.AddrPort]bool)
	for _, addr := range p.extra {
		if seen[addr] || slices.Contains(p.exact, addr) || addr.Port() < 1024 || (addr.Addr() != a.Addr() && addr.Addr() != b.Addr()) {
			t.Fatalf("unexpected expanded target %v", addr)
		}
		seen[addr] = true
	}
	if got := p.next(expandAt.Add(candidateRetry), expandAt); !slices.Contains(p.exact, got) {
		t.Fatalf("did not retry exact targets during expansion: %v", got)
	}
	if got := expandedTargets([]netip.AddrPort{private, netip.MustParseAddrPort("100.64.1.1:40000")}, 0); len(got) != 0 {
		t.Fatal("expanded non-public addresses")
	}
}

func TestLinearStepPrecedesNeighborhood(t *testing.T) {
	exact := netip.MustParseAddrPort("203.0.113.8:20000")
	p := agentProbes{exact: []netip.AddrPort{exact}, step: 4}
	expandAt := time.Now()
	if got := p.next(expandAt, expandAt); got != exact {
		t.Fatalf("exact retry = %v", got)
	}
	if got := p.next(expandAt, expandAt); got != netip.MustParseAddrPort("203.0.113.8:20004") {
		t.Fatalf("forward step = %v", got)
	}
	if got := p.next(expandAt, expandAt); got != netip.MustParseAddrPort("203.0.113.8:19996") {
		t.Fatalf("backward step = %v", got)
	}
	if exactWindow(protocol.P2PMappingVaries, protocol.P2PMappingLinear) != 120*time.Millisecond ||
		exactWindow(protocol.P2PMappingStable, protocol.P2PMappingLinear) != 200*time.Millisecond ||
		exactWindow(protocol.P2PMappingStable, protocol.P2PMappingStable) != 800*time.Millisecond {
		t.Fatal("unexpected exact windows")
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
		_, addr, err := s.punch(ctx, []netip.AddrPort{a, b}, nil, exactWindow("", ""), 0)
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
	i, addr, err := s.punch(ctx, targets, nil, exactWindow(protocol.P2PMappingStable, protocol.P2PMappingStable), 0)
	if err != nil || i != 0 || addr != src || len(s.sockets) != 1 {
		t.Fatalf("fast path socket=%d addr=%v err=%v sockets=%d", i, addr, err, len(s.sockets))
	}
}
