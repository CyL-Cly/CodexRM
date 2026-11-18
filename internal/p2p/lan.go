package p2p

import (
	"encoding/binary"
	"net"
	"net/netip"
)

func IsLANIPv4(ip netip.Addr) bool {
	return ip.Is4() && ip.IsPrivate() && ip.IsGlobalUnicast()
}

func lanInterfaces() []netip.Prefix {
	var out []netip.Prefix
	interfaces, _ := net.Interfaces()
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&(net.FlagLoopback|net.FlagPointToPoint) != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			if p, err := netip.ParsePrefix(a.String()); err == nil && IsLANIPv4(p.Addr()) && p.Bits() > 0 && p.Bits() < 32 {
				out = append(out, p)
			}
		}
	}
	return out
}

func onLinkIPv4(ip netip.Addr, prefixes []netip.Prefix) bool {
	if !IsLANIPv4(ip) {
		return false
	}
	for _, p := range prefixes {
		if p.Addr() == ip {
			return false
		}
	}
	for _, p := range prefixes {
		if !p.Contains(ip) {
			continue
		}
		if p.Bits() <= 30 {
			b := ip.As4()
			mask := uint32(1)<<(32-p.Bits()) - 1
			host := binary.BigEndian.Uint32(b[:]) & mask
			if host == 0 || host == mask {
				continue
			}
		}
		return true
	}
	return false
}
