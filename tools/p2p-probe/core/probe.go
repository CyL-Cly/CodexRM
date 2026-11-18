package core

import (
	"context"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	mathrand "math/rand/v2"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/quic-go/quic-go"
)

type Config struct {
	Coordinator    string   `json:"coordinator"`
	Secret         string   `json:"secret"`
	Role           string   `json:"role"`
	Servers        []string `json:"servers"`
	Rounds         int      `json:"rounds"`
	Sockets        int      `json:"sockets"`
	Ports          int      `json:"ports"`
	TimeoutSeconds int      `json:"timeoutSeconds"`
	Prediction     int      `json:"prediction"`
}

type Peer struct {
	Role        string    `json:"role"`
	Round       int       `json:"round"`
	Candidates  []string  `json:"candidates"`
	Mappings    []Mapping `json:"mappings"`
	Fingerprint string    `json:"fingerprint"`
	Sockets     int       `json:"sockets"`
	Ready       bool      `json:"ready"`
	Error       string    `json:"error,omitempty"`
}

type Result struct {
	Role           string    `json:"role"`
	Round          int       `json:"round"`
	Success        bool      `json:"success"`
	Error          string    `json:"error,omitempty"`
	UDPHit         bool      `json:"udpHit"`
	QUIC           bool      `json:"quic"`
	RPC            bool      `json:"rpc"`
	Local          string    `json:"local,omitempty"`
	Remote         string    `json:"remote,omitempty"`
	ElapsedMS      int64     `json:"elapsedMs"`
	PunchMS        int64     `json:"punchMs"`
	Tx             int64     `json:"probeTx"`
	Rx             int64     `json:"probeRx"`
	Sockets        int       `json:"sockets"`
	Mappings       []Mapping `json:"mappings"`
	Path           string    `json:"path"`
	Bytes          int       `json:"echoBytes"`
	ProbedPorts    int       `json:"distinctProbedPorts"`
	HitPhase       string    `json:"hitPhase,omitempty"`
	SendErrors     int64     `json:"probeSendErrors,omitempty"`
	FirstSendError string    `json:"firstProbeSendError,omitempty"`
}

type wire struct {
	Magic string `json:"m"`
	Round int    `json:"r"`
	Role  string `json:"s"`
	Kind  string `json:"k"`
	Index int    `json:"i"`
	MAC   string `json:"h"`
}

func sign(w wire, secret string) []byte {
	w.MAC = ""
	raw, _ := json.Marshal(w)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(raw)
	w.MAC = hex.EncodeToString(mac.Sum(nil))
	b, _ := json.Marshal(w)
	return b
}

func verify(b []byte, cfg Config, round int) (wire, bool) {
	var w wire
	if json.Unmarshal(b, &w) != nil || w.Magic != "CRM-P2P1" || w.Round != round || w.Role == cfg.Role {
		return w, false
	}
	old := w.MAC
	w.MAC = ""
	raw, _ := json.Marshal(w)
	mac := hmac.New(sha256.New, []byte(cfg.Secret))
	mac.Write(raw)
	v, e := hex.DecodeString(old)
	return w, e == nil && hmac.Equal(v, mac.Sum(nil))
}

func exchange(ctx context.Context, cfg Config, path string, value any, out any) error {
	var body io.Reader
	method := "GET"
	if value != nil {
		b, _ := json.Marshal(value)
		body = strings.NewReader(string(b))
		method = "POST"
	}
	req, err := http.NewRequestWithContext(ctx, method, cfg.Coordinator+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+cfg.Secret)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("coord %s: %d", path, resp.StatusCode)
	}
	if out != nil {
		return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(out)
	}
	return nil
}

func certificate() (tls.Certificate, string, error) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	tmpl := &x509.Certificate{SerialNumber: serial, Subject: pkix.Name{CommonName: "codexrm-p2p-probe"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth}}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, key)
	h := sha256.Sum256(der)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, hex.EncodeToString(h[:]), err
}

func Run(cfg Config, log func(Result)) error {
	if cfg.Role != "agent" && cfg.Role != "phone" {
		return errors.New("invalid role")
	}
	if cfg.Rounds < 1 || cfg.Rounds > 1000 || cfg.Sockets < 1 || cfg.Sockets > 256 || cfg.Ports < 1 || cfg.Ports > 4096 || len(cfg.Secret) < 32 || len(cfg.Servers) == 0 {
		return errors.New("invalid bounded probe configuration")
	}
	cert, fp, err := certificate()
	if err != nil {
		return err
	}
	for round := 1; round <= cfg.Rounds; round++ {
		result := runRound(cfg, round, cert, fp)
		log(result)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		e := exchange(ctx, cfg, fmt.Sprintf("/done?role=%s&round=%d", cfg.Role, round), result, nil)
		cancel()
		if e != nil {
			return e
		}
	}
	return nil
}

