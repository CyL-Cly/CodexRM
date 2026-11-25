package server

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/updater"
)

func (s *Server) updateManifest(w http.ResponseWriter, r *http.Request) {
	serveUpdateManifest(w, s.cfg.UpdateDir, "update.json")
}

func (s *Server) androidUpdateManifest(w http.ResponseWriter, r *http.Request) {
	serveUpdateManifest(w, s.cfg.UpdateDir, "android-update.json")
}

func (s *Server) linuxUpdateManifest(w http.ResponseWriter, r *http.Request) {
	serveUpdateManifest(w, s.cfg.UpdateDir, "linux-update.json")
}

func (s *Server) serverUpdateManifest(w http.ResponseWriter, r *http.Request) {
	serveUpdateManifest(w, s.cfg.ServerUpdateDir, release.ServerManifestName)
}

func serveUpdateManifest(w http.ResponseWriter, directory, name string) {
	w.Header().Set("Cache-Control", "no-store")
	if directory == "" {
		fail(w, http.StatusNotFound, "no update published")
		return
	}
	f, err := os.Open(filepath.Join(directory, name))
	if os.IsNotExist(err) {
		fail(w, http.StatusNotFound, "no update published")
		return
	}
	if err != nil {
		fail(w, http.StatusInternalServerError, "cannot read update")
		return
	}
	defer f.Close()
	m, err := updater.ReadManifest(f)
	if err != nil {
		fail(w, http.StatusInternalServerError, "invalid update manifest")
		return
	}
	writeJSON(w, http.StatusOK, m)
}

func (s *Server) updatePackage(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	extension := strings.ToLower(filepath.Ext(name))
	if name != filepath.Base(name) || strings.ContainsAny(name, "/\\:") || (extension != ".zip" && extension != ".apk") {
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
	if extension == ".apk" {
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	} else {
		w.Header().Set("Content-Type", "application/zip")
	}
	http.ServeContent(w, r, name, info.ModTime(), f)
}
