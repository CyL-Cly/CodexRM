package p2p

import (
	"errors"
	"math/bits"
	"net"
	"unsafe"

	"golang.org/x/sys/windows"
)

func bindInterface(fd uintptr, iface net.Interface, ipv6 bool) error {
	const unicastInterface = 31
	if ipv6 {
		index, err := ipv6InterfaceIndex(iface.Index)
		if err != nil {
			return err
		}
		return windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IPV6, unicastInterface, index)
	}
	// IP_UNICAST_IF takes a network-order IPv4 interface index.
	return windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IP, unicastInterface, int(bits.ReverseBytes32(uint32(iface.Index))))
}

func ipv6InterfaceIndex(index int) (int, error) {
	// net.Interface.Index uses the IPv4 index when the adapter has both.
	size := uint32(15 * 1024)
	for range 3 {
		buf := make([]byte, size)
		first := (*windows.IpAdapterAddresses)(unsafe.Pointer(&buf[0]))
		err := windows.GetAdaptersAddresses(windows.AF_INET6, windows.GAA_FLAG_SKIP_UNICAST|windows.GAA_FLAG_SKIP_ANYCAST|windows.GAA_FLAG_SKIP_MULTICAST|windows.GAA_FLAG_SKIP_DNS_SERVER, 0, first, &size)
		if err == windows.ERROR_BUFFER_OVERFLOW {
			continue
		}
		if err != nil {
			return 0, err
		}
		for a := first; a != nil; a = a.Next {
			if a.Ipv6IfIndex != 0 && (int(a.IfIndex) == index || (a.IfIndex == 0 && int(a.Ipv6IfIndex) == index)) {
				return int(a.Ipv6IfIndex), nil
			}
		}
		break
	}
	return 0, errors.New("IPv6 interface index unavailable")
}
