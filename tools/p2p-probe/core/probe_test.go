package core

import (
	"encoding/binary"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

func localSTUN(t *testing.T) string {
	t.Helper()
	a, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	b, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		a.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { a.Close(); b.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, remote, e := a.ReadFromUDP(buf)
			if e != nil {
				return
			}
			if n < 20 {
				continue
			}
			response := make([]byte, 32)
			copy(response, buf[:20])
			binary.BigEndian.PutUint16(response, 0x101)
			binary.BigEndian.PutUint16(response[2:], 12)
			binary.BigEndian.PutUint16(response[20:], 0x20)
			binary.BigEndian.PutUint16(response[22:], 8)
			response[25] = 1
			binary.BigEndian.PutUint16(response[26:], uint16(remote.Port)^0x2112)
			ip := remote.IP.To4()
			for i := 0; i < 4; i++ {
				response[28+i] = ip[i] ^ response[4+i]
			}
			out := a
			if n == 28 && buf[27]&2 != 0 {
				out = b
			}
			out.WriteToUDP(response, remote)
		}
	}()
	return a.LocalAddr().String()
}

func TestAuthenticatedProbeRejectsTamperReplayAndOwnRole(t *testing.T) {
	sender := Config{Role: "phone", Secret: strings.Repeat("a", 64)}
	receiver := sender
	receiver.Role = "agent"
	w := wire{Magic: "CRM-P2P1", Round: 7, Role: "phone", Kind: "probe", Index: 4}
	packet := sign(w, sender.Secret)
	if _, ok := verify(packet, receiver, 7); !ok {
		t.Fatal("valid probe rejected")
	}
	if _, ok := verify(packet, receiver, 8); ok {
		t.Fatal("different generation accepted")
	}
	if _, ok := verify(packet, sender, 7); ok {
		t.Fatal("self-role probe accepted")
	}
	receiver.Secret = strings.Repeat("b", 64)
	if _, ok := verify(packet, receiver, 7); ok {
		t.Fatal("wrong key accepted")
	}
	receiver.Secret = sender.Secret
	tampered := strings.Replace(string(packet), "probe", "confirm", 1)
	if _, ok := verify([]byte(tampered), receiver, 7); ok {
		t.Fatal("modified kind accepted")
	}
}

func TestSTUNAlternatePortReplyIsObserved(t *testing.T) {
	server := localSTUN(t)
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	normal := stun(c, server, false)
	changed := stun(c, server, true)
	if normal.Error != "" || changed.Error != "" {
		t.Fatalf("normal=%+v changed=%+v", normal, changed)
	}
	if normal.Mapped != c.LocalAddr().String() || normal.Mapped != changed.Mapped {
		t.Fatalf("wrong observed endpoint: %+v %+v", normal, changed)
	}
	if changed.Responder == normal.Responder {
		t.Fatal("alternate port not observed")
	}
}

func TestRealSocketHandoffMutualTLSAndBidirectionalRPC(t *testing.T) {
	server := localSTUN(t)
	var mu sync.Mutex
	peers := map[string]Peer{}
	ready := map[string]Peer{}
	barrier := map[string]bool{}
	coord := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		role := r.URL.Query().Get("role")
		other := "phone"
		if role == "phone" {
			other = "agent"
		}
		if r.Header.Get("Authorization") != "Bearer "+strings.Repeat("a", 64) {
			http.Error(w, "auth", 401)
			return
		}
		mu.Lock()
		switch r.URL.Path {
		case "/publish", "/ready":
			var p Peer
			if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
				mu.Unlock()
				http.Error(w, "bad peer", 400)
				return
			}
			if r.URL.Path == "/publish" {
				peers[role] = p
			} else {
				ready[role] = p
			}
			mu.Unlock()
			w.Write([]byte("{}"))
			return
		case "/barrier":
			barrier[role] = true
		}
		mu.Unlock()
		for {
			mu.Lock()
			var value any
			found := false
			switch r.URL.Path {
			case "/peer":
				value, found = peers[other]
			case "/wait-ready":
				value, found = ready[other]
			case "/barrier":
				value = map[string]bool{"done": true}
				found = barrier[other]
			}
			mu.Unlock()
			if found {
				json.NewEncoder(w).Encode(value)
				return
			}
			select {
			case <-r.Context().Done():
				return
			case <-time.After(time.Millisecond):
			}
		}
	}))
	defer coord.Close()
	cfg := Config{Coordinator: coord.URL, Secret: strings.Repeat("a", 64), Role: "agent", Servers: []string{server}, Rounds: 1, Sockets: 3, Ports: 8, TimeoutSeconds: 10}
	agentCert, agentFP, err := certificate()
	if err != nil {
		t.Fatal(err)
	}
	phoneCert, phoneFP, err := certificate()
	if err != nil {
		t.Fatal(err)
	}
	results := make(chan Result, 2)
	go func() { results <- runRound(cfg, 1, agentCert, agentFP) }()
	phone := cfg
	phone.Role = "phone"
	go func() { results <- runRound(phone, 1, phoneCert, phoneFP) }()
	for i := 0; i < 2; i++ {
		result := <-results
		if !result.Success || !result.UDPHit || !result.QUIC || !result.RPC || result.Bytes != 32768 {
			t.Fatalf("failed actual UDP/QUIC/RPC handoff: %+v", result)
		}
		remote, e := net.ResolveUDPAddr("udp4", result.Remote)
		if e != nil || !remote.IP.IsLoopback() {
			t.Fatalf("unexpected peer %s", result.Remote)
		}
		_, p, _ := net.SplitHostPort(result.Local)
		if n, _ := strconv.Atoi(p); n == 0 {
			t.Fatal("missing retained local socket")
		}
	}
}
