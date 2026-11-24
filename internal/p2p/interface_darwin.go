//go:build darwin

package p2p

import (
	"net"

	"golang.org/x/sys/unix"
)

func bindInterface(fd uintptr, iface net.Interface, ipv6 bool) error {
	if ipv6 {
		return unix.SetsockoptInt(int(fd), unix.IPPROTO_IPV6, unix.IPV6_BOUND_IF, iface.Index)
	}
	return unix.SetsockoptInt(int(fd), unix.IPPROTO_IP, unix.IP_BOUND_IF, iface.Index)
}

func adapterType(iface net.Interface) AdapterType {
	return classifyAdapterName(iface.Name)
}
