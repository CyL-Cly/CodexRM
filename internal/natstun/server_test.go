package natstun_test

import (
	"bytes"
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/natstun"
	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/protocol"
)

func reserveUDP(t *testing.T, ip netip.Addr, port uint16) *net.UDPConn {
	t.Helper()
	conn, err := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(netip.AddrPortFrom(ip, port)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

func pairedConfig(t *testing.T) (natstun.Config, natstun.Config) {
	t.Helper()
	a, b := netip.MustParseAddr("127.0.0.2"), netip.MustParseAddr("127.0.0.3")
	addr := func(conn *net.UDPConn) netip.AddrPort { return conn.LocalAddr().(*net.UDPAddr).AddrPort() }
	a1, a2, peerA, peerB := reserveUDP(t, a, 0), reserveUDP(t, a, 0), reserveUDP(t, a, 0), reserveUDP(t, b, 0)
	ports := [2]uint16{addr(a1).Port(), addr(a2).Port()}
	for _, p := range ports {
		// Reserve the same port on both IPs to catch collisions before starting.
		_ = reserveUDP(t, b, p).Close()
	}
	key := [32]byte{17}
	first := natstun.Config{ListenIP: a, PublicIP: a, OtherIP: b, Ports: ports, PeerListen: addr(peerA), PeerRemote: addr(peerB), Key: key}
	second := natstun.Config{ListenIP: b, PublicIP: b, OtherIP: a, Ports: ports, PeerListen: addr(peerB), PeerRemote: addr(peerA), Key: key}
	for _, conn := range []*net.UDPConn{a1, a2, peerA, peerB} {
		_ = conn.Close()
	}
	return first, second
}

func start(t *testing.T, cfg natstun.Config) *natstun.Server {
	t.Helper()
	s, err := natstun.Listen(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	return s
}

func TestTwoNodesWithCodexRMCheckerAndClassifier(t *testing.T) {
	first, second := pairedConfig(t)
	start(t, first)
	start(t, second)
	for _, cfg := range []natstun.Config{first, second} {
		dst := netip.AddrPortFrom(cfg.PublicIP, cfg.Ports[0])
		classic, modern := p2p.CheckNATSTUN(t.Context(), dst)
		if classic != nil || modern != nil {
			t.Fatalf("%s: RFC 3489: %v; RFC 5780: %v", dst, classic, modern)
		}
		report := p2p.DiagnoseNAT(t.Context(), []string{dst.String()})
		want := protocol.NATReport{RFC3489: "open_internet", Mapping: protocol.NATEndpointIndependent, Filtering: protocol.NATEndpointIndependent}
		if report != want {
			t.Fatalf("%s report = %+v, want %+v", dst, report, want)
		}
	}
}

func TestBrokenPeerReturnsSTUNError(t *testing.T) {
	first, second := pairedConfig(t)
	start(t, first)
	// Keep local Binding working on both IPs but break authentication. The
	// classifier must not mislabel this as an address-dependent filter.
	second.Key[0] ^= 1
	start(t, second)
	dst := netip.AddrPortFrom(first.PublicIP, first.Ports[0])
	c := reserveUDP(t, netip.MustParseAddr("127.0.0.1"), 0)
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	req := make([]byte, 28)
	binary.BigEndian.PutUint16(req, 1)
	binary.BigEndian.PutUint16(req[2:], 8)
	binary.BigEndian.PutUint32(req[4:], 0x2112a442)
	copy(req[8:], "transaction!")
	copy(req[20:], []byte{0, 3, 0, 4, 0, 0, 0, 6})
	if _, err := c.WriteToUDPAddrPort(req, dst); err != nil {
		t.Fatal(err)
	}
	var buf [256]byte
	n, source, err := c.ReadFromUDPAddrPort(buf[:])
	if err != nil {
		t.Fatal(err)
	}
	if n < 28 || source != dst || !bytes.Equal(buf[4:20], req[4:20]) ||
		binary.BigEndian.Uint16(buf[:]) != 0x111 || binary.BigEndian.Uint16(buf[20:]) != 9 ||
		buf[26] != 5 || buf[27] != 0 {
		t.Fatalf("expected Binding error 500 from %s, got %x from %s", dst, buf[:n], source)
	}
	report := p2p.DiagnoseNAT(t.Context(), []string{dst.String()})
	if report.RFC3489 != protocol.NATUnknown || report.Filtering != protocol.NATUnknown || report.Mapping != protocol.NATEndpointIndependent {
		t.Fatalf("unexpected result on broken peer: %+v", report)
	}
}
