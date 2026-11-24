package p2p

import (
	"context"
	"fmt"
	"net"
	"net/netip"

	"github.com/codexrm/codexrm/internal/protocol"
)

// CheckNATSTUN validates the actual response sources, not just the advertised
// attributes. Run from a server or a path that allows pre-contacted UDP peers;
// a restrictive checker path can fail even against a capable STUN server.
// This is a deployment check, not a NAT test: the socket deliberately contacts
// all four endpoint combinations before testing CHANGE-REQUEST.
func CheckNATSTUN(ctx context.Context, dst netip.AddrPort) (classic, modern error) {
	if !dst.Addr().Is4() || dst.Port() == 0 || (!canExpand(dst.Addr()) && !dst.Addr().IsLoopback()) {
		err := fmt.Errorf("expected a public IPv4 UDP endpoint (or loopback for local testing)")
		return err, err
	}
	pace := &natPacer{}
	check := func(legacy bool) error {
		ctx, cancel := context.WithTimeout(ctx, protocol.NATProbeTimeout)
		defer cancel()
		c, err := net.ListenUDP("udp4", nil)
		if err != nil {
			return err
		}
		defer c.Close()
		binding, status := natQuery(ctx, c, pace, dst, dst, legacy, 0)
		if !binding.mapped.IsValid() {
			return fmt.Errorf("Binding on %s: %s", dst, status)
		}
		alt := binding.other
		if legacy {
			alt = binding.changed
		}
		if !validNATAlternate(dst, alt) {
			return fmt.Errorf("missing usable alternate IPv4 address and port")
		}
		altIP := netip.AddrPortFrom(alt.Addr(), dst.Port())
		altPort := netip.AddrPortFrom(dst.Addr(), alt.Port())
		for _, addr := range []netip.AddrPort{altIP, altPort, alt} {
			if r, status := natQuery(ctx, c, pace, addr, addr, legacy, 0); !r.mapped.IsValid() {
				return fmt.Errorf("Binding on %s: %s", addr, status)
			}
		}
		if r, status := natQuery(ctx, c, pace, dst, alt, legacy, changeIP|changePort); !r.mapped.IsValid() {
			return fmt.Errorf("CHANGE-REQUEST (IP + port) expected %s: %s", alt, status)
		}
		if r, status := natQuery(ctx, c, pace, dst, altPort, legacy, changePort); !r.mapped.IsValid() {
			return fmt.Errorf("CHANGE-REQUEST (port) expected %s: %s", altPort, status)
		}
		return nil
	}
	return check(true), check(false)
}
