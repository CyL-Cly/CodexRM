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
	"slices"
	"sync"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/quic-go/quic-go"
)

var (
	ErrNoPeerCandidate      = errors.New("no verified peer candidate")
	ErrUDPPunch             = errors.New("UDP punch")
	ErrDualUnpredictableNAT = errors.New("both peers have unpredictable UDP mappings and no working direct path")
)

type Session struct {
	agent        bool
	id           string
	key          []byte
	cert         tls.Certificate
	sockets      []*net.UDPConn
	pathSockets  int // wildcard socket plus routable, locally bound alternatives
	lan          []netip.Prefix
	paths        []directPath
	mapping      string
	dualNATPunch bool
	ipv6         bool

	clientInitiated bool
}

func Prepare(ctx context.Context, agent bool, start protocol.P2PSignal) (*Session, *protocol.P2PSignal, error) {
	return PrepareWithSources(ctx, agent, start, sourceInterfaces())
}

func PrepareWithLocalPrefixes(ctx context.Context, agent bool, start protocol.P2PSignal, local []netip.Prefix) (*Session, *protocol.P2PSignal, error) {
	var sources []Source
	for _, p := range local {
		sources = append(sources, Source{Prefix: p, LAN: true})
	}
	if len(sources) == 0 && !start.LAN {
		return Prepare(ctx, agent, start)
	}
	return PrepareWithSources(ctx, agent, start, sources)
}

