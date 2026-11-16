package web

import "embed"

// Files ships the UI inside the server executable.
//
//go:embed index.html app.js styles.css pwa.js sw.js manifest.webmanifest icons/*.png icons/*.svg
var Files embed.FS
