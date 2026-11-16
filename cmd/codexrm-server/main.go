package main

import (
	"context"
	"flag"
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
	webDir := flag.String("web", "", "optional static UI directory (default: embedded)")
	enrollKey := flag.String("enroll-key", os.Getenv("CODEXRM_ENROLL_KEY"), "key required to register remote agents")
	flag.Parse()

	absWeb := ""
	if *webDir != "" {
		absWeb, _ = filepath.Abs(*webDir)
	}
	srv, err := server.New(server.Config{
		Addr:       *addr,
		DataDir:    *dataDir,
		WebDir:     absWeb,
		AdminToken: *enrollKey,
	})
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("CodexRM server listening on %s", *addr)
	log.Printf("web UI: %s", absWeb)
	httpServer := &http.Server{Addr: *addr, Handler: srv.Handler(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}
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

func defaultDataDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ".codexrm-server"
	}
	return filepath.Join(home, ".codexrm-server")
}