func PrepareWithSources(ctx context.Context, agent bool, start protocol.P2PSignal, sources []Source) (*Session, *protocol.P2PSignal, error) {
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
	s.dualNATPunch, s.ipv6 = start.DualNATPunch, start.IPv6
	s.clientInitiated = start.ClientInitiated
	if start.LAN {
		for _, src := range sources {
			p := src.Prefix
			if src.LAN && IsLANIPv4(p.Addr()) && p.Bits() > 0 && p.Bits() < 32 {
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
	paths := s.discoverPaths(ctx, start, sources)
	s.mapping, offer.Step = classifyPathMappings(paths)
	offer.Mapping = s.mapping
	ranked := s.rankedPaths()
	hints := make(map[string]protocol.P2PMappingHint)
	for _, i := range ranked {
		for _, q := range paths[i] {
			if q.mapped.Addr().Is4() {
				raw := q.mapped.String()
				hint := s.paths[i].hint
				if prev, exists := hints[raw]; exists && prev != hint {
					hint = protocol.P2PMappingHint{}
				}
				hints[raw] = hint
			}
		}
	}
	// Keep a verifier-backed candidate first for old RMs. Give distinct egress
	// IPs priority over additional ports from IPs already offered.
	proofs := make(map[string]string)
	observed := make(map[netip.Addr]bool)
	for _, distinct := range []bool{true, false} {
		for _, verified := range []bool{true, false} {
			for _, i := range ranked {
				for _, q := range paths[i] {
					if !q.mapped.IsValid() || (q.proof != "") != verified {
						continue
					}
					if verified {
						proofs[q.mapped.String()] = q.proof
					}
					if distinct && observed[q.mapped.Addr()] {
						continue
					}
					add(q.mapped)
					observed[q.mapped.Addr()] = true
				}
			}
		}
	}
	for i, raw := range offer.Candidates {
		if proof := proofs[raw]; proof != "" {
			if i == 0 {
				offer.Proof = proof
			}
			if start.MultiPath || len(start.VerifyMore) > 0 {
				if offer.Proofs == nil {
					offer.Proofs = make(map[string]string)
				}
				offer.Proofs[raw] = proof
			}
		}
		if hint := hints[raw]; start.MultiPath && hint.Mapping != "" {
			if offer.PathMappings == nil {
				offer.PathMappings = make(map[string]protocol.P2PMappingHint)
			}
			offer.PathMappings[raw] = hint
		}
	}
	// Keep the wildcard's LAN offer for compatibility and include the exact
	// bound socket ports, one address per route before duplicate addresses.
	addLAN := func(p netip.Prefix, port uint16) {
		raw := netip.AddrPortFrom(p.Addr(), port).String()
		if len(offer.Local) < protocol.P2PMaxCandidates && !slices.Contains(offer.Local, raw) {
			offer.Local = append(offer.Local, raw)
		}
	}
	for _, i := range ranked {
		if i == 0 {
			continue
		}
		src := s.paths[i].source
		addr := s.sockets[i].LocalAddr().(*net.UDPAddr).AddrPort()
		if start.LAN && src.LAN && IsLANIPv4(addr.Addr()) && src.Prefix.Bits() > 0 && src.Prefix.Bits() < 32 {
			addLAN(src.Prefix, addr.Port())
		}
		if (!start.LAN || len(start.STUN) > 0) && (IsPublicIPv6(addr.Addr()) || canExpand(addr.Addr())) {
			add(addr)
		}
	}
	port := s.sockets[0].LocalAddr().(*net.UDPAddr).AddrPort().Port()
	// Local RM deployments may configure a loopback STUN endpoint and also
	// rely on on-host connectivity when STUN itself is unavailable.
	for _, endpoint := range start.STUN {
		if dst, err := netip.ParseAddrPort(endpoint); err == nil && dst.Addr().IsLoopback() && supportsFamily(s.sockets[0], dst.Addr().Is6()) {
			add(netip.AddrPortFrom(dst.Addr(), port))
		}
	}
	for _, p := range s.lan {
		addLAN(p, port)
	}
	if !start.LAN || len(start.STUN) > 0 {
		for _, src := range orderSources(sources, s.ipv6) {
			ip := src.Prefix.Addr()
			if ip.Is6() || ip.Is4() {
				add(netip.AddrPortFrom(ip, port))
			}
		}
	}
	if ctx.Err() != nil || (len(offer.Candidates) == 0 && len(offer.Local) == 0 && (s.agent || !s.clientInitiated)) {
		s.Close()
		return nil, nil, errors.New("no UDP candidates")
	}
	return s, offer, nil
}

type directPath struct {
	source Source
	hint   protocol.P2PMappingHint
	rtt    time.Duration
}

func (s *Session) rankedPaths() []int {
	order := make([]int, max(1, s.pathSockets))
	for i := range order {
		order[i] = i
	}
	if len(s.paths) == len(order) {
		slices.SortStableFunc(order, func(i, j int) int {
			a, b := s.paths[i].rtt, s.paths[j].rtt
			switch {
			case a == b:
				return 0
			case a == 0 || (b != 0 && a > b):
				return 1
			default:
				return -1
			}
		})
	}
	return order
}

// Keep each socket from discovery through punching and QUIC. A failed STUN
// probe does not eliminate directly routable LAN or public IPv6 host addresses.
func (s *Session) discoverPaths(ctx context.Context, start protocol.P2PSignal, sources []Source) [][]discoveryQuery {
	s.paths = []directPath{{}}
	if start.MultiPath {
		for _, src := range orderSources(sources, s.ipv6) {
			if len(s.sockets) >= maxDirectPaths || ctx.Err() != nil {
				break
			}
			if c, err := listenSource(ctx, src); err == nil {
				s.sockets = append(s.sockets, c)
				s.paths = append(s.paths, directPath{source: src})
			}
		}
	}
	paths := make([][]discoveryQuery, len(s.sockets))
	var wg sync.WaitGroup
	for i, c := range s.sockets {
		wg.Add(1)
		go func() { defer wg.Done(); paths[i] = discover(ctx, c, start) }()
	}
	wg.Wait()
	keep := 0
	for i, queries := range paths {
		path := s.paths[i]
		ip := path.source.Prefix.Addr()
		var rtts []time.Duration
		for _, q := range queries {
			if q.mapped.IsValid() {
				rtts = append(rtts, q.rtt)
			}
		}
		if len(rtts) == 0 && i != 0 && !IsPublicIPv6(ip) && !canExpand(ip) &&
			!(start.LAN && path.source.LAN && IsLANIPv4(ip)) {
			_ = s.sockets[i].Close()
			continue
		}
		if len(rtts) > 0 {
			slices.Sort(rtts)
			path.rtt = rtts[len(rtts)/2]
		}
		path.hint.Mapping, path.hint.Step = classifyMapping(queries)
		paths[keep], s.sockets[keep], s.paths[keep] = queries, s.sockets[i], path
		keep++
	}
	s.sockets, s.paths, s.pathSockets = s.sockets[:keep], s.paths[:keep], keep
	return paths[:keep]
}

func (s *Session) openSocket() (*net.UDPConn, error) {
	if len(s.sockets) == 0 && s.ipv6 {
		// Use a dual-stack primary socket so either family can win without
		// reopening the selected socket for QUIC. Extra NAT sockets stay IPv4.
		if c, err := net.ListenUDP("udp", &net.UDPAddr{}); err == nil {
			s.ipv6 = c.LocalAddr().(*net.UDPAddr).AddrPort().Addr().Is6()
			s.sockets = append(s.sockets, c)
			return c, nil
		}
		s.ipv6 = false
	}
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
		if e == nil && a.Port() != 0 &&
			((a.Addr().Is4() && (a.Addr().IsGlobalUnicast() || a.Addr().IsLoopback())) ||
				(s.ipv6 && (IsPublicIPv6(a.Addr()) || a.Addr() == netip.IPv6Loopback()))) {
			targets = append(targets, a)
		}
	}
	var local []netip.AddrPort
	for _, v := range offer.Local {
		if a, err := netip.ParseAddrPort(v); err == nil && a.Port() != 0 && onLinkIPv4(a.Addr(), s.lan) {
			local = append(local, a)
		}
	}
	// Only the Agent can wait for a probe without a verified peer candidate.
	passive := s.agent && s.clientInitiated && len(offer.Candidates) == 0 && len(offer.Local) == 0
	if len(targets) == 0 && len(local) == 0 && !passive {
		return nil, ErrNoPeerCandidate
	}
	remoteMapping, remoteStep := protocol.NormalizeMapping(offer.Mapping, offer.Step)
	hints := make(map[netip.AddrPort]protocol.P2PMappingHint, len(targets))
	dualUnpredictable := s.mapping == protocol.P2PMappingVaries && len(targets) > 0
	for _, a := range targets {
		hint := protocol.P2PMappingHint{Mapping: remoteMapping, Step: remoteStep}
		if len(offer.PathMappings) > 0 {
			hint = offer.PathMappings[a.String()]
			hint.Mapping, hint.Step = protocol.NormalizeMapping(hint.Mapping, hint.Step)
		}
		hints[a] = hint
		dualUnpredictable = dualUnpredictable && a.Addr().Is4() && hint.Mapping == protocol.P2PMappingVaries
	}
	publicDualPunch := dualUnpredictable && s.dualNATPunch && len(targets) > 0
	punchCtx := ctx
	if dualUnpredictable && !passive {
		limit := dualNATPunchTimeout
		if !publicDualPunch {
			if len(local) == 0 {
				return nil, ErrDualUnpredictableNAT
			}
			targets, hints = nil, nil
			limit = 3 * time.Second
		}
		var cancel context.CancelFunc
		punchCtx, cancel = context.WithTimeout(ctx, limit)
		defer cancel()
	}
	i, addr, err := s.punchPaths(punchCtx, targets, local, hints, 0, publicDualPunch)
	if err != nil {
		if dualUnpredictable && ctx.Err() == nil && errors.Is(err, context.DeadlineExceeded) {
			return nil, ErrDualUnpredictableNAT
		}
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
	if s.agent {
		// One message stream and one Android-to-desktop file stream.
		qc.MaxIncomingStreams = 2
	}
	link := &Link{trans: tr, udp: c, ipv6: addr.Addr().Is6()}
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