type incoming struct {
	index int
	addr  *net.UDPAddr
	data  []byte
}

func runRound(cfg Config, round int, cert tls.Certificate, fp string) (result Result) {
	started := time.Now()
	result = Result{Role: cfg.Role, Round: round, Path: "direct-ipv4"}
	defer func() { result.ElapsedMS = time.Since(started).Milliseconds() }()
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(cfg.TimeoutSeconds)*time.Second)
	defer cancel()
	count := 1
	if cfg.Role == "phone" {
		count = cfg.Sockets
	}
	var sockets []*net.UDPConn
	defer func() {
		for _, c := range sockets {
			c.Close()
		}
	}()
	for i := 0; i < count; i++ {
		c, e := net.ListenUDP("udp4", &net.UDPAddr{})
		if e != nil {
			result.Error = "socket: " + e.Error()
			return
		}
		c.SetReadBuffer(1 << 20)
		sockets = append(sockets, c)
	}
	result.Sockets = len(sockets)
	result.Mappings = diagnoseSocket(sockets[0], cfg.Servers)
	candidates := []string{}
	seen := map[string]bool{}
	for _, m := range result.Mappings {
		if m.Mapped != "" && !seen[m.Mapped] {
			candidates = append(candidates, m.Mapped)
			seen[m.Mapped] = true
		}
	}
	own := Peer{Role: cfg.Role, Round: round, Candidates: candidates, Mappings: result.Mappings, Fingerprint: fp, Sockets: count}
	if len(candidates) == 0 {
		own.Error = "all STUN requests failed"
	}
	if e := exchange(ctx, cfg, fmt.Sprintf("/publish?role=%s&round=%d", cfg.Role, round), own, nil); e != nil {
		result.Error = e.Error()
		return
	}
	var peer Peer
	if e := exchange(ctx, cfg, fmt.Sprintf("/peer?role=%s&round=%d", cfg.Role, round), nil, &peer); e != nil {
		result.Error = e.Error()
		return
	}
	if own.Error != "" || peer.Error != "" {
		result.Error = own.Error + " " + peer.Error
		return
	}
	punchStarted := time.Now()
	stopRead := make(chan struct{})
	events := make(chan incoming, 2048)
	var readers sync.WaitGroup
	for i, c := range sockets {
		readers.Add(1)
		go func(i int, c *net.UDPConn) {
			defer readers.Done()
			buf := make([]byte, 2048)
			for {
				c.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
				n, addr, e := c.ReadFromUDP(buf)
				select {
				case <-stopRead:
					return
				default:
				}
				if e != nil {
					continue
				}
				b := append([]byte(nil), buf[:n]...)
				select {
				case events <- incoming{i, addr, b}:
				default:
				}
			}
		}(i, c)
	}
	readStopped := false
	stop := func() {
		if !readStopped {
			close(stopRead)
			readers.Wait()
			readStopped = true
			for _, c := range sockets {
				c.SetReadDeadline(time.Time{})
			}
		}
	}
	defer stop()
	var tx, rx atomic.Int64
	var sendErrors atomic.Int64
	var firstSendError atomic.Pointer[string]
	defer func() {
		result.Tx = tx.Load()
		result.Rx = rx.Load()
		result.SendErrors = sendErrors.Load()
		if message := firstSendError.Load(); message != nil {
			result.FirstSendError = *message
		}
	}()
	send := func(i int, to *net.UDPAddr, kind string) {
		b := sign(wire{Magic: "CRM-P2P1", Round: round, Role: cfg.Role, Kind: kind, Index: i}, cfg.Secret)
		if _, e := sockets[i].WriteToUDP(b, to); e == nil {
			tx.Add(1)
		} else {
			sendErrors.Add(1)
			message := e.Error()
			firstSendError.CompareAndSwap(nil, &message)
		}
	}
	var selected *net.UDPAddr
	chosen := -1
	// Agent keeps one source endpoint. Phone keeps ALL mappings aimed at it.
	var targets []*net.UDPAddr
	for _, s := range peer.Candidates {
		a, e := net.ResolveUDPAddr("udp4", s)
		if e == nil {
			targets = append(targets, a)
		}
	}
	if len(targets) == 0 {
		result.Error = "no peer candidate"
		return
	}
	var scan []*net.UDPAddr
	phases := map[string]string{}
	if cfg.Role == "agent" {
		phase := "candidate"
		portSeen := map[string]bool{}
		add := func(a *net.UDPAddr) {
			if len(scan) < cfg.Ports && !portSeen[a.String()] {
				scan = append(scan, a)
				portSeen[a.String()] = true
				phases[a.String()] = phase
			}
		}
		for _, a := range targets {
			add(a)
		}
		phase = "prediction"
		for delta := 1; delta <= cfg.Prediction; delta++ {
			for _, a := range targets {
				for _, d := range []int{delta, -delta} {
					p := a.Port + d
					if p > 0 && p < 65536 {
						add(&net.UDPAddr{IP: a.IP, Port: p})
					}
				}
			}
		}
		ips := []net.IP{}
		seenIP := map[string]bool{}
		for _, a := range targets {
			if !seenIP[a.IP.String()] {
				ips = append(ips, a.IP)
				seenIP[a.IP.String()] = true
			}
		}
		phase = "random"
		perm := mathrand.Perm(65535)
		for _, p := range perm {
			for _, ip := range ips {
				if len(scan) >= cfg.Ports {
					break
				}
				add(&net.UDPAddr{IP: ip, Port: p + 1})
			}
			if len(scan) >= cfg.Ports {
				break
			}
		}
	}
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	cursor := 0
	phoneIndex := 0
	var phoneNext time.Time
	var lastSelected time.Time
	punchDone := false
	for !punchDone {
		select {
		case <-ctx.Done():
			result.Error = "punch timeout"
			result.Tx = tx.Load()
			result.Rx = rx.Load()
			result.ProbedPorts = min(cursor, len(scan))
			return
		case event := <-events:
			w, ok := verify(event.data, cfg, round)
			if !ok {
				continue
			}
			rx.Add(1)
			if cfg.Role == "agent" {
				if selected == nil && w.Kind == "probe" {
					selected = event.addr
					chosen = 0
				}
				if selected != nil && selected.String() == event.addr.String() {
					if w.Kind == "confirm" {
						punchDone = true
					} else {
						send(0, selected, "selected")
					}
				}
			} else if w.Kind == "selected" {
				selected = event.addr
				chosen = event.index
				send(chosen, selected, "confirm")
				punchDone = true
			}
		case now := <-tick.C:
			if cfg.Role == "agent" {
				if selected != nil {
					if now.Sub(lastSelected) > 100*time.Millisecond {
						send(0, selected, "selected")
						lastSelected = now
					}
				} else {
					send(0, scan[cursor%len(scan)], "probe")
					cursor++
				}
			}
			if cfg.Role == "phone" && (phoneNext.IsZero() || !now.Before(phoneNext)) {
				for _, a := range targets {
					send(phoneIndex, a, "probe")
				}
				phoneIndex++
				if phoneIndex == len(sockets) {
					phoneIndex = 0
					phoneNext = now.Add(time.Second)
				} else {
					phoneNext = now
				}
			}
		}
	}
	result.UDPHit = true
	result.PunchMS = time.Since(punchStarted).Milliseconds()
	result.Tx = tx.Load()
	result.Rx = rx.Load()
	result.Local = sockets[chosen].LocalAddr().String()
	result.Remote = selected.String()
	result.ProbedPorts = min(cursor, len(scan))
	result.HitPhase = phases[selected.String()]
	stop()
	for i, c := range sockets {
		if i != chosen {
			c.Close()
		}
	}
	own.Ready = true
	// Confirm can be lost after selection. Keep it alive until the agent has
	// acknowledged selection through the authenticated signaling barrier.
	confirmsDone := make(chan struct{})
	var confirmWG sync.WaitGroup
	if cfg.Role == "phone" {
		confirmWG.Add(1)
		go func() {
			defer confirmWG.Done()
			t := time.NewTicker(100 * time.Millisecond)
			defer t.Stop()
			for {
				select {
				case <-confirmsDone:
					return
				case <-ctx.Done():
					return
				case <-t.C:
					send(chosen, selected, "confirm")
				}
			}
		}()
	}
	var confirmsOnce sync.Once
	stopConfirms := func() { confirmsOnce.Do(func() { close(confirmsDone); confirmWG.Wait() }) }
	defer stopConfirms()
	if e := exchange(ctx, cfg, fmt.Sprintf("/ready?role=%s&round=%d", cfg.Role, round), own, nil); e != nil {
		result.Error = e.Error()
		return
	}
	if e := exchange(ctx, cfg, fmt.Sprintf("/wait-ready?role=%s&round=%d", cfg.Role, round), nil, &peer); e != nil {
		result.Error = e.Error()
		return
	}
	stopConfirms()
	tlsCfg := &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{cert}, NextProtos: []string{"codexrm-p2p-probe/1"}, InsecureSkipVerify: true, ClientAuth: tls.RequireAnyClientCert, VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
		if len(raw) != 1 {
			return errors.New("peer certificate missing")
		}
		h := sha256.Sum256(raw[0])
		if hex.EncodeToString(h[:]) != peer.Fingerprint {
			return errors.New("peer certificate pin mismatch")
		}
		return nil
	}}
	transport := &quic.Transport{Conn: sockets[chosen]}
	defer transport.Close()
	qc := &quic.Config{HandshakeIdleTimeout: 3 * time.Second, MaxIdleTimeout: 5 * time.Second, KeepAlivePeriod: time.Second}
	var conn *quic.Conn
	var err error
	if cfg.Role == "agent" {
		listener, e := transport.Listen(tlsCfg, qc)
		if e != nil {
			result.Error = e.Error()
			return
		}
		defer listener.Close()
		conn, err = listener.Accept(ctx)
	} else {
		conn, err = transport.Dial(ctx, selected, tlsCfg, qc)
	}
	if err != nil {
		result.Error = "QUIC: " + err.Error()
		return
	}
	defer conn.CloseWithError(0, "")
	result.QUIC = true
	result.Remote = conn.RemoteAddr().String()
	if err = rpc(ctx, conn, cfg.Role); err != nil {
		result.Error = "RPC: " + err.Error()
		return
	}
	if err = exchange(ctx, cfg, fmt.Sprintf("/barrier?role=%s&round=%d", cfg.Role, round), map[string]bool{"done": true}, nil); err != nil {
		result.Error = err.Error()
		return
	}
	result.RPC = true
	result.Success = true
	result.Bytes = 32768
	return
}

