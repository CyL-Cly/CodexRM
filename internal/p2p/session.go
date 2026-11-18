package p2p

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/netip"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

var (
	ErrNoPeerCandidate = errors.New("no verified peer candidate")
	ErrUDPPunch        = errors.New("UDP punch")
)

type Session struct {
	agent   bool
	id      string
	key     []byte
	cert    tls.Certificate
	sockets []*net.UDPConn
	lan     []netip.Prefix
	mapping string
}

func Prepare(ctx context.Context, agent bool, start protocol.P2PSignal) (*Session, *protocol.P2PSignal, error) {
	var local []netip.Prefix
	if start.LAN {
		local = lanInterfaces()
	}
	return PrepareWithLocalPrefixes(ctx, agent, start, local)
}

func PrepareWithLocalPrefixes(ctx context.Context, agent bool, start protocol.P2PSignal, local []netip.Prefix) (*Session, *protocol.P2PSignal, error) {
	key, err := hex.DecodeString(start.Secret)
	if err != nil || len(key) != 32 || len(start.ID) != 36 || len(start.STUN) > 4 {
		return nil, nil, errors.New("invalid direct session")
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	cert := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature}
	der, err := x509.CreateCertificate(rand.Reader, cert, cert, pub, priv)
	if err != nil {
		return nil, nil, err
	}
	s := &Session{agent: agent, id: start.ID, key: key, cert: tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv}}
	if start.LAN {
		for _, p := range local {
			if IsLANIPv4(p.Addr()) && p.Bits() > 0 && p.Bits() < 32 {
				s.lan = append(s.lan, p)
			}
		}
	}
	var listenErr error
	for i := 0; i < 3 && ctx.Err() == nil; i++ {
		if _, listenErr = s.openSocket(); listenErr == nil {
			break
		}
		if i == 2 {
			break
		}
		timer := time.NewTimer(30 * time.Millisecond)
		select {
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()
		}
	}
	if len(s.sockets) == 0 {
		return nil, nil, fmt.Errorf("cannot allocate UDP socket: %w", errors.Join(ctx.Err(), listenErr))
	}
	fp := sha256.Sum256(der)
	offer := protocol.P2P("offer", start.ID)
	offer.Pin = hex.EncodeToString(fp[:])
	add := func(a netip.AddrPort) {
		if !a.IsValid() || len(offer.Candidates) >= protocol.P2PMaxCandidates {
			return
		}
		for _, old := range offer.Candidates {
			if old == a.String() {
				return
			}
		}
		offer.Candidates = append(offer.Candidates, a.String())
	}
	queries := discover(ctx, s.sockets[0], start)
	s.mapping, offer.Step = classifyMapping(queries)
	offer.Mapping = s.mapping
	for _, q := range queries {
		add(q.mapped)
		if !q.verifier || q.proof == "" {
			continue
		}
		if offer.Candidates[0] == q.mapped.String() {
			offer.Proof = q.proof
		}
		if len(start.VerifyMore) > 0 {
			if offer.Proofs == nil {
				offer.Proofs = make(map[string]string)
			}
			offer.Proofs[q.mapped.String()] = q.proof
		}
	}
	port := s.sockets[0].LocalAddr().(*net.UDPAddr).AddrPort().Port()
	for _, p := range s.lan {
		if len(offer.Local) >= protocol.P2PMaxCandidates {
			break
		}
		offer.Local = append(offer.Local, netip.AddrPortFrom(p.Addr(), port).String())
	}
	if addrs, e := net.InterfaceAddrs(); e == nil && (!start.LAN || len(start.STUN) > 0) {
		for _, a := range addrs {
			p, e := netip.ParsePrefix(a.String())
			if e == nil && p.Addr().Is4() {
				add(netip.AddrPortFrom(p.Addr(), port))
			}
		}
	}
	if ctx.Err() != nil || (len(offer.Candidates) == 0 && len(offer.Local) == 0) {
		s.Close()
		return nil, nil, errors.New("no UDP candidates")
	}
	return s, offer, nil
}

func (s *Session) openSocket() (*net.UDPConn, error) {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{})
	if err == nil {
		s.sockets = append(s.sockets, c)
	}
	return c, err
}

func (s *Session) Close() {
	for _, c := range s.sockets {
		if c != nil {
			_ = c.Close()
		}
	}
}

func (s *Session) packet(role, kind byte) []byte {
	data := []byte{'C', 'R', 'M', 1, role, kind}
	mac := hmac.New(sha256.New, s.key)
	mac.Write([]byte(s.id))
	mac.Write(data)
	return mac.Sum(data)
}

