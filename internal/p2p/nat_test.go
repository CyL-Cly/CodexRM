package p2p

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

func mappedAttr(b []byte, kind uint16, a netip.AddrPort) []byte {
	out := binary.BigEndian.AppendUint16(b, kind)
	out = binary.BigEndian.AppendUint16(out, 8)
	out = append(out, 0, 1)
	out = binary.BigEndian.AppendUint16(out, a.Port())
	ip := a.Addr().As4()
	out = append(out, ip[:]...)
	binary.BigEndian.PutUint16(out[2:4], uint16(len(out)-20))
	return out
}

func TestNATDiagnosesBothRFCsAgainstRateLimitedSTUN(t *testing.T) {
	open := func(addr string) *net.UDPConn {
		c, err := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(addr)))
		if err != nil {
			t.Skipf("alternate loopback socket unavailable: %v", err)
		}
		t.Cleanup(func() { _ = c.Close() })
		return c
	}
	address := func(c *net.UDPConn) netip.AddrPort { return c.LocalAddr().(*net.UDPAddr).AddrPort() }
	primary, alt := open("127.0.0.1:0"), open("127.0.0.2:0")
	if address(primary).Port() == address(alt).Port() {
		t.Skip("requires two distinct ports")
	}
	altIP := open(netip.AddrPortFrom(address(alt).Addr(), address(primary).Port()).String())
	requests := make(chan struct {
		legacy bool
		from   netip.AddrPort
	}, 20)
	// Model a server limiting by client IP rather than source port. Drops
	// include requests sent too close together across both protocol suites.
	var gate sync.Mutex
	var last time.Time
	var dropped int
	serve := func(c *net.UDPConn) {
		var buf [128]byte
		for {
			n, from, err := c.ReadFromUDPAddrPort(buf[:])
			if err != nil {
				return
			}
			if n != 20 && n != 28 {
				continue
			}
			gate.Lock()
			now := time.Now()
			tooSoon := now.Sub(last) < 250*time.Millisecond
			last = now
			if tooSoon {
				dropped++
			}
			gate.Unlock()
			if tooSoon {
				continue
			}
			legacy := binary.BigEndian.Uint32(buf[4:8]) != 0x2112a442
			requests <- struct {
				legacy bool
				from   netip.AddrPort
			}{legacy, from}
			to := c
			if n == 28 && buf[27] == changeIP|changePort {
				to = alt
			}
			b := append([]byte(nil), buf[:20]...)
			binary.BigEndian.PutUint16(b[:2], 0x101)
			mapped := netip.MustParseAddrPort("203.0.113.7:45678")
			if !legacy && c == altIP {
				mapped = netip.MustParseAddrPort("203.0.113.7:45679")
			}
			if !legacy && c == alt {
				mapped = netip.MustParseAddrPort("203.0.113.7:45679")
			}
			b = mappedAttr(b, 1, mapped)
			kind := uint16(0x802c)
			if legacy {
				kind = 5
			}
			b = mappedAttr(b, kind, address(alt))
			_, _ = to.WriteToUDPAddrPort(b, from)
		}
	}
	for _, c := range []*net.UDPConn{primary, altIP, alt} {
		go serve(c)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 6*time.Second)
	defer cancel()
	got := DiagnoseNAT(ctx, []string{address(primary).String()})
	want := protocol.NATReport{RFC3489: "full_cone", Mapping: protocol.NATAddressDependent, Filtering: protocol.NATEndpointIndependent}
	if got != want || !got.Valid() {
		t.Fatalf("diagnosis = %+v, want %+v", got, want)
	}
	gate.Lock()
	drops := dropped
	gate.Unlock()
	if drops != 0 {
		t.Fatalf("STUN dropped %d requests sent too close together", drops)
	}
	sources := make(map[bool]netip.AddrPort)
	for len(requests) > 0 {
		req := <-requests
		if first, ok := sources[req.legacy]; ok && first != req.from {
			t.Fatalf("changed source socket during one RFC suite: %v vs %v", first, req.from)
		}
		sources[req.legacy] = req.from
	}
	if len(sources) != 2 || sources[true] == sources[false] {
		t.Fatalf("suites did not use separate sockets: %v", sources)
	}
}

func TestNATDoesNotInferTypeFromBasicSTUN(t *testing.T) {
	c, addr := loopbackUDP(t)
	go func() {
		var req [64]byte
		for {
			n, from, err := c.ReadFromUDPAddrPort(req[:])
			if err != nil {
				return
			}
			if n != 20 {
				continue
			}
			b := append([]byte(nil), req[:n]...)
			binary.BigEndian.PutUint16(b[:2], 0x101)
			_, _ = c.WriteToUDPAddrPort(mappedAttr(b, 1, from), from)
		}
	}()
	got := DiagnoseNAT(t.Context(), []string{addr.String()})
	want := protocol.NATReport{RFC3489: protocol.NATUnsupported, Mapping: protocol.NATUnsupported, Filtering: protocol.NATUnsupported}
	if got != want {
		t.Fatalf("diagnosis = %+v, want %+v", got, want)
	}
}

