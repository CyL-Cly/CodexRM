package server

import (
	"fmt"
	"net"
	"net/http"
)

func (s *Server) secureWebRequest(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	if !s.cfg.TrustLoopbackWebProxy {
		return false
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	protos := r.Header.Values("X-Forwarded-Proto")
	return err == nil && net.ParseIP(host).IsLoopback() && len(protos) == 1 && protos[0] == "https"
}

func (s *Server) webHandler() http.Handler {
	files := http.FileServer(http.Dir(s.cfg.WebDir))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.secureWebRequest(r) {
			files.ServeHTTP(w, r)
			return
		}
		// This is the static-web fallback only. API routes, WebSockets and
		// update manifests are handled separately by the relay mux.
		w.Header().Del("Service-Worker-Allowed")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusForbidden)
		if r.Method != http.MethodHead {
			_, _ = fmt.Fprintln(w, "如需使用网页控制需要使用https")
		}
	})
}
