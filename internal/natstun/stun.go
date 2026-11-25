package natstun

import (
	"encoding/binary"
	"hash/crc32"
	"net/netip"
)

const (
	headerSize = 20
	maxRequest = 512
	cookie     = 0x2112a442
	changePort = 2
	changeIP   = 4
)

type binding struct {
	id                  [16]byte
	flags               byte
	modern, fingerprint bool
}

// Parse the Binding subset used by the two CodexRM NAT suites. Unsupported
// mandatory attributes are dropped so they cannot change the measured behavior.
func parseBinding(packet []byte) (binding, bool) {
	var req binding
	if len(packet) < headerSize || len(packet) > maxRequest || len(packet)%4 != 0 ||
		binary.BigEndian.Uint16(packet) != 1 || int(binary.BigEndian.Uint16(packet[2:])) != len(packet)-headerSize {
		return req, false
	}
	copy(req.id[:], packet[4:headerSize])
	req.modern = binary.BigEndian.Uint32(req.id[:]) == cookie
	changeSeen := false
	for offset := headerSize; offset < len(packet); {
		kind, size := binary.BigEndian.Uint16(packet[offset:]), int(binary.BigEndian.Uint16(packet[offset+2:]))
		end := offset + 4 + ((size + 3) &^ 3)
		if end > len(packet) {
			return req, false
		}
		value := packet[offset+4 : offset+4+size]
		switch kind {
		case 3: // CHANGE-REQUEST
			if changeSeen || size != 4 {
				return req, false
			}
			flags := binary.BigEndian.Uint32(value)
			if flags&^uint32(changeIP|changePort) != 0 {
				return req, false
			}
			changeSeen, req.flags = true, byte(flags)
		case 0x8028: // FINGERPRINT must be the final attribute.
			if !req.modern || size != 4 || end != len(packet) || binary.BigEndian.Uint32(value) != fingerprint(packet[:offset]) {
				return req, false
			}
			req.fingerprint = true
		default:
			if kind < 0x8000 {
				return req, false
			}
		}
		offset = end
	}
	return req, true
}

func fingerprint(packet []byte) uint32 { return crc32.ChecksumIEEE(packet) ^ 0x5354554e }

func appendAddress(packet []byte, kind uint16, addr netip.AddrPort) []byte {
	packet = binary.BigEndian.AppendUint16(packet, kind)
	packet = append(packet, 0, 8, 0, 1)
	ip, port := binary.BigEndian.Uint32(addr.Addr().AsSlice()), addr.Port()
	if kind == 0x20 {
		ip ^= cookie
		port ^= cookie >> 16
	}
	packet = binary.BigEndian.AppendUint16(packet, port)
	return binary.BigEndian.AppendUint32(packet, ip)
}

func bindingReply(req binding, mapped, origin, other netip.AddrPort) []byte {
	packet := make([]byte, headerSize, 64)
	binary.BigEndian.PutUint16(packet, 0x101)
	copy(packet[4:], req.id[:])
	if req.modern {
		packet = appendAddress(packet, 0x20, mapped)   // XOR-MAPPED-ADDRESS
		packet = appendAddress(packet, 0x802b, origin) // RESPONSE-ORIGIN
		packet = appendAddress(packet, 0x802c, other)  // OTHER-ADDRESS
	} else {
		packet = appendAddress(packet, 1, mapped) // MAPPED-ADDRESS
		packet = appendAddress(packet, 4, origin) // SOURCE-ADDRESS
		packet = appendAddress(packet, 5, other)  // CHANGED-ADDRESS
	}
	return finishBinding(packet, req.fingerprint)
}

func bindingUnavailable(req binding) []byte {
	packet := make([]byte, headerSize, 64)
	binary.BigEndian.PutUint16(packet, 0x111)
	copy(packet[4:], req.id[:])
	// ERROR-CODE 500 with a short reason phrase and four-byte padding.
	packet = append(packet, 0, 9, 0, 16, 0, 0, 5, 0)
	packet = append(packet, "Server Error"...)
	return finishBinding(packet, req.fingerprint)
}

func finishBinding(packet []byte, includeFingerprint bool) []byte {
	length := len(packet) - headerSize
	if includeFingerprint {
		length += 8
	}
	binary.BigEndian.PutUint16(packet[2:], uint16(length))
	if includeFingerprint {
		value := fingerprint(packet)
		packet = append(packet, 0x80, 0x28, 0, 4)
		packet = binary.BigEndian.AppendUint32(packet, value)
	}
	return packet
}
