//go:build !windows && (!linux || android) && !darwin

package p2p

import (
	"errors"
	"net"
)

func bindInterface(uintptr, net.Interface, bool) error {
	return errors.New("interface binding unavailable")
}
