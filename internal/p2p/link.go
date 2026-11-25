package p2p

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"net"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

// Header and payload fit together in a normal QUIC packet for interactive messages.
const smallDirectPayload = 1024

type Link struct {
	lan    bool
	ipv6   bool
	path   PathInfo
	stream *quic.Stream
	conn   *quic.Conn
	udp    *net.UDPConn
	trans  *quic.Transport
	once   sync.Once
	frame  [4 + smallDirectPayload]byte // owned by the serialized writer

	statsMu sync.Mutex
	stats   FallbackStats
}

// FallbackStats records why a direct path was abandoned for the RM relay.
type FallbackStats struct {
	AutoRelaySwitches   int
	ManualRelaySwitches int
	LastReason          string
}

func (l *Link) IsLAN() bool    { return l.lan }
func (l *Link) IsIPv6() bool   { return l.ipv6 }
func (l *Link) Path() PathInfo { return l.path }

func (l *Link) Fallback() FallbackStats {
	l.statsMu.Lock()
	defer l.statsMu.Unlock()
	return l.stats
}

// SwitchEgress records a preferred network for the next direct attempt and
// closes this link so the owner can fall back to the RM relay and renegotiate.
// Agent and Android do not call this automatically: a live QUIC path is left
// on its current network, and a later retry uses the updated source list.
func (l *Link) SwitchEgress(network string) error {
	if l.conn == nil {
		return ErrSwitchUnavailable
	}
	select {
	case <-l.conn.Context().Done():
		return ErrSwitchUnavailable
	default:
	}
	RememberEgress(network)
	l.noteFallback(false, "manual_switch")
	l.Close()
	return nil
}
func (l *Link) UsesBoundSource() bool {
	return !l.udp.LocalAddr().(*net.UDPAddr).AddrPort().Addr().IsUnspecified()
}

func (l *Link) OpenStream(ctx context.Context) (*quic.Stream, error) {
	return l.conn.OpenStreamSync(ctx)
}

func (l *Link) AcceptStream(ctx context.Context) (*quic.Stream, error) {
	return l.conn.AcceptStream(ctx)
}

func (l *Link) Read() ([]byte, error) {
	var header [4]byte
	if _, err := io.ReadFull(l.stream, header[:]); err != nil {
		return nil, err
	}
	size := binary.BigEndian.Uint32(header[:])
	if size == 0 || size > protocol.MaxEnvelopeSize {
		return nil, errors.New("invalid direct frame size")
	}
	data := make([]byte, int(size))
	_, err := io.ReadFull(l.stream, data)
	return data, err
}

// The owner serializes writes and closes the link on any partial write.
func (l *Link) Write(data []byte) error {
	if len(data) == 0 || len(data) > protocol.MaxEnvelopeSize {
		return errors.New("invalid direct frame size")
	}
	_ = l.stream.SetWriteDeadline(time.Now().Add(5 * time.Second))
	return writeDirectFrame(l.stream, l.frame[:], data)
}

func writeDirectFrame(w io.Writer, scratch, data []byte) error {
	header := scratch[:4]
	binary.BigEndian.PutUint32(header, uint32(len(data)))
	if len(data) <= len(scratch)-len(header) {
		// A separate write for just the header can schedule a tiny UDP packet
		// before the payload is queued, costing an extra packet on both legs.
		frame := scratch[:len(header)+len(data)]
		copy(frame[len(header):], data)
		n, err := w.Write(frame)
		if err == nil && n != len(frame) {
			return io.ErrShortWrite
		}
		return err
	}
	// Avoid copying large history or attachment payloads just for framing.
	_, err := io.Copy(w, io.MultiReader(bytes.NewReader(header), bytes.NewReader(data)))
	return err
}

// WatchQuality closes a persistently lossy direct connection, letting the
// owner fall back to RM and renegotiate. Idle links rely on QUIC keepalives.
func (l *Link) WatchQuality(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	previous := l.conn.ConnectionStats()
	badWindows := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-l.conn.Context().Done():
			return
		case <-ticker.C:
			current := l.conn.ConnectionStats()
			if badLossWindow(previous, current) {
				badWindows++
			} else {
				badWindows = 0
			}
			previous = current
			if badWindows == 3 {
				log.Printf("p2p direct: sustained packet loss (sent=%d lost=%d), try to switch to relay", current.PacketsSent, current.PacketsLost)
				l.noteFallback(true, "auto_loss")
				l.Close()
				return
			}
		}
	}
}

func badLossWindow(previous, current quic.ConnectionStats) bool {
	// Packets declared lost can arrive late and lower the cumulative count.
	if current.PacketsSent <= previous.PacketsSent || current.PacketsLost <= previous.PacketsLost {
		return false
	}
	sent := current.PacketsSent - previous.PacketsSent
	lost := current.PacketsLost - previous.PacketsLost
	return sent >= 100 && lost >= (sent+1)/2
}

func (l *Link) noteFallback(auto bool, reason string) {
	l.statsMu.Lock()
	defer l.statsMu.Unlock()
	if auto {
		l.stats.AutoRelaySwitches++
	} else {
		l.stats.ManualRelaySwitches++
	}
	l.stats.LastReason = reason
}

func (l *Link) Close() {
	l.once.Do(func() {
		_ = l.conn.CloseWithError(0, "")
		_ = l.trans.Close()
		_ = l.udp.Close()
	})
}
