//go:build android && cgo

package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"runtime/cgo"
	"unsafe"

	"github.com/codexrm/codexrm/internal/securechannel"
	"github.com/flynn/noise"
)

type mobileSecure struct {
	handshake *noise.HandshakeState
	channel   *securechannel.Channel
	pairing   bool
}

//export CRMSecureOpen
func CRMSecureOpen(b unsafe.Pointer, n C.int) C.int64_t {
	if n <= 0 || n > 512 {
		return 0
	}
	var cfg struct {
		Client, Route string
		Key           []byte
	}
	if json.Unmarshal(C.GoBytes(b, n), &cfg) != nil || len(cfg.Key) != securechannel.KeySize {
		return 0
	}
	h, err := securechannel.Handshake(true, cfg.Client, cfg.Route, securechannel.Key(cfg.Key))
	if err != nil {
		return 0
	}
	return C.int64_t(cgo.NewHandle(&mobileSecure{handshake: h, pairing: cfg.Route != ""}))
}

//export CRMSecureCall
func CRMSecureCall(handle C.int64_t, action C.int, b unsafe.Pointer, n C.int, size *C.int) unsafe.Pointer {
	if handle == 0 || n < 0 || n > securechannel.MaxPlaintext+4096 {
		return nil
	}
	s := cgo.Handle(handle).Value().(*mobileSecure)
	var out []byte
	var err error
	switch action {
	case 0:
		if s.handshake == nil {
			return nil
		}
		out, _, _, err = s.handshake.WriteMessage(nil, nil)
	case 1:
		if s.handshake == nil || (s.pairing && n != 80) || (!s.pairing && n != 48) {
			return nil
		}
		var up, down *noise.CipherState
		out, up, down, err = s.handshake.ReadMessage(nil, C.GoBytes(b, n))
		s.handshake = nil
		if err == nil {
			s.channel = securechannel.InitiatorChannel(up, down)
		}
	case 2, 3:
		if s.channel == nil {
			return nil
		}
		if action == 2 {
			out, err = s.channel.Seal(C.GoBytes(b, n))
		} else {
			out, err = s.channel.Open(C.GoBytes(b, n))
		}
	default:
		return nil
	}
	if err != nil {
		return nil
	}
	*size = C.int(len(out))
	if len(out) == 0 {
		return C.malloc(1)
	}
	return C.CBytes(out)
}

//export CRMSecureClose
func CRMSecureClose(handle C.int64_t) {
	if handle != 0 {
		cgo.Handle(handle).Delete()
	}
}
