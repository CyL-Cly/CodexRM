package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/codexrm/codexrm/internal/server"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	dataDir := flag.String("data", defaultDataDir(), "data directory")
	webDir := flag.String("web", "web", "static UI directory served from disk")
	updateDir := flag.String("updates", "", "client release directory (default: <data>/updates)")
	enrollKey := flag.String("enroll-key", os.Getenv("CODEXRM_ENROLL_KEY"), "key required to register remote agents")
	flag.Parse()

	absWeb, err := resolveWebDir(*webDir)
	if err != nil {
		log.Fatal(err)
	}
	srv, err := server.New(server.Config{
		Addr:       *addr,
		DataDir:    *dataDir,
		WebDir:     absWeb,
		AdminToken: *enrollKey,
		UpdateDir:  *updateDir,
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
