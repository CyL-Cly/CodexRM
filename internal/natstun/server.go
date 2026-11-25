package natstun

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"
)

// The two nodes use the same public port pair. ListenIP can be a cloud
// instance's private address when both ports have one-to-one NAT.
type Config struct {
	ListenIP, PublicIP, OtherIP netip.Addr
	Ports                       [2]uint16
	PeerListen, PeerRemote      netip.AddrPort
	Key                         [32]byte
}

const maxRequestsPerSecond = 100

var nonPublic = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"),
}

func publicIPv4(ip netip.Addr) bool {
	if !ip.Is4() || !ip.IsGlobalUnicast() || ip.IsPrivate() {
		return false
	}
	for _, prefix := range nonPublic {
		if prefix.Contains(ip) {
			return false
		}
	}
	return true
}

type Server struct {
	cfg    Config
	client [2]*net.UDPConn
	peer   *net.UDPConn
	limit  sourceLimiter
	wg     sync.WaitGroup
	stop   chan struct{}
	close  sync.Once
	acks   sync.Map // [8]byte nonce -> chan struct{}

}

func Listen(cfg Config) (*Server, error) {
	loopback := cfg.PublicIP.IsLoopback() && cfg.OtherIP.IsLoopback()
	if !cfg.ListenIP.Is4() || cfg.ListenIP.IsUnspecified() || !cfg.PublicIP.Is4() || !cfg.OtherIP.Is4() || cfg.PublicIP == cfg.OtherIP ||
		(!loopback && (!publicIPv4(cfg.PublicIP) || !publicIPv4(cfg.OtherIP))) {
		return nil, errors.New("expected a specific IPv4 bind address and two distinct public IPv4 addresses (loopback allowed for testing)")
	}
	if cfg.Ports[0] == 0 || cfg.Ports[1] == 0 || cfg.Ports[0] == cfg.Ports[1] {
		return nil, errors.New("expected two distinct nonzero STUN ports")
	}
	if !cfg.PeerListen.Addr().Is4() || cfg.PeerListen.Addr().IsUnspecified() || !cfg.PeerRemote.Addr().Is4() || cfg.PeerRemote.Addr().IsUnspecified() ||
		cfg.PeerListen.Port() == 0 || cfg.PeerRemote.Port() == 0 || cfg.PeerListen == cfg.PeerRemote || cfg.Key == [32]byte{} {
		return nil, errors.New("expected concrete peer IPv4 UDP endpoints and a nonzero shared key")
	}
	s := &Server{cfg: cfg, stop: make(chan struct{})}
	var err error
	for i, port := range cfg.Ports {
		s.client[i], err = net.ListenUDP("udp4", net.UDPAddrFromAddrPort(netip.AddrPortFrom(cfg.ListenIP, port)))
		if err != nil {
			s.Close()
			return nil, fmt.Errorf("listen on STUN port %d: %w", port, err)
		}
	}
	s.peer, err = net.ListenUDP("udp4", net.UDPAddrFromAddrPort(cfg.PeerListen))
	if err != nil {
		s.Close()
		return nil, fmt.Errorf("listen for peer: %w", err)
	}
	s.wg.Add(3)
	for i := range cfg.Ports {
		go s.serveClients(i)
	}
	go s.servePeer()
	return s, nil
}

func (s *Server) Close() {
	s.close.Do(func() { close(s.stop) })
	for _, conn := range s.client {
		if conn != nil {
			_ = conn.Close()
		}
	}
	if s.peer != nil {
		_ = s.peer.Close()
	}
	s.wg.Wait()
}

func (s *Server) mayReply(to netip.AddrPort) bool {
	ip := to.Addr()
	return to.Port() != 0 && ip != s.cfg.PublicIP && ip != s.cfg.OtherIP &&
		(publicIPv4(ip) || (s.cfg.PublicIP.IsLoopback() && ip.IsLoopback()))
}

func (s *Server) serveClients(in int) {
	defer s.wg.Done()
	var buf [maxRequest + 1]byte
	for {
		n, from, err := s.client[in].ReadFromUDPAddrPort(buf[:])
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		req, ok := parseBinding(buf[:n])
		if !ok || !s.mayReply(from) || !s.limit.allow(from.Addr(), time.Now()) {
			continue
		}
		if req.flags&changeIP != 0 {
			s.forward(in, from, req, buf[:n])
			continue
		}
		if !s.reply(in, from, req, false) && req.flags&changePort != 0 {
			_, _ = s.client[in].WriteToUDPAddrPort(bindingUnavailable(req), from)
		}
	}
}

func (s *Server) reply(in int, client netip.AddrPort, req binding, fromPeer bool) bool {
	out := in
	if req.flags&changePort != 0 {
		out ^= 1
	}
	otherIP := s.cfg.OtherIP
	if fromPeer {
		otherIP = s.cfg.PublicIP
	}
	origin := netip.AddrPortFrom(s.cfg.PublicIP, s.cfg.Ports[out])
	other := netip.AddrPortFrom(otherIP, s.cfg.Ports[in^1])
	_, err := s.client[out].WriteToUDPAddrPort(bindingReply(req, client, origin, other), client)
	return err == nil
}

// Allow a whole household (or a shared carrier NAT) to run both suites.
// Bound the global send rate and address table even under rotating sources.
type sourceLimiter struct {
	mu     sync.Mutex
	second int64
	total  int
	perIP  map[netip.Addr]uint8
}

func (l *sourceLimiter) allow(ip netip.Addr, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if second := now.Unix(); second > l.second || l.perIP == nil {
		l.second, l.total = second, 0
		if l.perIP == nil {
			l.perIP = make(map[netip.Addr]uint8)
		} else {
			clear(l.perIP)
		}
	}
	if l.total >= maxRequestsPerSecond || l.perIP[ip] >= 20 {
		return false
	}
	l.total++
	l.perIP[ip]++
	return true
}

func decodeAddr(data []byte) netip.AddrPort {
	return netip.AddrPortFrom(netip.AddrFrom4([4]byte(data[:4])), binary.BigEndian.Uint16(data[4:6]))
}
