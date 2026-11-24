package p2p

import (
	"net"
	"net/netip"
	"slices"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

func TestOrderSourcesKeepsNetworkAndFamilyDiversity(t *testing.T) {
	secondEthernet := Source{Network: "ethernet", Prefix: netip.MustParsePrefix("192.168.0.3/24")}
	input := []Source{
		{Network: "ethernet", Prefix: netip.MustParsePrefix("192.168.0.2/24")},
		secondEthernet,
		{Network: "ethernet", Prefix: netip.MustParsePrefix("192.168.0.4/24")},
		{Network: "ethernet", Prefix: netip.MustParsePrefix("2001:db8::1/64")},
		{Network: "wifi", Prefix: netip.MustParsePrefix("192.168.0.2/24")},
		{Network: "wifi", Prefix: netip.MustParsePrefix("fe80::1/64")},
	}
	for _, ipv6 := range []bool{true, false} {
		got := orderSources(input, ipv6)
		want := []string{"ethernet:192.168.0.2", "wifi:192.168.0.2", "ethernet:192.168.0.3"}
		if ipv6 {
			want = slices.Insert(want, 1, "ethernet:2001:db8::1")
		}
		var actual []string
		for _, s := range got {
			actual = append(actual, s.Network+":"+s.Prefix.Addr().String())
		}
		if !slices.Equal(actual, want) {
			t.Fatalf("ipv6=%t: got %v, want %v", ipv6, actual, want)
		}
	}
}

func TestPunchPlansKeepMappingHintsOnTheirOwnPath(t *testing.T) {
	a, _ := loopbackUDP(t)
	b, _ := loopbackUDP(t)
	s := &Session{agent: true, sockets: []*net.UDPConn{a, b}, pathSockets: 2}
	stable := netip.MustParseAddrPort("192.0.2.1:30500")
	linear := netip.MustParseAddrPort("192.0.2.2:40500")
	varies := netip.MustParseAddrPort("192.0.2.2:50500")
	hints := map[netip.AddrPort]protocol.P2PMappingHint{
		stable: {Mapping: protocol.P2PMappingStable},
		linear: {Mapping: protocol.P2PMappingLinear, Step: 7},
		varies: {Mapping: protocol.P2PMappingVaries},
	}
	start := time.Now()
	plans := s.plans(s.rankedPaths(), []netip.AddrPort{stable, linear, varies}, hints, start, 0, dualNATProbeTargets)
	if len(plans) != 4 {
		t.Fatalf("expected two path-specific plans per socket, got %d", len(plans))
	}
	for i, plan := range plans {
		if plan.socket != i/2 || plan.targets[0].Addr() != linear.Addr() {
			t.Fatalf("unexpected plan %+v", plan)
		}
		if i%2 == 0 && (plan.targets[0].Port() != linear.Port()+7 || plan.after.Sub(start) != 200*time.Millisecond) {
			t.Fatalf("linear plan did not use its own port and step: %+v", plan)
		}
		if i%2 == 1 && plan.after.Sub(start) != 120*time.Millisecond {
			t.Fatalf("unexpected schedule for unpredictable mapping: %v", plan.after.Sub(start))
		}
	}
}

func TestIPv6VerifierBindsReceiptToSocketAndTicket(t *testing.T) {
	verifier, err := ListenVerifier(netip.MustParseAddrPort("[::1]:0"), "")
	if err != nil {
		t.Skipf("IPv6 loopback unavailable: %v", err)
	}
	defer verifier.Close()
	socket, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	now := time.Now()
	ticket, err := verifier.Ticket(now)
	if err != nil {
		t.Fatal(err)
	}
	queries := discover(t.Context(), socket, protocol.P2PSignal{Ticket: ticket, VerifyUDP: verifier.Addr().String(), IPv6: true})
	if len(queries) != 1 {
		t.Fatalf("expected one verification query, got %d", len(queries))
	}
	q := queries[0]
	addr := socket.LocalAddr().(*net.UDPAddr).AddrPort()
	if q.mapped != addr || !verifier.Check(ticket, q.proof, addr, now) {
		t.Fatalf("receipt did not authenticate socket: %+v", q)
	}
	if verifier.Check(ticket, q.proof, netip.AddrPortFrom(addr.Addr(), addr.Port()%65535+1), now) ||
		verifier.Check(ticket, q.proof, addr, now.Add(time.Minute)) {
		t.Fatal("receipt accepted with a different port or after ticket expiry")
	}
}

func TestBadLossWindowIgnoresIdleLinksAndLatePackets(t *testing.T) {
	old := quic.ConnectionStats{PacketsSent: 200, PacketsLost: 60}
	for _, tc := range []struct {
		sent, lost uint64
		want       bool
	}{
		{230, 89, false}, {400, 40, false}, {400, 159, false}, {400, 160, true},
	} {
		if got := badLossWindow(old, quic.ConnectionStats{PacketsSent: tc.sent, PacketsLost: tc.lost}); got != tc.want {
			t.Fatalf("sent=%d lost=%d: got %t, want %t", tc.sent, tc.lost, got, tc.want)
		}
	}
}
