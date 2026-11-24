package p2p

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net"
	"net/netip"
	"os"
	"strings"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
)

const (
	verifySize  = 4 + 32 + 6 + 16
	verifySize6 = 4 + 32 + 18 + 16
)

// UDPVerifier issues source receipts only for attempts with a valid RM ticket.
// Requests and replies have the same fixed size; packets create no server state.
type UDPVerifier struct {
	conn *net.UDPConn
	key  [32]byte
}

func ListenVerifier(addr netip.AddrPort, keyFile string) (*UDPVerifier, error) {
	v := new(UDPVerifier)
	if keyFile == "" {
		if _, err := rand.Read(v.key[:]); err != nil {
			return nil, err
		}
	} else {
		data, err := os.ReadFile(keyFile)
		if err != nil {
			return nil, err
		}
		key := strings.TrimSpace(string(data))
		if len(key) != hex.EncodedLen(len(v.key)) {
			return nil, errors.New("UDP verifier key file must hold 32 bytes in hex")
		}
		if _, err := hex.Decode(v.key[:], []byte(key)); err != nil {
			return nil, errors.New("UDP verifier key file contains invalid hex")
		}
	}
	var err error
	family := "udp4"
	if addr.Addr().Is6() {
		family = "udp6"
	}
	v.conn, err = net.ListenUDP(family, net.UDPAddrFromAddrPort(addr))
	if err != nil {
		return nil, err
	}
	go v.serve()
	return v, nil
}

func (v *UDPVerifier) Addr() netip.AddrPort { return v.conn.LocalAddr().(*net.UDPAddr).AddrPort() }
func (v *UDPVerifier) Close()               { _ = v.conn.Close() }

func (v *UDPVerifier) mac(label string, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, v.key[:])
	h.Write([]byte(label))
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)[:16]
}

func (v *UDPVerifier) Ticket(now time.Time) (string, error) {
	var ticket [32]byte
	binary.BigEndian.PutUint32(ticket[:], uint32(now.Add(protocol.P2PAttempt).Unix()+1))
	if _, err := rand.Read(ticket[4:16]); err != nil {
		return "", err
	}
	copy(ticket[16:], v.mac("ticket", ticket[:16]))
	return hex.EncodeToString(ticket[:]), nil
}

func (v *UDPVerifier) validTicket(ticket []byte, now time.Time) bool {
	return len(ticket) == 32 && now.Unix() < int64(binary.BigEndian.Uint32(ticket)) &&
		hmac.Equal(ticket[16:], v.mac("ticket", ticket[:16]))
}

func addrBytes(a netip.AddrPort) []byte {
	b := append(a.Addr().AsSlice(), 0, 0)
	binary.BigEndian.PutUint16(b[len(b)-2:], a.Port())
	return b
}

func (v *UDPVerifier) Check(ticket, proof string, a netip.AddrPort, now time.Time) bool {
	if len(ticket) != 64 || len(proof) != 32 || (!a.Addr().Is4() && !IsPublicIPv6(a.Addr()) && a.Addr() != netip.IPv6Loopback()) || a.Port() == 0 {
		return false
	}
	t, err := hex.DecodeString(ticket)
	if err != nil || !v.validTicket(t, now) {
		return false
	}
	p, err := hex.DecodeString(proof)
	b := addrBytes(a)
	return err == nil && hmac.Equal(p, v.mac("address", t, b))
}

func (v *UDPVerifier) serve() {
	var b [verifySize6 + 1]byte
	for {
		n, from, err := v.conn.ReadFromUDPAddrPort(b[:])
		if err != nil {
			return
		}
		if from.Port() == 0 {
			continue
		}
		var reply string
		switch {
		case n == verifySize && from.Addr().Is4() && string(b[:4]) == "CRV1":
			reply = "CRV2"
		case n == verifySize6 && from.Addr().Is6() && string(b[:4]) == "CRV3":
			reply = "CRV4"
		default:
			continue
		}
		if !v.validTicket(b[4:36], time.Now()) {
			continue
		}
		a := addrBytes(from)
		copy(b[:4], reply)
		copy(b[36:], a)
		copy(b[36+len(a):], v.mac("address", b[4:36], a))
		_, _ = v.conn.WriteToUDPAddrPort(b[:n], from)
	}
}

func verifyRequestFor(ticket string, ipv6 bool) []byte {
	if len(ticket) != 64 {
		return nil
	}
	size, magic := verifySize, "CRV1"
	if ipv6 {
		size, magic = verifySize6, "CRV3"
	}
	req := make([]byte, size)
	copy(req[:4], magic)
	if _, err := hex.Decode(req[4:36], []byte(ticket)); err != nil {
		return nil
	}
	return req
}

func verifyReply(req, buf []byte) (netip.AddrPort, string) {
	if len(buf) != len(req) {
		return netip.AddrPort{}, ""
	}
	var addr netip.Addr
	var ipEnd int
	switch {
	case len(buf) == verifySize && string(buf[:4]) == "CRV2":
		addr, ipEnd = netip.AddrFrom4([4]byte(buf[36:40])), 40
	case len(buf) == verifySize6 && string(buf[:4]) == "CRV4":
		addr, ipEnd = netip.AddrFrom16([16]byte(buf[36:52])), 52
	default:
		return netip.AddrPort{}, ""
	}
	port := binary.BigEndian.Uint16(buf[ipEnd : ipEnd+2])
	if port == 0 || !bytes.Equal(req[4:36], buf[4:36]) {
		return netip.AddrPort{}, ""
	}
	return netip.AddrPortFrom(addr, port), hex.EncodeToString(buf[ipEnd+2:])
}
