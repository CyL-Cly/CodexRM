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

const verifySize = 4 + 32 + 6 + 16

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
	v.conn, err = net.ListenUDP("udp4", net.UDPAddrFromAddrPort(addr))
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

func addrBytes(a netip.AddrPort) [6]byte {
	ip := a.Addr().As4()
	b := [6]byte{ip[0], ip[1], ip[2], ip[3]}
	binary.BigEndian.PutUint16(b[4:], a.Port())
	return b
}

func (v *UDPVerifier) Check(ticket, proof string, a netip.AddrPort, now time.Time) bool {
	if len(ticket) != 64 || len(proof) != 32 || !a.Addr().Is4() || a.Port() == 0 {
		return false
	}
	t, err := hex.DecodeString(ticket)
	if err != nil || !v.validTicket(t, now) {
		return false
	}
	p, err := hex.DecodeString(proof)
	b := addrBytes(a)
	return err == nil && hmac.Equal(p, v.mac("address", t, b[:]))
}

func (v *UDPVerifier) serve() {
	var b [verifySize + 1]byte
	for {
		n, from, err := v.conn.ReadFromUDPAddrPort(b[:])
		if err != nil {
			return
		}
		if n != verifySize || !from.Addr().Is4() || from.Port() == 0 || string(b[:4]) != "CRV1" || !v.validTicket(b[4:36], time.Now()) {
			continue
		}
		a := addrBytes(from)
		copy(b[:4], "CRV2")
		copy(b[36:42], a[:])
		copy(b[42:58], v.mac("address", b[4:36], a[:]))
		_, _ = v.conn.WriteToUDPAddrPort(b[:verifySize], from)
	}
}

func verifyRequest(ticket string) []byte {
	var req [verifySize]byte
	copy(req[:4], "CRV1")
	if len(ticket) != 64 {
		return nil
	}
	if _, err := hex.Decode(req[4:36], []byte(ticket)); err != nil {
		return nil
	}
	return req[:]
}

func verifyReply(req, buf []byte) (netip.AddrPort, string) {
	if len(buf) == verifySize && string(buf[:4]) == "CRV2" && bytes.Equal(req[4:36], buf[4:36]) {
		addr := netip.AddrFrom4([4]byte(buf[36:40]))
		if port := binary.BigEndian.Uint16(buf[40:42]); port != 0 {
			return netip.AddrPortFrom(addr, port), hex.EncodeToString(buf[42:58])
		}
	}
	return netip.AddrPort{}, ""
}
