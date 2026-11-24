package agent

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/codexrm/codexrm/internal/desktop"
	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

func (a *Agent) serveDirectShares(d *directSession, link *p2p.Link) {
	for {
		stream, err := link.AcceptStream(d.ctx)
		if err != nil {
			return
		}
		_ = stream.SetReadDeadline(time.Now().Add(15 * time.Second))
		var first [1]byte
		if _, err := io.ReadFull(stream, first[:]); err != nil {
			stream.CancelRead(0)
			stream.CancelWrite(1)
			if d.ctx.Err() != nil {
				return
			}
			continue
		}
		if first[0] == desktop.StreamMagic {
			_ = stream.SetDeadline(time.Time{})
			go a.serveDesktopControl(d, stream)
			continue
		}
		a.finishDirectShare(d, stream, first[0])
	}
}

func (a *Agent) finishDirectShare(d *directSession, stream *quic.Stream, first byte) {
	defer stream.CancelRead(0)
	_ = stream.SetDeadline(time.Now().Add(15 * time.Second))
	var id [protocol.DirectShareIDSize]byte
	id[0] = first
	if _, err := io.ReadFull(stream, id[1:]); err != nil || !a.beginRequest() {
		stream.CancelWrite(1)
		return
	}
	defer a.endRequest()
	_ = stream.SetDeadline(time.Now().Add(protocol.DirectShareTimeout))
	if err := a.receiveDirectShare(d, string(id[:]), stream); err != nil {
		stream.CancelWrite(1)
		return
	}
	if _, err := stream.Write([]byte{protocol.DirectShareOK}); err != nil {
		stream.CancelWrite(1)
		return
	}
	_ = stream.Close()
}

func (a *Agent) receiveDirectShare(d *directSession, id string, source io.Reader) (err error) {
	a.attachmentMu.Lock()
	upload := a.uploads[id]
	if d.ctx.Err() != nil || upload == nil || upload.Owner != d.client || upload.DirectID != d.id || !upload.Streamed || upload.Offset != -1 || upload.Receiving {
		a.attachmentMu.Unlock()
		return errors.New("unknown direct desktop upload")
	}
	upload.Receiving = true
	a.attachmentMu.Unlock()
	defer func() {
		a.attachmentMu.Lock()
		defer a.attachmentMu.Unlock()
		upload.Receiving = false
		if err != nil || d.ctx.Err() != nil {
			a.removeDirectShare(id, upload)
			return
		}
		upload.Offset = upload.File.Size
		upload.Updated = time.Now()
	}()
	file, err := os.OpenFile(upload.File.Path+".part", os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, file.Close()) }()
	if _, err := io.CopyN(file, source, upload.File.Size); err != nil {
		return err
	}
	// Confirm the stream ended at the declared size before allowing finish.
	var extra [1]byte
	if _, err := io.ReadFull(source, extra[:]); err != io.EOF {
		return errors.New("desktop upload stream exceeds declared size or was interrupted")
	}
	return d.ctx.Err()
}

// The attachment mutex must be held by the caller.
func (a *Agent) removeDirectShare(id string, upload *attachmentUpload) {
	_ = os.Remove(upload.File.Path + ".part")
	_ = os.Remove(filepath.Dir(upload.File.Path))
	delete(a.uploads, id)
}

func (a *Agent) clearDirectShares(d *directSession) {
	a.attachmentMu.Lock()
	defer a.attachmentMu.Unlock()
	for id, upload := range a.uploads {
		if upload.Owner == d.client && upload.DirectID == d.id && !upload.Receiving {
			a.removeDirectShare(id, upload)
		}
	}
}
