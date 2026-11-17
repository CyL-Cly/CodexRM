package main

import (
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestHTTPServerReadLimits(t *testing.T) {
	srv := newHTTPServer("127.0.0.1:0", http.NotFoundHandler())
	if srv.ReadHeaderTimeout != 10*time.Second {
		t.Fatalf("ReadHeaderTimeout = %v", srv.ReadHeaderTimeout)
	}
	if srv.ReadTimeout != 30*time.Second {
		t.Fatalf("ReadTimeout = %v", srv.ReadTimeout)
	}
	if srv.IdleTimeout != 90*time.Second {
		t.Fatalf("IdleTimeout = %v", srv.IdleTimeout)
	}
	if srv.MaxHeaderBytes != 1<<20 {
		t.Fatalf("MaxHeaderBytes = %d", srv.MaxHeaderBytes)
	}
	if srv.WriteTimeout != 0 {
		t.Fatalf("WriteTimeout must not interrupt updates or WebSockets: %v", srv.WriteTimeout)
	}
}

func TestResolveWebDirRequiresIndexOnDisk(t *testing.T) {
	if _, err := resolveWebDir(t.TempDir()); err == nil {
		t.Fatal("empty web directory accepted")
	}
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "index.html"), []byte("disk UI"), 0600); err != nil {
		t.Fatal(err)
	}
	got, err := resolveWebDir(root)
	if err != nil {
		t.Fatal(err)
	}
	if !filepath.IsAbs(got) {
		t.Fatalf("web directory is not absolute: %q", got)
	}
}