type rpcMessage struct {
	Method  string `json:"method"`
	Nonce   string `json:"nonce"`
	Payload []byte `json:"payload,omitempty"`
	Hash    string `json:"sha256,omitempty"`
}

func rpc(ctx context.Context, c *quic.Conn, role string) error {
	var stream *quic.Stream
	var err error
	if role == "phone" {
		stream, err = c.OpenStreamSync(ctx)
	} else {
		stream, err = c.AcceptStream(ctx)
	}
	if err != nil {
		return err
	}
	defer stream.Close()
	deadline, _ := ctx.Deadline()
	stream.SetDeadline(deadline)
	enc := json.NewEncoder(stream)
	dec := json.NewDecoder(io.LimitReader(stream, 256<<10))
	if role == "phone" {
		payload := make([]byte, 32768)
		rand.Read(payload)
		nonce := make([]byte, 16)
		rand.Read(nonce)
		h := sha256.Sum256(payload)
		request := rpcMessage{Method: "echo", Nonce: hex.EncodeToString(nonce), Payload: payload, Hash: hex.EncodeToString(h[:])}
		if err = enc.Encode(request); err != nil {
			return err
		}
		var response rpcMessage
		if err = dec.Decode(&response); err != nil {
			return err
		}
		if response.Method != "echoResult" || response.Nonce != request.Nonce || response.Hash != request.Hash {
			return errors.New("echo mismatch")
		}
		if err = dec.Decode(&response); err != nil {
			return err
		}
		if response.Method != "reverseEcho" {
			return errors.New("missing reverse RPC")
		}
		response.Method = "reverseResult"
		if err = enc.Encode(response); err != nil {
			return err
		}
		if err = dec.Decode(&response); err != nil {
			return err
		}
		if response.Method != "complete" {
			return errors.New("missing completion")
		}
		return enc.Encode(rpcMessage{Method: "ack"})
	}
	var request rpcMessage
	if err = dec.Decode(&request); err != nil {
		return err
	}
	h := sha256.Sum256(request.Payload)
	if request.Method != "echo" || len(request.Payload) != 32768 || request.Hash != hex.EncodeToString(h[:]) {
		return errors.New("payload mismatch")
	}
	request.Payload = nil
	request.Method = "echoResult"
	if err = enc.Encode(request); err != nil {
		return err
	}
	request.Method = "reverseEcho"
	if err = enc.Encode(request); err != nil {
		return err
	}
	var response rpcMessage
	if err = dec.Decode(&response); err != nil {
		return err
	}
	if response.Method != "reverseResult" || response.Nonce != request.Nonce {
		return errors.New("reverse mismatch")
	}
	if err = enc.Encode(rpcMessage{Method: "complete"}); err != nil {
		return err
	}
	if err = dec.Decode(&response); err != nil {
		return err
	}
	if response.Method != "ack" {
		return errors.New("missing ack")
	}
	return nil
}

// RunJSON is the deliberately small Android JNI boundary.
func RunJSON(config string) string {
	var cfg Config
	if err := json.Unmarshal([]byte(config), &cfg); err != nil {
		return err.Error()
	}
	var results []Result
	err := Run(cfg, func(r Result) { results = append(results, r) })
	out := map[string]any{"results": results}
	if err != nil {
		out["error"] = err.Error()
	}
	b, _ := json.Marshal(out)
	return string(b)
}
