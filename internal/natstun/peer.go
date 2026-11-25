package natstun

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"time"
)

// Version, ingress port index, Unix time, nonce, observed IPv4:port,
// original Binding request, and HMAC-SHA256. Both public IPs bind the tag
// to one direction and one node pair. No arbitrary payload is ever sent to
// the observed address: the receiving node builds a STUN reply itself.
const (
	peerHeader     = 24
	peerVersion    = 1
	peerLifetime   = 5 * time.Second
	peerAckTimeout = 350 * time.Millisecond
	peerAck        = 0x80
)

func (s *Server) peerMAC(sender, recipient netip.Addr, body []byte) []byte {
	mac := hmac.New(sha256.New, s.cfg.Key[:])
	_, _ = mac.Write([]byte("codexrm nat-stun peer v1\x00"))
	_, _ = mac.Write(sender.AsSlice())
	_, _ = mac.Write(recipient.AsSlice())
	_, _ = mac.Write(body)
	return mac.Sum(nil)
}

func (s *Server) encodePeer(in int, client netip.AddrPort, request []byte, now time.Time) []byte {
	packet := make([]byte, peerHeader, peerHeader+len(request)+sha256.Size)
	packet[0], packet[1] = peerVersion, byte(in)
	binary.BigEndian.PutUint64(packet[2:10], uint64(now.UnixMilli()))
	if _, err := rand.Read(packet[10:18]); err != nil {
		return nil
	}
	copy(packet[18:22], client.Addr().AsSlice())
	binary.BigEndian.PutUint16(packet[22:24], client.Port())
	packet = append(packet, request...)
	return append(packet, s.peerMAC(s.cfg.PublicIP, s.cfg.OtherIP, packet)...)
}

type peerPacket struct {
	in      int
	client  netip.AddrPort
	request binding
	nonce   [8]byte
	expires time.Time
}

func (s *Server) decodePeer(packet []byte, now time.Time) (peerPacket, bool) {
	var result peerPacket
	if len(packet) < peerHeader+headerSize+sha256.Size || len(packet) > peerHeader+maxRequest+sha256.Size || packet[0] != peerVersion || packet[1] > 1 {
		return result, false
	}
	stamp := time.UnixMilli(int64(binary.BigEndian.Uint64(packet[2:10])))
	if stamp.Before(now.Add(-peerLifetime)) || stamp.After(now.Add(peerLifetime)) {
		return result, false
	}
	body := packet[:len(packet)-sha256.Size]
	if !hmac.Equal(packet[len(body):], s.peerMAC(s.cfg.OtherIP, s.cfg.PublicIP, body)) {
		return result, false
	}
	client := decodeAddr(body[18:24])
	request, ok := parseBinding(body[peerHeader:])
	if !s.mayReply(client) || !ok || request.flags&changeIP == 0 {
		return result, false
	}
	return peerPacket{int(body[1]), client, request, [8]byte(body[10:18]), stamp.Add(peerLifetime)}, true
}

func (s *Server) servePeer() {
	defer s.wg.Done()
	var buf [peerHeader + maxRequest + sha256.Size + 1]byte
	seen := make(map[[8]byte]time.Time)
	var cleanedAt int64
	for {
		n, from, err := s.peer.ReadFromUDPAddrPort(buf[:])
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		if from != s.cfg.PeerRemote {
			continue
		}
		if n == 10+sha256.Size && buf[0] == peerVersion && buf[1] == peerAck {
			s.acceptAck(buf[:n])
			continue
		}
		now := time.Now()
		packet, ok := s.decodePeer(buf[:n], now)
		if !ok {
			continue
		}
		if second := now.Unix(); cleanedAt != second {
			for nonce, expires := range seen {
				if now.After(expires) {
					delete(seen, nonce)
				}
			}
			cleanedAt = second
		}
		if _, duplicate := seen[packet.nonce]; duplicate || len(seen) >= 2048 || !s.limit.allow(packet.client.Addr(), now) {
			continue
		}
		seen[packet.nonce] = packet.expires
		if s.reply(packet.in, packet.client, packet.request, true) {
			ack := append([]byte{peerVersion, peerAck}, packet.nonce[:]...)
			ack = append(ack, s.peerMAC(s.cfg.PublicIP, s.cfg.OtherIP, ack)...)
			_, _ = s.peer.WriteToUDPAddrPort(ack, s.cfg.PeerRemote)
		}
	}
}

// Absence of a client-visible response is meaningful only after the other
// node has sent it. Report a server error on a failed handoff so CodexRM
// won't interpret a broken inter-node path as restrictive NAT filtering.
func (s *Server) forward(in int, from netip.AddrPort, req binding, wire []byte) {
	unavailable := func() {
		_, _ = s.client[in].WriteToUDPAddrPort(bindingUnavailable(req), from)
	}
	frame := s.encodePeer(in, from, wire, time.Now())
	if frame == nil {
		unavailable()
		return
	}
	nonce := [8]byte(frame[10:18])
	ack := make(chan struct{}, 1)
	s.acks.Store(nonce, ack)
	if _, err := s.peer.WriteToUDPAddrPort(frame, s.cfg.PeerRemote); err != nil {
		s.acks.Delete(nonce)
		unavailable()
		return
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer s.acks.Delete(nonce)
		delay := time.NewTimer(peerAckTimeout)
		defer delay.Stop()
		select {
		case <-ack:
		case <-s.stop:
		case <-delay.C:
			unavailable()
		}
	}()
}

func (s *Server) acceptAck(frame []byte) {
	ack, ok := s.acks.Load([8]byte(frame[2:10]))
	if !ok || !hmac.Equal(frame[10:], s.peerMAC(s.cfg.OtherIP, s.cfg.PublicIP, frame[:10])) {
		return
	}
	select {
	case ack.(chan struct{}) <- struct{}{}:
	default:
	}
}
