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
	"strings"
	"syscall"
	"time"

	"github.com/codexrm/codexrm/internal/p2p"
	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/server"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dataDir := flag.String("data", defaultDataDir(), "data directory")
	webDir := flag.String("web", "web", "static UI directory served from disk")
	updateDir := flag.String("updates", "", "client release directory (default: <data>/updates)")
	serverUpdateDir := flag.String("server-updates", "server-updates", "directory for the Server release manifest (keep publisher-owned and separate from client updates)")
	version := flag.Bool("version", false, "print the Server release version and exit")
	enrollKey := flag.String("enroll-key", os.Getenv("CODEXRM_ENROLL_KEY"), "key required to register remote agents")
	publicEnrollment := flag.Bool("public-enrollment", false, "allow agent registration without a shared key")
	stun := flag.String("p2p-stun", os.Getenv("CODEXRM_P2P_STUN"), "comma-separated IPv4:port or [IPv6]:port STUN endpoints; empty disables public P2P")
	natSTUN := flag.String("p2p-nat-stun", os.Getenv("CODEXRM_P2P_NAT_STUN"), "optional comma-separated IPv4:port STUN endpoints for NAT diagnosis; defaults to IPv4 endpoints in --p2p-stun")
	natCheck := flag.Bool("check-nat-stun", false, "validate endpoints set by --p2p-nat-stun without starting the RM; exits nonzero if any fail")
	lan := flag.Bool("p2p-lan", true, "allow paired peers to try on-link IPv4 direct connections")
	verifyListen := flag.String("p2p-verify-listen", os.Getenv("CODEXRM_P2P_VERIFY_LISTEN"), "local UDP endpoint for candidate verification")
	verifyPublic := flag.String("p2p-verify-public", os.Getenv("CODEXRM_P2P_VERIFY_PUBLIC"), "public UDP endpoint for candidate verification")
	verifyMore := flag.String("p2p-verify-more", os.Getenv("CODEXRM_P2P_VERIFY_MORE"), "up to three additional public UDP verification endpoints")
	verifyKeyFile := flag.String("p2p-verify-key-file", os.Getenv("CODEXRM_P2P_VERIFY_KEY_FILE"), "file with the 32-byte hex key shared by trusted UDP verifiers")
	verifyOnly := flag.Bool("p2p-verify-only", false, "serve only the UDP verifier using the shared key file")
	trustProxy := flag.Bool("p2p-trust-loopback-proxy", false, "trust X-Real-IP and X-Forwarded-Proto from a local TLS reverse proxy")
	trustWebProxy := flag.Bool("web-trust-loopback-proxy", false, "allow the web UI behind a local HTTPS reverse proxy that overwrites X-Forwarded-Proto")
	flag.Parse()
	if *version {
		fmt.Printf("CodexRM Server %s (code %d)\n", release.ServerVersionName, release.ServerVersionCode)
		return
	}
	if *natCheck {
		if *verifyOnly {
			log.Fatal("--check-nat-stun and --p2p-verify-only are mutually exclusive")
		}
		ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer cancel()
		if err := checkNATSTUN(ctx, *natSTUN); err != nil {
			log.Print(err)
			os.Exit(1)
		}
		return
	}
	if *verifyOnly {
		listen, err := netip.ParseAddrPort(*verifyListen)
		if err != nil || listen.Addr().Zone() != "" || listen.Addr().Is4In6() || listen.Port() == 0 || *verifyKeyFile == "" {
			log.Fatal("UDP verifier mode requires a local UDP listen address and a shared key file")
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
		Addr:                  *addr,
		DataDir:               *dataDir,
		WebDir:                absWeb,
		AdminToken:            *enrollKey,
		PublicEnrollment:      *publicEnrollment,
		UpdateDir:             *updateDir,
		ServerUpdateDir:       *serverUpdateDir,
		P2PSTUN:               *stun,
		P2PNATSTUN:            *natSTUN,
		P2PLAN:                *lan,
		P2PVerifyListen:       *verifyListen,
		P2PVerifyPublic:       *verifyPublic,
		P2PVerifyMore:         *verifyMore,
		P2PVerifyKeyFile:      *verifyKeyFile,
		P2PTrustProxy:         *trustProxy,
		TrustLoopbackWebProxy: *trustWebProxy || *trustProxy,
		RequireSecureClients:  true,
	})
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("CodexRM Server %s (code %d) listening on %s", release.ServerVersionName, release.ServerVersionCode, *addr)
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

func checkNATSTUN(ctx context.Context, raw string) error {
	fields := strings.Split(raw, ",")
	if len(fields) > 4 {
		return fmt.Errorf("--check-nat-stun requires one to four --p2p-nat-stun IPv4:UDP-port endpoints")
	}
	endpoints := make([]netip.AddrPort, 0, len(fields))
	for _, value := range fields {
		addr, err := netip.ParseAddrPort(strings.TrimSpace(value))
		if err != nil || !addr.Addr().Is4() || addr.Port() == 0 {
			return fmt.Errorf("--check-nat-stun requires one to four --p2p-nat-stun IPv4:UDP-port endpoints")
		}
		endpoints = append(endpoints, addr)
	}
	ok := true
	for _, dst := range endpoints {
		classic, modern := p2p.CheckNATSTUN(ctx, dst)
		for _, test := range []struct {
			protocol string
			err      error
		}{{"RFC 3489", classic}, {"RFC 5780", modern}} {
			if test.err != nil {
				log.Printf("%s %s not validated: %v", dst, test.protocol, test.err)
				ok = false
			} else {
				log.Printf("%s %s passed (Binding and changed source)", dst, test.protocol)
			}
		}
	}
	if !ok {
		return fmt.Errorf("not all endpoints passed; verify the STUN server and the checker's IPv4 UDP path")
	}
	return nil
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
