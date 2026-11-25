package p2p

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

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
			if s.State() != ConnFailed {
				t.Fatalf("state %s, want failed", s.State())
			}
		})
	}
}

func TestPrepareWithoutLocalUDPCandidates(t *testing.T) {
	start := protocol.P2P("start", "test-session-without-peer-candidates")
	start.LAN = true // LAN-only, and the client has no usable local prefix.
	start.Secret = strings.Repeat("ab", 32)
	if session, _, err := PrepareWithLocalPrefixes(t.Context(), false, *start, nil); err == nil {
		session.Close()
		t.Fatal("accepted a pin-only offer without negotiation")
	}
	start.ClientInitiated = true
	session, offer, err := PrepareWithLocalPrefixes(t.Context(), false, *start, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	if session.State() != ConnOffering {
		t.Fatalf("pin-only client state %s, want offering", session.State())
	}
	if len(offer.Pin) != 64 || len(offer.Candidates) != 0 || len(offer.Local) != 0 {
		t.Fatalf("expected a pin-only offer, got %+v", offer)
	}
	if agent, _, err := PrepareWithLocalPrefixes(t.Context(), true, *start, nil); err == nil {
		agent.Close()
		t.Fatal("allowed the Agent to offer no candidates")
	}
}

func TestDirectRacesCandidatesAndUsesAlternateSocketForQUIC(t *testing.T) {
	ctx, cancel := context.WithTimeout(t.Context(), 4*time.Second)
	defer cancel()
	const fileID = "12345678-1234-1234-1234-123456789abc"
	const fileContents = "QUIC stream payload"
	start := protocol.P2P("start", "test-session-without-peer-candidates")
	start.Secret, start.ClientInitiated = strings.Repeat("ab", 32), true
	agent, offerAgent, err := PrepareWithLocalPrefixes(ctx, true, *start, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer agent.Close()
	client, offerClient, err := PrepareWithLocalPrefixes(ctx, false, *start, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	alt, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 2)})
	if err != nil {
		t.Skipf("alternate loopback unavailable: %v", err)
	}
	agent.sockets, agent.pathSockets = append(agent.sockets, alt), 2
	_, dead := loopbackUDP(t) // No reply; the second address must be tried anyway.
	offerAgent.Candidates = []string{dead.String(), alt.LocalAddr().String()}
	// The client initiates because its advertised mapping is unreachable.
	offerClient.Candidates = nil
	ready := make(chan struct{})
	clientReady := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		link, err := agent.Connect(ctx, *offerClient, clientReady, func() error { close(ready); return nil })
		if err == nil {
			defer link.Close()
			if !link.UsesBoundSource() {
				err = errors.New("QUIC did not use the alternate bound socket")
			} else {
				err = link.Write([]byte("alternate path"))
			}
			if err == nil {
				err = sendDownloadTestFile(ctx, link, string(protocol.PhoneFileDownload)+fileID, fileContents)
			}
			if err == nil {
				_, err = link.Read()
			}
		}
		done <- err
	}()
	link, err := client.Connect(ctx, *offerAgent, ready, func() error { close(clientReady); return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer link.Close()
	message, err := link.Read()
	if err != nil || string(message) != "alternate path" {
		t.Fatalf("read = %q, %v", message, err)
	}
	var file strings.Builder
	if err := link.DownloadFile(ctx, protocol.PhoneFileDownload, fileID, int64(len(fileContents)), &file); err != nil || file.String() != fileContents {
		t.Fatalf("download = %q, %v", file.String(), err)
	}
	if err := link.Write([]byte("received")); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if !DirectReady(agent.State()) || !DirectReady(client.State()) {
		t.Fatalf("agent %s client %s, want connected", agent.State(), client.State())
	}
}

func sendDownloadTestFile(ctx context.Context, link *Link, header, payload string) error {
	stream, err := link.AcceptStream(ctx)
	if err != nil {
		return err
	}
	deadline, _ := ctx.Deadline()
	_ = stream.SetDeadline(deadline)
	got, err := io.ReadAll(io.LimitReader(stream, int64(len(header)+1)))
	if err != nil || string(got) != header {
		stream.CancelRead(1)
		stream.CancelWrite(1)
		return errors.New("unexpected direct download request")
	}
	if _, err := io.WriteString(stream, payload); err != nil {
		return err
	}
	return stream.Close()
}

func TestPrepareForceRelay(t *testing.T) {
	start := protocol.P2P("start", "test-session-without-peer-candidates")
	start.Secret = strings.Repeat("ab", 32)
	start.ForceRelay = true
	if _, _, err := PrepareWithSources(t.Context(), true, *start, nil); !errors.Is(err, ErrForceRelay) {
		t.Fatalf("got %v, want ErrForceRelay", err)
	}
}

func TestSwitchEgressRequiresLiveConnection(t *testing.T) {
	l := &Link{}
	if err := l.SwitchEgress("eth0"); !errors.Is(err, ErrSwitchUnavailable) {
		t.Fatalf("got %v", err)
	}
}

func TestDisableFullPunchOmitsPredictedTargets(t *testing.T) {
	a, _ := loopbackUDP(t)
	s := &Session{agent: true, sockets: []*net.UDPConn{a}, pathSockets: 1, disableFullPunch: true}
	linear := netip.MustParseAddrPort("192.0.2.2:40500")
	hints := map[netip.AddrPort]protocol.P2PMappingHint{linear: {Mapping: protocol.P2PMappingLinear, Step: 7}}
	plans := s.plans(s.rankedPaths(), []netip.AddrPort{linear}, hints, time.Now(), 0, dualNATProbeTargets)
	if len(plans) != 0 {
		t.Fatalf("expected no expansion plans, got %+v", plans)
	}
}
