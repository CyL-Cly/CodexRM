//go:build android && cgo

package main

/*
#include <stdint.h>
*/
import "C"

import (
	"context"
	"io"
	"os"
	"unsafe"

	"github.com/CyL-Cly/CodexRM-Server/p2p"

	"github.com/CyL-Cly/CodexRM-Server/protocol"
)

//export CRMUpload
func CRMUpload(h C.int64_t, id unsafe.Pointer, idLen C.int, path unsafe.Pointer, pathLen C.int, size C.int64_t) C.int {
	s := lookup(h)
	if s == nil || idLen != protocol.DirectShareIDSize || pathLen <= 0 || pathLen > 4096 || size < 0 || size > protocol.DirectFileMaxSize {
		return 0
	}
	link := s.link.Load()
	if link == nil || s.ctx.Err() != nil {
		return 0
	}
	s.shareProgress.Store(0)
	file, err := os.Open(string(C.GoBytes(path, pathLen)))
	if err != nil {
		return 0
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || info.Size() != int64(size) || !info.Mode().IsRegular() {
		return 0
	}
	ctx, cancel := context.WithTimeout(s.ctx, protocol.DirectShareTimeout)
	defer cancel()
	stream, err := link.OpenStream(ctx)
	if err != nil {
		return 0
	}
	defer stream.CancelRead(0)
	deadline, _ := ctx.Deadline()
	_ = stream.SetDeadline(deadline)
	if _, err := stream.Write(C.GoBytes(id, idLen)); err != nil {
		stream.CancelWrite(1)
		return 0
	}
	reader := &shareReader{file: file, size: int64(size), session: s}
	if err := p2p.CopyFilePayload(stream, reader, int64(size)); err != nil {
		stream.CancelWrite(1)
		return 0
	}
	if err := stream.Close(); err != nil {
		return 0
	}
	var ack [1]byte
	if _, err := io.ReadFull(stream, ack[:]); err != nil || ack[0] != protocol.DirectShareOK {
		return 0
	}
	return 1
}

//export CRMUploadProgress
func CRMUploadProgress(h C.int64_t) C.int {
	s := lookup(h)
	if s == nil {
		return 0
	}
	return C.int(s.shareProgress.Load())
}

type shareReader struct {
	file       *os.File
	size, read int64
	session    *mobileSession
}

func (r *shareReader) Read(buf []byte) (int, error) {
	n, err := r.file.Read(buf)
	r.read += int64(n)
	r.session.shareProgress.Store(int32(min(99, r.read*100/r.size)))
	return n, err
}