// Connect keeps the winning UDP socket across the authenticated probe and TLS handshake.
// ready is the peer's authenticated RM signal. The Agent listens before sending its own.
func (s *Session) Connect(ctx context.Context, offer protocol.P2PSignal, ready <-chan struct{}, signalReady func() error) (*Link, error) {
	pin, err := hex.DecodeString(offer.Pin)
	if err != nil || len(pin) != 32 || offer.ID != s.id || len(offer.Candidates) > protocol.P2PMaxCandidates || len(offer.Local) > protocol.P2PMaxCandidates {
		return nil, errors.New("invalid peer offer")
	}
	var targets []netip.AddrPort
	for _, v := range offer.Candidates {
		a, e := netip.ParseAddrPort(v)
		if e == nil && a.Addr().Is4() && a.Port() != 0 && (a.Addr().IsGlobalUnicast() || a.Addr().IsLoopback()) {
			targets = append(targets, a)
		}
	}
	var local []netip.AddrPort
	for _, v := range offer.Local {
		if a, err := netip.ParseAddrPort(v); err == nil && a.Port() != 0 && onLinkIPv4(a.Addr(), s.lan) {
			local = append(local, a)
		}
	}
	if len(targets) == 0 && len(local) == 0 {
		return nil, ErrNoPeerCandidate
	}
	remoteMapping, remoteStep := protocol.NormalizeMapping(offer.Mapping, offer.Step)
	i, addr, err := s.punch(ctx, targets, local, exactWindow(s.mapping, remoteMapping), remoteStep)
	if err != nil {
		return nil, err
	}
	c := s.sockets[i]
	for n, other := range s.sockets {
		if n != i {
			_ = other.Close()
		}
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{s.cert}, NextProtos: []string{"codexrm-direct/1"}, InsecureSkipVerify: true, ClientAuth: tls.RequireAnyClientCert, VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
		if len(raw) == 1 {
			sum := sha256.Sum256(raw[0])
			if bytes.Equal(sum[:], pin) {
				return nil
			}
		}
		return errors.New("direct peer certificate mismatch")
	}}
	tr := &quic.Transport{Conn: c}
	qc := &quic.Config{HandshakeIdleTimeout: 3 * time.Second, MaxIdleTimeout: 8 * time.Second, KeepAlivePeriod: 2 * time.Second, MaxIncomingStreams: 1, MaxIncomingUniStreams: -1}
	link := &Link{trans: tr, udp: c}
	for _, a := range local {
		if a.Addr() == addr.Addr() {
			link.lan = true
			break
		}
	}
	success := false
	defer func() {
		if !success {
			if link.conn != nil {
				link.Close()
			} else {
				_ = tr.Close()
			}
		}
	}()
	if s.agent {
		ln, e := tr.Listen(tlsConfig, qc)
		if e != nil {
			return nil, e
		}
		defer ln.Close()
		if e = signalReady(); e != nil {
			return nil, e
		}
		link.conn, err = ln.Accept(ctx)
	} else {
		if err = signalReady(); err != nil {
			return nil, err
		}
		tick := time.NewTicker(100 * time.Millisecond)
		defer tick.Stop()
		confirm := s.packet(1, punchConfirm)
	readyLoop:
		for {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-ready:
				break readyLoop
			case <-tick.C:
				_, _ = c.WriteToUDPAddrPort(confirm, addr)
			}
		}
		link.conn, err = tr.Dial(ctx, net.UDPAddrFromAddrPort(addr), tlsConfig, qc)
	}
	if err != nil {
		return nil, fmt.Errorf("QUIC: %w", err)
	}
	if s.agent {
		link.stream, err = link.conn.AcceptStream(ctx)
	} else {
		link.stream, err = link.conn.OpenStreamSync(ctx)
	}
	if err != nil {
		return nil, err
	}
	_ = link.stream.SetReadDeadline(time.Now().Add(3 * time.Second))
	const marker = "codexrm-direct/1"
	if !s.agent {
		if err := link.Write([]byte(marker)); err != nil {
			return nil, err
		}
	}
	buf, err := link.Read()
	if err != nil || string(buf) != marker {
		return nil, errors.New("direct stream handshake failed")
	}
	if s.agent {
		if err := link.Write([]byte(marker)); err != nil {
			return nil, err
		}
	}
	_ = link.stream.SetReadDeadline(time.Time{})
	s.sockets[i] = nil // ownership transferred
	success = true
	return link, nil
}
