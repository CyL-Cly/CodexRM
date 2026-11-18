package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/codexrm/p2p-probe/core"
)

type roundState struct {
	peer    map[string]core.Peer
	ready   map[string]core.Peer
	barrier map[string]bool
	done    map[string]core.Result
}

func main() {
	mode := flag.String("mode", "host", "host or diagnose")
	servers := flag.String("stun", "162.159.207.0:3478,162.159.207.1:3478", "IPv4 STUN responders; use a controlled alternate-port responder for filtering diagnostics")
	phoneServers := flag.String("phone-stun", "", "optional phone-specific STUN list")
	port := flag.Int("port", 18879, "loopback coordinator port")
	rounds := flag.Int("rounds", 5, "new sessions")
	sockets := flag.Int("sockets", 256, "phone sockets")
	ports := flag.Int("ports", 2048, "agent distinct probe ports")
	window := flag.Int("window", 25, "seconds per session")
	prediction := flag.Int("prediction", 64, "port prediction radius before random probes")
	output := flag.String("out", "results", "private result directory")
	flag.Parse()
	if *mode == "diagnose" {
		b, _ := json.MarshalIndent(core.Diagnose(strings.Split(*servers, ",")), "", "  ")
		fmt.Println(string(b))
		return
	}
	if e := os.MkdirAll(*output, 0700); e != nil {
		panic(e)
	}
	key := make([]byte, 32)
	rand.Read(key)
	cfg := core.Config{Coordinator: fmt.Sprintf("http://127.0.0.1:%d", *port), Secret: hex.EncodeToString(key), Role: "agent", Servers: strings.Split(*servers, ","), Rounds: *rounds, Sockets: *sockets, Ports: *ports, TimeoutSeconds: *window, Prediction: *prediction}
	phone := cfg
	phone.Role = "phone"
	if *phoneServers != "" {
		phone.Servers = strings.Split(*phoneServers, ",")
	}
	raw, _ := json.MarshalIndent(phone, "", "  ")
	if e := os.WriteFile(filepath.Join(*output, "phone-config.json"), raw, 0600); e != nil {
		panic(e)
	}
	file, e := os.OpenFile(filepath.Join(*output, "rounds.jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		panic(e)
	}
	defer file.Close()
	var mu sync.Mutex
	states := map[int]*roundState{}
	state := func(n int) *roundState {
		s := states[n]
		if s == nil {
			s = &roundState{map[string]core.Peer{}, map[string]core.Peer{}, map[string]bool{}, map[string]core.Result{}}
			states[n] = s
		}
		return s
	}
	allDone := make(chan struct{})
	closed := false
	started := false
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+cfg.Secret {
			http.Error(w, "unauthorized", 401)
			return
		}
		role := r.URL.Query().Get("role")
		n, _ := strconv.Atoi(r.URL.Query().Get("round"))
		other := "phone"
		if role == "phone" {
			other = "agent"
		}
		if role != "phone" && role != "agent" {
			http.Error(w, "role", 400)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/start" {
			mu.Lock()
			if !started {
				started = true
				go func() {
					err := core.Run(cfg, func(result core.Result) { b, _ := json.Marshal(result); fmt.Println(string(b)) })
					if err != nil {
						fmt.Fprintln(os.Stderr, err)
					}
				}()
			}
			mu.Unlock()
			json.NewEncoder(w).Encode(phone)
			return
		}
		if n < 1 || n > cfg.Rounds {
			http.Error(w, "round", 400)
			return
		}
		mu.Lock()
		s := state(n)
		switch r.URL.Path {
		case "/publish", "/ready":
			var peer core.Peer
			err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&peer)
			if err != nil {
				mu.Unlock()
				http.Error(w, err.Error(), 400)
				return
			}
			if r.URL.Path == "/publish" {
				s.peer[role] = peer
			} else {
				s.ready[role] = peer
			}
			mu.Unlock()
			fmt.Fprint(w, "{}")
			return
		case "/done":
			var result core.Result
			err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&result)
			if err != nil {
				mu.Unlock()
				http.Error(w, err.Error(), 400)
				return
			}
			s.done[role] = result
			b, _ := json.Marshal(result)
			file.Write(append(b, '\n'))
			file.Sync()
			if role == "phone" {
				fmt.Println(string(b))
			}
			if n == cfg.Rounds && len(s.done) == 2 && !closed {
				closed = true
				close(allDone)
			}
			mu.Unlock()
			fmt.Fprint(w, "{}")
			return
		case "/barrier":
			s.barrier[role] = true
		case "/peer", "/wait-ready":
		default:
			mu.Unlock()
			http.NotFound(w, r)
			return
		}
		mu.Unlock()
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		for {
			mu.Lock()
			s = state(n)
			var answer any
			ready := false
			switch r.URL.Path {
			case "/peer":
				answer, ready = s.peer[other]
			case "/wait-ready":
				answer, ready = s.ready[other]
			case "/barrier":
				ready = s.barrier[other]
				answer = map[string]bool{"done": ready}
			}
			mu.Unlock()
			if ready {
				json.NewEncoder(w).Encode(answer)
				return
			}
			select {
			case <-r.Context().Done():
				return
			case <-ticker.C:
			}
		}
	})
	srv := &http.Server{Addr: fmt.Sprintf("127.0.0.1:%d", *port), Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() { <-allDone; time.Sleep(time.Second); srv.Close() }()
	fmt.Printf("coordinator on loopback :%d; rounds=%d; phoneSockets=%d; ports=%d; waiting for Android start\n", *port, cfg.Rounds, cfg.Sockets, cfg.Ports)
	if err := srv.ListenAndServe(); err != http.ErrServerClosed {
		panic(err)
	}
}
