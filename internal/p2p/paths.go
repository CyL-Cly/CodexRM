package p2p

import (
	"context"
	"net"
	"net/netip"
	"slices"
	"syscall"
)

const maxDirectPaths = 8 // Includes the system default route.

// Source identifies a local address on a specific network. Bind, when supplied,
// pins an unconnected socket to that network before its local address is bound.
// Android supplies a binder using the OS network handle.
type Source struct {
	Prefix  netip.Prefix
	Network string
	LAN     bool
	Bind    func(uintptr) error
}

func sourceInterfaces() []Source {
	var out []Source
	interfaces, _ := net.Interfaces()
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&(net.FlagLoopback|net.FlagPointToPoint) != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			p, err := netip.ParsePrefix(a.String())
			if err != nil || !usableSource(p.Addr()) {
				continue
			}
			out = append(out, Source{Prefix: p, Network: iface.Name, LAN: true, Bind: func(fd uintptr) error {
				return bindInterface(fd, iface, p.Addr().Is6())
			}})
		}
	}
	return out
}

func usableSource(ip netip.Addr) bool {
	return (ip.Is4() && ip.IsGlobalUnicast()) || IsPublicIPv6(ip)
}

// Try one address on each network and family before a second address on the
// same network. Multiple addresses on one interface can have different egress.
func orderSources(sources []Source, ipv6 bool) []Source {
	type group struct {
		name string
		v6   bool
	}
	type address struct {
		network string
		ip      netip.Addr
	}
	counts := make(map[group]int)
	seen := make(map[address]bool)
	var first, second []Source
	for _, src := range sources {
		ip := src.Prefix.Addr()
		key := address{src.Network, ip}
		if !usableSource(ip) || (!ipv6 && ip.Is6()) || seen[key] {
			continue
		}
		seen[key] = true
		g := group{src.Network, ip.Is6()}
		switch counts[g] {
		case 0:
			first = append(first, src)
		case 1:
			second = append(second, src)
		}
		counts[g]++
	}
	return slices.Concat(first, second)
}

func listenSource(ctx context.Context, src Source) (*net.UDPConn, error) {
	ip := src.Prefix.Addr()
	family := "udp4"
	if ip.Is6() {
		family = "udp6"
	}
	cfg := net.ListenConfig{}
	if src.Bind != nil {
		cfg.Control = func(_, _ string, raw syscall.RawConn) error {
			var bindErr error
			if err := raw.Control(func(fd uintptr) { bindErr = src.Bind(fd) }); err != nil {
				return err
			}
			return bindErr
		}
	}
	c, err := cfg.ListenPacket(ctx, family, netip.AddrPortFrom(ip, 0).String())
	if err != nil {
		return nil, err
	}
	return c.(*net.UDPConn), nil
}

func supportsFamily(c *net.UDPConn, v6 bool) bool {
	ip := c.LocalAddr().(*net.UDPAddr).AddrPort().Addr()
	return ip.Is6() == v6 || (!v6 && ip == netip.IPv6Unspecified())
}
