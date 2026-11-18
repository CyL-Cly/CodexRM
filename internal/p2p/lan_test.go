package p2p

import (
	"net/netip"
	"testing"
)

func TestOnLinkIPv4RejectsLocalAndOffLinkTargets(t *testing.T) {
	prefixes := []netip.Prefix{netip.MustParsePrefix("192.168.2.164/24"), netip.MustParsePrefix("10.1.0.4/31")}
	for _, tc := range []struct {
		addr string
		ok   bool
	}{
		{"192.168.2.126", true},
		{"10.1.0.5", true},
		{"192.168.2.164", false},
		{"192.168.2.0", false},
		{"192.168.2.255", false},
		{"192.168.3.126", false},
		{"127.0.0.1", false},
		{"169.254.2.126", false},
		{"::ffff:192.168.2.126", false},
	} {
		if got := onLinkIPv4(netip.MustParseAddr(tc.addr), prefixes); got != tc.ok {
			t.Errorf("onLinkIPv4(%s) = %t, want %t", tc.addr, got, tc.ok)
		}
	}
}
