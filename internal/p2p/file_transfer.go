package p2p

import (
	"context"
	"errors"
	"io"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

const fileTransferBuffer = 256 << 10

// CopyFilePayload coalesces short stream reads into bounded disk writes and
// never consumes any bytes beyond the announced file size.
func CopyFilePayload(dst io.Writer, src io.Reader, size int64) error {
	if size < 0 || size > protocol.DirectFileMaxSize {
		return errors.New("invalid direct file size")
	}
	buf := make([]byte, min(int64(fileTransferBuffer), size))
	for size > 0 {
		part := buf[:min(int64(len(buf)), size)]
		if _, err := io.ReadFull(src, part); err != nil {
			return err
		}
		n, err := dst.Write(part)
		if err != nil {
			return err
		}
		if n != len(part) {
			return io.ErrShortWrite
		}
		size -= int64(n)
	}
	return nil
}

// ReceiveFilePayload only succeeds on a clean FIN at exactly size bytes. A
// reset (including after the last data byte) must discard the local partial.
func ReceiveFilePayload(dst io.Writer, src io.Reader, size int64) error {
	if err := CopyFilePayload(dst, src, size); err != nil {
		return err
	}
	var extra [1]byte
	_, err := io.ReadFull(src, extra[:])
	if err == io.EOF {
		return nil
	}
	if err == nil {
		return errors.New("direct file exceeded its announced size")
	}
	return err
}

type fileStreamOpener interface {
	OpenStreamSync(context.Context) (*quic.Stream, error)
}

// DownloadFile opens a separate bidirectional stream for an authorized read ID.
// The client sends only the stream kind and ID, followed by a FIN.
func (l *Link) DownloadFile(ctx context.Context, kind byte, id string, size int64, dst io.Writer) error {
	return downloadFile(ctx, l.conn, kind, id, size, dst)
}

func downloadFile(ctx context.Context, conn fileStreamOpener, kind byte, id string, size int64, dst io.Writer) error {
	if (kind != protocol.DirectFileDownload && kind != protocol.PhoneFileDownload) || len(id) != protocol.DirectShareIDSize || size < 0 || size > protocol.DirectFileMaxSize {
		return errors.New("invalid direct file request")
	}
	ctx, cancel := context.WithTimeout(ctx, protocol.DirectShareTimeout)
	defer cancel()
	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		return err
	}
	defer stream.CancelRead(0)
	stop := context.AfterFunc(ctx, func() { stream.CancelRead(1); stream.CancelWrite(1) })
	defer stop()
	deadline, _ := ctx.Deadline()
	_ = stream.SetDeadline(deadline)
	var header [1 + protocol.DirectShareIDSize]byte
	header[0] = kind
	copy(header[1:], id)
	if _, err := stream.Write(header[:]); err != nil {
		stream.CancelWrite(1)
		return err
	}
	if err := stream.Close(); err != nil {
		return err
	}
	return ReceiveFilePayload(dst, stream, size)
}
