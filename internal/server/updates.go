package server

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/codexrm/codexrm/internal/updater"
)

func (s *Server) updateManifest(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	f, err := os.Open(filepath.Join(s.cfg.UpdateDir, "update.json"))
	if os.IsNotExist(err) {
		fail(w, http.StatusNotFound, "no client update published")
		return
	}
	if err != nil {
		fail(w, http.StatusInternalServerError, "cannot read client update")
		return
	}
	defer f.Close()
	m, err := updater.ReadManifest(f)
	if err != nil {
		fail(w, http.StatusInternalServerError, "invalid client update manifest")
		return
	}
	writeJSON(w, http.StatusOK, m)
}

func (s *Server) updatePackage(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	if name != filepath.Base(name) || strings.ContainsAny(name, "/\\:") || !strings.EqualFold(filepath.Ext(name), ".zip") {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.UpdateDir, name))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Content-Type", "application/zip")
	http.ServeContent(w, r, name, info.ModTime(), f)
}
