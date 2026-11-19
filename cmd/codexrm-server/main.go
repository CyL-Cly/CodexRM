package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"net/netip"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/server"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dataDir := flag.String("data", defaultDataDir(), "data directory")
	webDir := flag.String("web", "web", "static UI directory served from disk")
	updateDir := flag.String("updates", "", "client release directory (default: <data>/updates)")
	enrollKey := flag.String("enroll-key", os.Getenv("CODEXRM_ENROLL_KEY"), "key required to register remote agents")
	publicEnrollment := flag.Bool("public-enrollment", false, "allow agent registration without a shared key")
	stun := flag.String("p2p-stun", os.Getenv("CODEXRM_P2P_STUN"), "comma-separated IPv4:port or [IPv6]:port STUN endpoints; empty disables public P2P")
	lan := flag.Bool("p2p-lan", true, "allow paired peers to try on-link IPv4 direct connections")
	verifyListen := flag.String("p2p-verify-listen", os.Getenv("CODEXRM_P2P_VERIFY_LISTEN"), "local IPv4:UDP port for candidate verification")
	verifyPublic := flag.String("p2p-verify-public", os.Getenv("CODEXRM_P2P_VERIFY_PUBLIC"), "public IPv4:UDP port for candidate verification")
	verifyMore := flag.String("p2p-verify-more", os.Getenv("CODEXRM_P2P_VERIFY_MORE"), "up to three additional public IPv4:UDP verification endpoints")
	verifyKeyFile := flag.String("p2p-verify-key-file", os.Getenv("CODEXRM_P2P_VERIFY_KEY_FILE"), "file with the 32-byte hex key shared by trusted UDP verifiers")
	verifyOnly := flag.Bool("p2p-verify-only", false, "serve only the UDP verifier using the shared key file")
	trustProxy := flag.Bool("p2p-trust-loopback-proxy", false, "trust X-Real-IP and X-Forwarded-Proto from a local TLS reverse proxy")
	flag.Parse()
	if *verifyOnly {
		listen, err := netip.ParseAddrPort(*verifyListen)
		if err != nil || !listen.Addr().Is4() || listen.Port() == 0 || *verifyKeyFile == "" {
			log.Fatal("UDP verifier mode requires a local IPv4:UDP listen address and a shared key file")
		}
		v, err := p2p.ListenVerifier(listen, *verifyKeyFile)
		if err != nil {
			log.Fatal(err)
		}
		defer v.Close()
		ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer cancel()
		log.Printf("CodexRM UDP verifier listening on %s", v.Addr())
		<-ctx.Done()
		return
	}

	absWeb, err := resolveWebDir(*webDir)
	if err != nil {
		log.Fatal(err)
	}
	srv, err := server.New(server.Config{
		Addr:             *addr,
		DataDir:          *dataDir,
		WebDir:           absWeb,
		AdminToken:       *enrollKey,
		PublicEnrollment: *publicEnrollment,
		UpdateDir:        *updateDir,
		P2PSTUN:          *stun,
		P2PLAN:           *lan,
		P2PVerifyListen:  *verifyListen,
		P2PVerifyPublic:  *verifyPublic,
		P2PVerifyMore:    *verifyMore,
		P2PVerifyKeyFile: *verifyKeyFile,
		P2PTrustProxy:    *trustProxy,
	})
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("CodexRM server listening on %s", *addr)
	log.Printf("web UI: %s", absWeb)
	httpServer := newHTTPServer(*addr, srv.Handler())
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	go func() {
		<-ctx.Done()
		srv.Close()
		shutdownCtx, done := context.WithTimeout(context.Background(), 5*time.Second)
		defer done()
		_ = httpServer.Shutdown(shutdownCtx)
	}()
	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func resolveWebDir(path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(filepath.Join(abs, "index.html"))
	if err != nil {
		return "", fmt.Errorf("web UI index not found in %s: %w", abs, err)
	}
	if info.IsDir() {
		return "", fmt.Errorf("web UI index is a directory: %s", abs)
	}
	return abs, nil
}

func newHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		IdleTimeout:       90 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
}

func defaultDataDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ".codexrm-server"
	}
	return filepath.Join(home, ".codexrm-server")
}
