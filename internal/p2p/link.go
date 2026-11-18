package p2p

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

type Link struct {
	lan    bool
	stream *quic.Stream
	conn   *quic.Conn
	udp    *net.UDPConn
	trans  *quic.Transport
	once   sync.Once
}

func (l *Link) IsLAN() bool { return l.lan }

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

func (l *Link) Close() {
	l.once.Do(func() {
		_ = l.conn.CloseWithError(0, "")
		_ = l.trans.Close()
		_ = l.udp.Close()
	})
}
