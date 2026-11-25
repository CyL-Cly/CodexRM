//go:build android && cgo

package main

/*
#include <stdint.h>
*/
import "C"

import (
	"bytes"
	"context"
	"encoding/json"
	"strconv"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

const latencyProbePrefix = "android-rtt:"

type latencyProbe struct {
	id      string
	started time.Time
	reply   chan time.Duration
}

// Consume the probe on the native reader before handing ordinary replies to
// Android. Whether the UI is opening a dialog or rendering a turn is not RTT.
func (s *mobileSession) consumeLatencyReply(frame []byte) bool {
	p := s.probe.Load()
	if p == nil || !bytes.Contains(frame, []byte(p.id)) {
		return false
	}
	elapsed := time.Since(p.started)
	var env protocol.Envelope
	if json.Unmarshal(frame, &env) != nil || env.Type != protocol.TypeServerMessage || env.StreamID != p.id {
		return false
	}
	var reply struct {
		ID     string          `json:"id"`
		Result json.RawMessage `json:"result"`
		Error  json.RawMessage `json:"error"`
	}
	if json.Unmarshal(env.Message, &reply) != nil || reply.ID != p.id || len(reply.Error) > 0 || len(reply.Result) == 0 {
		elapsed = 0
	}
	select {
	case p.reply <- elapsed:
	default:
	}
	return true
}

//export CRMMeasureLatency
func CRMMeasureLatency(h C.int64_t) C.int64_t {
	s := lookup(h)
	if s == nil || s.ctx.Err() != nil || s.link.Load() == nil || !s.probeMu.TryLock() {
		return 0
	}
	defer s.probeMu.Unlock()
	ctx, cancel := context.WithTimeout(s.ctx, 4*time.Second)
	defer cancel()
	s.probeSequence++
	id := latencyProbePrefix + strconv.FormatUint(s.probeSequence, 10)
	// Use the same ordered control stream as chat RPCs. String IDs are separate
	// from the Android client's numeric IDs, including on older Agents.
	message := json.RawMessage(`{"id":` + strconv.Quote(id) + `,"method":"codexrm/info","params":{"latencyProbe":true}}`)
	frame, err := json.Marshal(protocol.Envelope{Type: protocol.TypeClientMessage, StreamID: id, Message: message})
	if err != nil {
		return 0
	}
	p := &latencyProbe{id: id, started: time.Now(), reply: make(chan time.Duration, 1)}
	s.probe.Store(p)
	defer s.probe.Store(nil)
	select {
	case s.out <- frame:
	case <-ctx.Done():
		return 0
	}
	select {
	case elapsed := <-p.reply:
		return C.int64_t(elapsed.Nanoseconds())
	case <-ctx.Done():
		return 0
	}
}
