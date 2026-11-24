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

type Link struct {
	lan    bool
	ipv6   bool
	stream *quic.Stream
	conn   *quic.Conn
	udp    *net.UDPConn
	trans  *quic.Transport
	once   sync.Once
}

func (l *Link) IsLAN() bool  { return l.lan }
func (l *Link) IsIPv6() bool { return l.ipv6 }
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
	var header [4]byte
	binary.BigEndian.PutUint32(header[:], uint32(len(data)))
	_ = l.stream.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err := io.Copy(l.stream, io.MultiReader(bytes.NewReader(header[:]), bytes.NewReader(data)))
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
				log.Print("p2p direct: sustained packet loss, returning to RM relay")
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

func (l *Link) Close() {
	l.once.Do(func() {
		_ = l.conn.CloseWithError(0, "")
		_ = l.trans.Close()
		_ = l.udp.Close()
	})
}
