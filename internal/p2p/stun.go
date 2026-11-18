package p2p

import (
	"bytes"
	"crypto/rand"
	"encoding/binary"
	"net/netip"
)

func bindingRequest() []byte {
	var req [20]byte
	binary.BigEndian.PutUint16(req[:], 1)
	binary.BigEndian.PutUint32(req[4:], 0x2112a442)
	if _, err := rand.Read(req[8:]); err != nil {
		return nil
	}
	return req[:]
}

// The discovery reader already matched the queried source endpoint.
func bindingReply(req, buf []byte) netip.AddrPort {
	if len(buf) < 20 || binary.BigEndian.Uint16(buf) != 0x101 || !bytes.Equal(buf[4:20], req[4:20]) {
		return netip.AddrPort{}
	}
	end := 20 + int(binary.BigEndian.Uint16(buf[2:]))
	if end != len(buf) || end%4 != 0 {
		return netip.AddrPort{}
	}
	var mapped, xorMapped netip.AddrPort
	for off := 20; off+4 <= end; {
		kind := binary.BigEndian.Uint16(buf[off:])
		size := int(binary.BigEndian.Uint16(buf[off+2:]))
		off += 4
		padded := (size + 3) &^ 3
		if off+padded > end {
			return netip.AddrPort{}
		}
		if (kind == 0x20 || kind == 1) && size == 8 && buf[off+1] == 1 {
			port := binary.BigEndian.Uint16(buf[off+2:])
			addr := [4]byte(buf[off+4 : off+8])
			if kind == 0x20 {
				port ^= 0x2112
				for j := range addr {
					addr[j] ^= req[4+j]
				}
			}
			if port != 0 {
				value := netip.AddrPortFrom(netip.AddrFrom4(addr), port)
				if kind == 0x20 {
					xorMapped = value
				} else {
					mapped = value
				}
			}
		}
		off += padded
	}
	if xorMapped.IsValid() {
		return xorMapped
	}
	return mapped
}
