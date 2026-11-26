//go:build android && cgo

package main

/*
#cgo LDFLAGS: -landroid
#include <android/multinetwork.h>
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/CyL-Cly/CodexRM-Server/p2p"
	"github.com/CyL-Cly/CodexRM-Server/protocol"
)

type mobileSession struct {
	ctx           context.Context
	cancel        context.CancelFunc
	id            string
	offer         chan protocol.P2PSignal
	ready, active chan struct{}
	out, incoming chan []byte
	link          atomic.Pointer[p2p.Link]
	shareProgress atomic.Int32
	downloads     sync.Map   // read ID -> context.CancelFunc
	probeMu       sync.Mutex // one button measurement at a time
	probeSequence uint64
	probe         atomic.Pointer[latencyProbe]
}

var registry = struct {
	sync.Mutex
	next C.int64_t
	m    map[C.int64_t]*mobileSession
}{m: make(map[C.int64_t]*mobileSession)}

func lookup(h C.int64_t) *mobileSession {
	registry.Lock()
	defer registry.Unlock()
	return registry.m[h]
}

func (s *mobileSession) emit(ctx context.Context, sig *protocol.P2PSignal) bool {
	b, _ := json.Marshal(protocol.Envelope{Type: protocol.TypeP2P, P2P: sig})
	return s.emitBytes(ctx, b)
}

func (s *mobileSession) emitBytes(ctx context.Context, b []byte) bool {
	select {
	case s.incoming <- b:
		return true
	case <-ctx.Done():
		return false
	}
}

func (s *mobileSession) run(start protocol.P2PSignal, local []p2p.Source) {
	defer s.cancel()
	defer close(s.incoming)
	failure := "transport_closed"
	defer func() {
		if s.ctx.Err() == nil {
			b, _ := json.Marshal(map[string]string{"type": "native_direct_failure", "reason": failure})
			select {
			case s.incoming <- b:
			default:
			}
		}
	}()
	attempt, cancel := context.WithTimeout(s.ctx, protocol.P2PAttempt)
	defer cancel()
	prepared, offer, err := p2p.PrepareWithSources(attempt, false, start, local)
	if err != nil {
		if errors.Is(err, p2p.ErrForceRelay) {
			failure = "force_relay"
		} else {
			failure = "prepare"
		}
		log.Printf("CodexRM P2P prepare: %v", err)
		return
	}
	defer prepared.Close()
	log.Printf("CodexRM P2P prepare state=%s candidates=%d", prepared.State(), len(offer.Candidates))
	if !s.emit(attempt, offer) {
		return
	}
	var remote protocol.P2PSignal
	select {
	case <-attempt.Done():
		failure = "peer_offer_timeout"
		return
	case remote = <-s.offer:
	}
	link, err := prepared.Connect(attempt, remote, s.ready, func() error {
		if s.emit(attempt, protocol.P2P(protocol.P2PKindReady, s.id)) {
			return nil
		}
		return attempt.Err()
	})
	if err != nil {
		switch {
		case errors.Is(err, p2p.ErrNoPeerCandidate):
			failure = "no_peer_candidate"
		case errors.Is(err, p2p.ErrDualUnpredictableNAT):
			failure = protocol.P2PReasonDualUnpredictableNAT
		case errors.Is(err, p2p.ErrUDPPunch):
			failure = "punch_timeout"
		default:
			failure = "handshake"
		}
		log.Printf("CodexRM P2P negotiate: %v state=%s", err, prepared.State())
		return
	}
	if !p2p.DirectReady(prepared.State()) {
		failure = "handshake"
		log.Printf("CodexRM P2P negotiate: control stream not ready, state=%s", prepared.State())
		return
	}
	defer link.Close()
	path := link.Path()
	log.Printf("CodexRM P2P: QUIC established state=%s lan=%t ipv6=%t bound=%t channel=%s network=%s adapter=%s", prepared.State(), link.IsLAN(), link.IsIPv6(), link.UsesBoundSource(), path.Channel, path.Network, path.Adapter)
	if link.IsLAN() {
		// The Android side checks the network that actually won the QUIC
		// connection, which can differ from Android's default network.
		if !s.emitBytes(attempt, []byte(`{"type":"native_direct_lan","network":`+strconv.Quote(path.Network)+`}`)) {
			return
		}
	}
	s.link.Store(link)
	if !s.emit(attempt, protocol.P2P(protocol.P2PKindActivate, s.id)) {
		return
	}
	select {
	case <-attempt.Done():
		failure = "activation_timeout"
		return
	case <-s.active:
	}
	cancel()
	go link.WatchQuality(s.ctx)
	go func() { <-s.ctx.Done(); link.Close() }()
	go func() {
		defer s.cancel()
		for {
			select {
			case <-s.ctx.Done():
				return
			case b := <-s.out:
				if link.Write(b) != nil {
					return
				}
			}
		}
	}()
	for {
		b, err := link.Read()
		if err == nil && s.consumeLatencyReply(b) {
			continue
		}
		if err != nil || !s.emitBytes(s.ctx, b) {
			if fb := link.Fallback(); fb.LastReason != "" {
				failure = fb.LastReason
				prepared.MarkRelay()
				log.Printf("CodexRM P2P: fallback to RM relay reason=%s auto=%d manual=%d state=%s", fb.LastReason, fb.AutoRelaySwitches, fb.ManualRelaySwitches, prepared.State())
			}
			return
		}
	}
}

//export CRMOpen
func CRMOpen(ptr unsafe.Pointer, n C.int) C.int64_t {
	if n <= 0 || n > 4096 {
		return 0
	}
	var start struct {
		protocol.P2PSignal
		LocalPrefixes []string `json:"localPrefixes"`
		LocalSources  []struct {
			Network string `json:"network"`
			Prefix  string `json:"prefix"`
			LAN     bool   `json:"lan"`
		} `json:"localSources"`
	}
	if json.Unmarshal(C.GoBytes(ptr, n), &start) != nil || start.Kind != protocol.P2PKindStart || start.Version != protocol.P2PVersion || len(start.LocalPrefixes) > protocol.P2PMaxCandidates || len(start.LocalSources) > 12 {
		return 0
	}
	var local []p2p.Source
	for _, raw := range start.LocalPrefixes {
		if p, err := netip.ParsePrefix(raw); err == nil {
			local = append(local, p2p.Source{Prefix: p, LAN: true})
		}
	}
	for _, source := range start.LocalSources {
		prefix, perr := netip.ParsePrefix(source.Prefix)
		handle, herr := strconv.ParseInt(source.Network, 10, 64)
		if perr != nil || herr != nil || handle == 0 {
			continue
		}
		local = append(local, p2p.Source{Prefix: prefix, Network: source.Network, LAN: source.LAN, Bind: func(fd uintptr) error {
			if result, err := C.android_setsocknetwork(C.net_handle_t(uint64(handle)), C.int(fd)); result != 0 {
				if err == nil {
					err = errors.New("android_setsocknetwork failed")
				}
				return err
			}
			return nil
		}})
	}
	registry.Lock()
	defer registry.Unlock()
	// Keep one additional connection for sharing to a non-selected desktop.
	if len(registry.m) >= 2 {
		return 0
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &mobileSession{ctx: ctx, cancel: cancel, id: start.ID, offer: make(chan protocol.P2PSignal, 1), ready: make(chan struct{}, 1), active: make(chan struct{}, 1), out: make(chan []byte, 8), incoming: make(chan []byte, 2)}
	registry.next++
	registry.m[registry.next] = s
	go s.run(start.P2PSignal, local)
	return registry.next
}

//export CRMPoll
func CRMPoll(h C.int64_t, n *C.int) unsafe.Pointer {
	s := lookup(h)
	if s == nil {
		return nil
	}
	b, ok := <-s.incoming
	if !ok {
		return nil
	}
	*n = C.int(len(b))
	return C.CBytes(b)
}

//export CRMSignal
func CRMSignal(h C.int64_t, ptr unsafe.Pointer, n C.int) {
	s := lookup(h)
	if s == nil || n <= 0 || n > 4096 {
		return
	}
	var sig protocol.P2PSignal
	if json.Unmarshal(C.GoBytes(ptr, n), &sig) != nil || sig.ID != s.id || sig.Version != protocol.P2PVersion {
		return
	}
	switch sig.Kind {
	case protocol.P2PKindOffer:
		select {
		case s.offer <- sig:
		default:
		}
	case protocol.P2PKindReady:
		select {
		case s.ready <- struct{}{}:
		default:
		}
	case protocol.P2PKindActive:
		select {
		case s.active <- struct{}{}:
		default:
		}
	}
}

//export CRMSend
func CRMSend(h C.int64_t, ptr unsafe.Pointer, n C.int) C.int {
	s := lookup(h)
	if s == nil || s.ctx.Err() != nil || n <= 0 || n > protocol.MaxEnvelopeSize {
		return 0
	}
	// Fail fast instead of blocking Android's main thread on a congested link.
	if len(s.out) >= cap(s.out) {
		s.cancel()
		return 0
	}
	select {
	case s.out <- C.GoBytes(ptr, n):
		return 1
	case <-s.ctx.Done():
		return 0
	default:
		s.cancel()
		return 0
	}
}

//export CRMClose
func CRMClose(h C.int64_t) {
	registry.Lock()
	s := registry.m[h]
	delete(registry.m, h)
	registry.Unlock()
	if s != nil {
		s.cancel()
	}
}

func main() {}
