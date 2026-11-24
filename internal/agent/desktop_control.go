package agent

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/desktop"
	"github.com/quic-go/quic-go"
)

var desktopSessions struct {
	sync.Mutex
	active bool
}

func acquireDesktop() bool {
	desktopSessions.Lock()
	defer desktopSessions.Unlock()
	if desktopSessions.active {
		return false
	}
	desktopSessions.active = true
	return true
}

func releaseDesktop() {
	desktopSessions.Lock()
	desktopSessions.active = false
	desktopSessions.Unlock()
}

type desktopGeometry struct {
	mu    sync.Mutex
	frame desktop.Frame
}

func (g *desktopGeometry) set(frame desktop.Frame) {
	frame.JPEG = nil
	g.mu.Lock()
	g.frame = frame
	g.mu.Unlock()
}

func (g *desktopGeometry) get() desktop.Frame {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.frame
}

func (a *Agent) serveDesktopControl(d *directSession, stream *quic.Stream) {
	defer stream.CancelRead(0)
	defer stream.CancelWrite(0)
	if !desktop.Supported() || !acquireDesktop() {
		return
	}
	defer releaseDesktop()
	if !a.beginRequest() {
		return
	}
	defer a.endRequest()
	defer desktop.ReleaseInputs()
	ctx, cancel := context.WithCancel(d.ctx)
	defer cancel()
	geometry := &desktopGeometry{}
	go func() {
		defer cancel()
		a.streamDesktop(ctx, stream, geometry)
		stream.CancelRead(0)
	}()
	mode := desktop.ModeMouse
	for {
		kind, payload, err := desktop.ReadMessage(stream)
		if err != nil || ctx.Err() != nil {
			return
		}
		switch kind {
		case desktop.MsgPointer:
			pointer, err := desktop.DecodePointer(payload)
			if err != nil || (pointer.Action != desktop.PointerDown && pointer.Action != desktop.PointerMove && pointer.Action != desktop.PointerUp) {
				continue
			}
			_ = desktop.ApplyPointer(pointer, mode, geometry.get())
		case desktop.MsgKey:
			key, err := desktop.DecodeKey(payload)
			if err != nil {
				continue
			}
			_ = desktop.ApplyKey(key)
		case desktop.MsgMode:
			if len(payload) == 1 && (payload[0] == desktop.ModeMouse || payload[0] == desktop.ModeTablet) {
				mode = payload[0]
			}
		}
	}
}

func (a *Agent) streamDesktop(ctx context.Context, stream *quic.Stream, geometry *desktopGeometry) {
	quality := 55
	edge := 1280
	failures := 0
	var wait time.Duration
	for ctx.Err() == nil {
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
		started := time.Now()
		frame, err := desktop.CaptureJPEG(edge, quality)
		if err != nil {
			failures++
			if failures >= 25 {
				log.Printf("desktop capture: %v", err)
				return
			}
			wait = 120 * time.Millisecond
			continue
		}
		failures = 0
		if len(frame.JPEG) > 380000 && (quality > 35 || edge > 960) {
			quality = 35
			edge = 960
		}
		geometry.set(frame)
		payload, err := desktop.FramePayload(frame.Width, frame.Height, frame.JPEG)
		if err != nil {
			return
		}
		_ = stream.SetWriteDeadline(time.Now().Add(3 * time.Second))
		if err := desktop.WriteMessage(stream, desktop.MsgFrame, payload); err != nil {
			return
		}
		wait = 125*time.Millisecond - time.Since(started)
		if wait < 0 {
			wait = 0
		}
	}
}
