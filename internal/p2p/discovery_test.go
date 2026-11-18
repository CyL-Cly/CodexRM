package p2p

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"slices"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

func loopbackUDP(t *testing.T) (*net.UDPConn, netip.AddrPort) {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c, c.LocalAddr().(*net.UDPAddr).AddrPort()
}

func stunResponse(req []byte, addr netip.AddrPort) []byte {
	b := make([]byte, 32)
	copy(b, req)
	binary.BigEndian.PutUint16(b, 0x101)
	binary.BigEndian.PutUint16(b[2:], 12)
	binary.BigEndian.PutUint16(b[20:], 0x20)
	binary.BigEndian.PutUint16(b[22:], 8)
	b[25] = 1
	binary.BigEndian.PutUint16(b[26:], addr.Port()^0x2112)
	for i, v := range addr.Addr().As4() {
		b[28+i] = v ^ req[4+i]
	}
	return b
}

func TestBindingReplyMatchesTransaction(t *testing.T) {
	req := bindingRequest()
	want := netip.MustParseAddrPort("203.0.113.1:45535")
	good := stunResponse(req, want)
	if got := bindingReply(req, good); got != want {
		t.Fatalf("bindingReply = %v, want %v", got, want)
	}
	wrong := slices.Clone(good)
	wrong[8] ^= 1
	malformed := append(slices.Clone(good), 0, 1, 255, 255)
	binary.BigEndian.PutUint16(malformed[2:], 16)
	for _, bad := range [][]byte{wrong, malformed, good[:31]} {
		if got := bindingReply(req, bad); got.IsValid() {
			t.Fatalf("accepted invalid reply: %v", got)
		}
	}
}

func TestDiscoverOnOneSocketDespiteSilentSTUN(t *testing.T) {
	serverA, a := loopbackUDP(t)
	serverB, b := loopbackUDP(t)
	_, silent := loopbackUDP(t)
	v, err := ListenVerifier(netip.MustParseAddrPort("127.0.0.1:0"), "")
	if err != nil {
		t.Fatal(err)
	}
	defer v.Close()
	ticket, err := v.Ticket(time.Now())
	if err != nil {
		t.Fatal(err)
	}
	one := netip.MustParseAddrPort("203.0.113.10:40000")
	two := netip.MustParseAddrPort("203.0.113.10:40002")
	seen := make(chan netip.AddrPort, 2)
	for c, mapped := range map[*net.UDPConn]netip.AddrPort{serverA: one, serverB: two} {
		go func() {
			var req [20]byte
			if n, from, err := c.ReadFromUDPAddrPort(req[:]); err == nil && n == len(req) {
				seen <- from
				_, _ = c.WriteToUDPAddrPort(stunResponse(req[:], mapped), from)
			}
		}()
	}
	// This is shorter than one STUN retry interval. A sequential collector
	// would spend all of it waiting for the first (silent) server.
	ctx, cancel := context.WithTimeout(t.Context(), 550*time.Millisecond)
	defer cancel()
	s, expectedSource := loopbackUDP(t)
	start := protocol.P2PSignal{STUN: []string{silent.String(), a.String(), b.String(), a.String()}, VerifyUDP: v.Addr().String(), Ticket: ticket}
	qs := discover(ctx, s, start)
	if len(qs) != 4 || qs[0].mapped != expectedSource || qs[1].mapped.IsValid() || qs[2].mapped != one || qs[3].mapped != two {
		t.Fatalf("unexpected query results: %+v", qs)
	}
	if !v.Check(ticket, qs[0].proof, expectedSource, time.Now()) {
		t.Fatal("invalid verification receipt")
	}
	for range 2 {
		select {
		case from := <-seen:
			if from != expectedSource {
				t.Fatalf("discovery changed source socket: %v", from)
			}
		default:
			t.Fatal("STUN server did not observe the request")
		}
	}
	if got := mappingHint(qs); got != protocol.P2PMappingVaries {
		t.Fatalf("mapping = %q", got)
	}
}

func TestMappingHintNeedsDistinctIPForStable(t *testing.T) {
	mapped := netip.MustParseAddrPort("203.0.113.30:45321")
	qs := []discoveryQuery{
		{dst: netip.MustParseAddrPort("192.0.2.1:3478"), mapped: mapped},
		{dst: netip.MustParseAddrPort("192.0.2.1:4378"), mapped: mapped},
		{dst: netip.MustParseAddrPort("192.0.2.2:3478")},
	}
	if got := mappingHint(qs); got != "" {
		t.Fatalf("same server IP plus timeout classified as %q", got)
	}
	qs[2].mapped = mapped
	if got := mappingHint(qs); got != protocol.P2PMappingStable {
		t.Fatalf("same mapping across different server IPs classified as %q", got)
	}
}

func TestClassifySequentialPortStep(t *testing.T) {
	qs := []discoveryQuery{
		{dst: netip.MustParseAddrPort("192.0.2.1:3478"), mapped: netip.MustParseAddrPort("203.0.113.10:40000")},
		{dst: netip.MustParseAddrPort("198.51.100.1:3478"), mapped: netip.MustParseAddrPort("203.0.113.10:40004")},
		{verifier: true, dst: netip.MustParseAddrPort("203.0.113.1:1"), mapped: netip.MustParseAddrPort("198.51.100.9:9")},
	}
	if hint, step := classifyMapping(qs); hint != protocol.P2PMappingLinear || step != 4 {
		t.Fatalf("linear = %s %d", hint, step)
	}
	qs = append(qs, discoveryQuery{dst: netip.MustParseAddrPort("203.0.113.5:3478"), mapped: netip.MustParseAddrPort("203.0.113.10:40010")})
	if hint, step := classifyMapping(qs); hint != protocol.P2PMappingVaries || step != 0 {
		t.Fatalf("inconsistent = %s %d", hint, step)
	}
	qs = []discoveryQuery{
		{dst: netip.MustParseAddrPort("192.0.2.1:3478"), mapped: netip.MustParseAddrPort("203.0.113.10:1000")},
		{dst: netip.MustParseAddrPort("198.51.100.1:3478"), mapped: netip.MustParseAddrPort("198.51.100.20:1002")},
	}
	if hint, _ := classifyMapping(qs); hint != protocol.P2PMappingVaries {
		t.Fatalf("different addresses = %s", hint)
	}
	if hint, step := protocol.NormalizeMapping(protocol.P2PMappingLinear, 40); hint != protocol.P2PMappingVaries || step != 0 {
		t.Fatalf("oversized step = %s %d", hint, step)
	}
}
