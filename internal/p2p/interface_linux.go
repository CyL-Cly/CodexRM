//go:build linux && !android

package p2p

import (
	"net"

	"golang.org/x/sys/unix"
)

func bindInterface(fd uintptr, iface net.Interface, _ bool) error {
	return unix.BindToDevice(int(fd), iface.Name)
}
