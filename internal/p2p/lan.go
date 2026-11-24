package p2p

import (
	"encoding/binary"
	"net/netip"
)

var globalIPv6 = netip.MustParsePrefix("2000::/3")

func IsPublicIPv6(ip netip.Addr) bool {
	return ip.Zone() == "" && globalIPv6.Contains(ip)
}

func IsLANIPv4(ip netip.Addr) bool {
	return ip.Is4() && ip.IsPrivate() && ip.IsGlobalUnicast()
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
