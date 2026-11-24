//go:build android && cgo

package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/codexrm/codexrm/internal/desktop"
	"github.com/quic-go/quic-go"
)

type desktopClient struct {
	ctx     context.Context
	cancel  context.CancelFunc
	stream  *quic.Stream
	mu      sync.Mutex
	writeMu sync.Mutex
	jpeg    []byte
	gen     uint64
	readGen uint64
	bytes   atomic.Int64
	closed  atomic.Bool
}

func (s *mobileSession) startDesktop() bool {
	s.stopDesktop()
	link := s.link.Load()
	if link == nil || s.ctx.Err() != nil {
		return false
	}
	ctx, cancel := context.WithCancel(s.ctx)
	stream, err := link.OpenStream(ctx)
	if err != nil {
		cancel()
		return false
	}
	magic := []byte{desktop.StreamMagic}
	_ = stream.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := stream.Write(magic); err != nil {
		cancel()
		stream.CancelRead(0)
		stream.CancelWrite(0)
		return false
	}
	_ = stream.SetDeadline(time.Time{})
	client := &desktopClient{ctx: ctx, cancel: cancel, stream: stream}
	s.desktopMu.Lock()
	s.desktop = client
	s.desktopMu.Unlock()
	go client.read()
	return true
}

func (s *mobileSession) stopDesktop() {
	s.desktopMu.Lock()
	client := s.desktop
	s.desktop = nil
	s.desktopMu.Unlock()
	if client == nil {
		return
	}
	client.closed.Store(true)
	client.cancel()
	client.stream.CancelRead(0)
	client.stream.CancelWrite(0)
}

func (s *mobileSession) desktopActive() bool {
	s.desktopMu.Lock()
	client := s.desktop
	s.desktopMu.Unlock()
	return client != nil && !client.closed.Load()
}

func (s *mobileSession) takeDesktop() []byte {
	s.desktopMu.Lock()
	client := s.desktop
	s.desktopMu.Unlock()
	if client == nil {
		return nil
	}
	client.mu.Lock()
	defer client.mu.Unlock()
	if client.gen == client.readGen || len(client.jpeg) == 0 {
		return nil
	}
	client.readGen = client.gen
	out := make([]byte, len(client.jpeg))
	copy(out, client.jpeg)
	return out
}

func (s *mobileSession) desktopBytes() int64 {
	s.desktopMu.Lock()
	client := s.desktop
	s.desktopMu.Unlock()
	if client == nil {
		return 0
	}
	return client.bytes.Load()
}

func (s *mobileSession) sendDesktop(data []byte) bool {
	s.desktopMu.Lock()
	client := s.desktop
	s.desktopMu.Unlock()
	if client == nil || client.closed.Load() || len(data) == 0 || len(data) > desktop.MaxMessage+5 {
		return false
	}
	client.writeMu.Lock()
	defer client.writeMu.Unlock()
	_ = client.stream.SetWriteDeadline(time.Now().Add(2 * time.Second))
	for len(data) > 0 {
		n, err := client.stream.Write(data)
		if n > 0 {
			data = data[n:]
		}
		if err != nil || n == 0 {
			client.closed.Store(true)
			client.cancel()
			return false
		}
	}
	return true
}

func (d *desktopClient) read() {
	defer d.closed.Store(true)
	defer d.cancel()
	for d.ctx.Err() == nil {
		kind, payload, err := desktop.ReadMessage(d.stream)
		if err != nil {
			return
		}
		if kind != desktop.MsgFrame {
			continue
		}
		_, _, jpegBytes, err := desktop.DecodeFrame(payload)
		if err != nil || len(jpegBytes) < 2 || jpegBytes[0] != 0xff || jpegBytes[1] != 0xd8 {
			continue
		}
		d.mu.Lock()
		d.jpeg = append(d.jpeg[:0], jpegBytes...)
		d.gen++
		d.bytes.Add(int64(len(jpegBytes)))
		d.mu.Unlock()
	}
}

//export CRMDesktopStart
func CRMDesktopStart(h C.int64_t) C.int {
	session := lookup(h)
	if session == nil || !session.startDesktop() {
		return 0
	}
	return 1
}

//export CRMDesktopStop
func CRMDesktopStop(h C.int64_t) {
	if session := lookup(h); session != nil {
		session.stopDesktop()
	}
}

//export CRMDesktopActive
func CRMDesktopActive(h C.int64_t) C.int {
	session := lookup(h)
	if session == nil || !session.desktopActive() {
		return 0
	}
	return 1
}

//export CRMDesktopBytes
func CRMDesktopBytes(h C.int64_t) C.int64_t {
	session := lookup(h)
	if session == nil {
		return 0
	}
	return C.int64_t(session.desktopBytes())
}

//export CRMDesktopPoll
func CRMDesktopPoll(h C.int64_t, n *C.int) unsafe.Pointer {
	session := lookup(h)
	if session == nil {
		return nil
	}
	frame := session.takeDesktop()
	if len(frame) == 0 {
		return nil
	}
	*n = C.int(len(frame))
	return C.CBytes(frame)
}

//export CRMDesktopSend
func CRMDesktopSend(h C.int64_t, ptr unsafe.Pointer, n C.int) C.int {
	session := lookup(h)
	if session == nil || n <= 0 {
		return 0
	}
	if session.sendDesktop(C.GoBytes(ptr, n)) {
		return 1
	}
	return 0
}
