package p2p

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"strings"
	"testing"

	"github.com/codexrm/codexrm/internal/protocol"
)

func TestConnectWithUnpredictableMappings(t *testing.T) {
	for _, tc := range []struct {
		name, mapping string
		step          int
		lan, dual     bool
		want          error
	}{
		{"both unpredictable without LAN", protocol.P2PMappingVaries, 0, false, false, ErrDualUnpredictableNAT},
		{"negotiated dual punch without LAN", protocol.P2PMappingVaries, 0, false, true, context.Canceled},
		{"both unpredictable with LAN", protocol.P2PMappingVaries, 0, true, false, context.Canceled},
		{"one stable", protocol.P2PMappingStable, 0, false, false, context.Canceled},
		{"one linear", protocol.P2PMappingLinear, 4, false, false, context.Canceled},
		{"one unknown", "", 0, false, false, context.Canceled},
	} {
		t.Run(tc.name, func(t *testing.T) {
			socket, _ := loopbackUDP(t)
			s := &Session{id: "test", sockets: []*net.UDPConn{socket}, mapping: protocol.P2PMappingVaries, dualNATPunch: tc.dual}
			offer := protocol.P2PSignal{ID: s.id, Pin: strings.Repeat("ab", 32), Mapping: tc.mapping, Step: tc.step,
				Candidates: []string{"127.0.0.1:9"}, Local: []string{"192.168.7.3:9"}}
			if tc.lan {
				s.lan = []netip.Prefix{netip.MustParsePrefix("192.168.7.2/24")}
			}
			// Cancellation lets us distinguish reaching the probe loop from stopping before it.
			ctx, cancel := context.WithCancel(t.Context())
			cancel()
			if _, err := s.Connect(ctx, offer, nil, nil); !errors.Is(err, tc.want) {
				t.Fatalf("Connect returned %v, want %v", err, tc.want)
			}
		})
	}
}
