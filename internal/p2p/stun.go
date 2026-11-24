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

type stunAddresses struct {
	mapped, other, changed netip.AddrPort
}

// The discovery reader already matched the queried source endpoint.
func bindingReply(req, buf []byte) netip.AddrPort { return parseBindingReply(req, buf).mapped }

func matchedSTUNReply(req, buf []byte, kind uint16) bool {
	return len(req) >= 20 && len(buf) >= 20 && len(buf)%4 == 0 &&
		binary.BigEndian.Uint16(buf) == kind && int(binary.BigEndian.Uint16(buf[2:])) == len(buf)-20 &&
		bytes.Equal(buf[4:20], req[4:20])
}

func bindingErrorCode(req, buf []byte) int {
	if !matchedSTUNReply(req, buf, 0x111) {
		return 0
	}
	for off := 20; off < len(buf); {
		kind := binary.BigEndian.Uint16(buf[off:])
		size := int(binary.BigEndian.Uint16(buf[off+2:]))
		off += 4
		padded := (size + 3) &^ 3
		if padded > len(buf)-off {
			return 0
		}
		if kind == 9 && size >= 4 {
			code := 100*int(buf[off+2]&7) + int(buf[off+3])
			if code >= 300 && code < 700 {
				return code
			}
			return 0
		}
		off += padded
	}
	return 0
}

func parseBindingReply(req, buf []byte) stunAddresses {
	var result stunAddresses
	if !matchedSTUNReply(req, buf, 0x101) {
		return result
	}
	var xorMapped netip.AddrPort
	modern := binary.BigEndian.Uint32(req[4:8]) == 0x2112a442
	for off := 20; off < len(buf); {
		kind := binary.BigEndian.Uint16(buf[off:])
		size := int(binary.BigEndian.Uint16(buf[off+2:]))
		off += 4
		padded := (size + 3) &^ 3
		if off+padded > len(buf) {
			return stunAddresses{}
		}
		if ((modern && (kind == 0x20 || kind == 0x802c)) || kind == 1 || (!modern && kind == 5)) && ((size == 8 && buf[off+1] == 1) || (size == 20 && buf[off+1] == 2)) {
			port := binary.BigEndian.Uint16(buf[off+2:])
			var addr [16]byte
			address := addr[:size-4]
			copy(address, buf[off+4:off+size])
			if kind == 0x20 {
				port ^= 0x2112
				for j := range address {
					address[j] ^= req[4+j]
				}
			}
			ip, _ := netip.AddrFromSlice(address)
			if port != 0 {
				value := netip.AddrPortFrom(ip.Unmap(), port)
				switch kind {
				case 0x20:
					xorMapped = value
				case 1:
					result.mapped = value
				case 0x802c:
					result.other = value
				case 5:
					result.changed = value
				}
			}
		}
		off += padded
	}
	if xorMapped.IsValid() {
		result.mapped = xorMapped
	}
	return result
}