func openNATFixture(t *testing.T, opts natFixtureOptions) netip.AddrPort {
	t.Helper()
	open := func(addr netip.AddrPort) *net.UDPConn {
		c, err := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(addr))
		if err != nil {
			t.Fatalf("bind %v: %v", addr, err)
		}
		t.Cleanup(func() { _ = c.Close() })
		return c
	}
	address := func(c *net.UDPConn) netip.AddrPort { return c.LocalAddr().(*net.UDPAddr).AddrPort() }
	a, b := netip.MustParseAddr("127.0.0.1"), netip.MustParseAddr("127.0.0.2")
	primary := open(netip.AddrPortFrom(a, 0))
	changedPort := open(netip.AddrPortFrom(a, 0))
	otherIP := open(netip.AddrPortFrom(b, address(primary).Port()))
	other := open(netip.AddrPortFrom(b, address(changedPort).Port()))
	sockets := []*net.UDPConn{primary, changedPort, otherIP, other}
	for index, c := range sockets {
		if opts.dropBindings&(1<<index) != 0 {
			continue
		}
		go func() {
			var data [64]byte
			for {
				n, from, err := c.ReadFromUDPAddrPort(data[:])
				if err != nil {
					return
				}
				if n != 20 && n != 28 {
					continue
				}
				dst := index
				b := append([]byte(nil), data[:20]...)
				if n == 28 {
					if opts.changeError != 0 {
						code := opts.changeError
						binary.BigEndian.PutUint16(b, 0x111)
						binary.BigEndian.PutUint16(b[2:], 8)
						b = append(b, 0, 9, 0, 4, 0, 0, byte(code/100), byte(code%100))
						_, _ = c.WriteToUDPAddrPort(b, from)
						continue
					}
					if opts.dropChanges {
						continue
					}
					if data[27]&changeIP != 0 {
						dst ^= 2
					}
					if data[27]&changePort != 0 {
						dst ^= 1
					}
				}
				if opts.delay != 0 {
					time.Sleep(opts.delay)
				}
				binary.BigEndian.PutUint16(b, 0x101)
				b = mappedAttr(b, 1, netip.MustParseAddrPort("203.0.113.8:32000"))
				kind := uint16(0x802c)
				if binary.BigEndian.Uint32(b[4:8]) != 0x2112a442 {
					kind = 5
				}
				b = mappedAttr(b, kind, address(sockets[index^3]))
				_, _ = sockets[dst].WriteToUDPAddrPort(b, from)
			}
		}()
	}
	return address(primary)
}

type natFixtureOptions struct {
	dropBindings int
	dropChanges  bool
	changeError  int
	delay        time.Duration
}

func TestNATFallsBackFromFastBrokenAlternate(t *testing.T) {
	broken := openNATFixture(t, natFixtureOptions{dropBindings: 0b1110, dropChanges: true})
	ready := openNATFixture(t, natFixtureOptions{delay: 80 * time.Millisecond})
	got := DiagnoseNAT(t.Context(), []string{broken.String(), ready.String()})
	want := protocol.NATReport{RFC3489: "full_cone", Mapping: protocol.NATEndpointIndependent, Filtering: protocol.NATEndpointIndependent}
	if got != want {
		t.Fatalf("diagnosis = %+v, want %+v", got, want)
	}
}

func TestNATDoesNotCallUnreachableAlternateFiltering(t *testing.T) {
	dst := openNATFixture(t, natFixtureOptions{dropBindings: 0b1000, dropChanges: true})
	got := DiagnoseNAT(t.Context(), []string{dst.String()})
	if got.RFC3489 != protocol.NATUnknown || got.Filtering != protocol.NATUnknown || got.Mapping != protocol.NATEndpointIndependent {
		t.Fatalf("diagnosis = %+v, want unknown filtering with valid mapping", got)
	}
}

func TestNATDistinguishesSTUNRejectionFromFiltering(t *testing.T) {
	for _, tc := range []struct {
		code int
		want string
	}{{420, protocol.NATUnsupported}, {500, protocol.NATUnknown}} {
		t.Run(tc.want, func(t *testing.T) {
			t.Parallel()
			dst := openNATFixture(t, natFixtureOptions{changeError: tc.code})
			got := DiagnoseNAT(t.Context(), []string{dst.String()})
			if got.RFC3489 != tc.want || got.Filtering != tc.want || got.Mapping != protocol.NATEndpointIndependent {
				t.Fatalf("diagnosis = %+v, want %s filtering with valid mapping", got, tc.want)
			}
		})
	}
}

func TestCheckNATSTUNRequiresActualChangedSources(t *testing.T) {
	for _, tc := range []struct {
		name string
		code int
	}{{"complete", 0}, {"rejects_change_request", 420}} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			dst := openNATFixture(t, natFixtureOptions{changeError: tc.code})
			legacy, modern := CheckNATSTUN(t.Context(), dst)
			if wantOK := tc.code == 0; (legacy == nil) != wantOK || (modern == nil) != wantOK {
				t.Fatalf("check: classic=%v modern=%v, want success=%v", legacy, modern, wantOK)
			}
		})
	}
}
