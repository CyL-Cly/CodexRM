package p2p

import (
	"context"
	"errors"
	"net"
	"syscall"
	"time"
)

// Public DNS addresses used as a best-effort outbound check. Only a
// permission error on every probe is treated as a host firewall; refused
// ports and timeouts are not, so a machine without those routes still punches.
var preflightAddrs = []string{"223.5.5.5:53", "119.29.29.29:53"}

func outboundBlocked(ctx context.Context) bool {
	d := net.Dialer{Timeout: 400 * time.Millisecond}
	blocked := 0
	for _, addr := range preflightAddrs {
		c, err := d.DialContext(ctx, "tcp", addr)
		if err == nil {
			_ = c.Close()
			return false
		}
		if isProhibited(err) {
			blocked++
		}
	}
	return blocked == len(preflightAddrs)
}

func isProhibited(err error) bool {
	var errno syscall.Errno
	if errors.As(err, &errno) {
		return errno == syscall.EPERM || errno == syscall.EACCES
	}
	return false
}
