module github.com/CyL-Cly/CodexRM

go 1.24

require (
	github.com/CyL-Cly/CodexRM-Agent v0.0.0-20261126103422-34406c54de25
	github.com/CyL-Cly/CodexRM-Server v0.0.0-20261126074707-6dccc103e137
	github.com/flynn/noise v1.1.0
)

require (
	github.com/dustin/go-humanize v1.0.1 // indirect
	github.com/getlantern/context v0.0.0-20190109183933-c447772a6520 // indirect
	github.com/getlantern/errors v0.0.0-20190325191628-abdb3e3e36f7 // indirect
	github.com/getlantern/golog v0.0.0-20190830074920-4ef2e798c2d7 // indirect
	github.com/getlantern/hex v0.0.0-20190417191902-c6586a6fe0b7 // indirect
	github.com/getlantern/hidden v0.0.0-20190325191715-f02dbb02be55 // indirect
	github.com/getlantern/ops v0.0.0-20190325191751-d70cb0d6f85f // indirect
	github.com/getlantern/systray v1.2.2 // indirect
	github.com/go-stack/stack v1.8.0 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/gorilla/websocket v1.5.3 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/ncruces/go-strftime v0.1.9 // indirect
	github.com/oxtoacart/bpool v0.0.0-20190530202638-03653db5a59c // indirect
	github.com/pelletier/go-toml/v2 v2.2.3 // indirect
	github.com/quic-go/quic-go v0.59.1 // indirect
	github.com/remyoudompheng/bigfft v0.0.0-20230129092748-24d4a6f8daec // indirect
	github.com/robfig/cron/v3 v3.0.1 // indirect
	github.com/skip2/go-qrcode v0.0.0-20200617195104-da1b6568686e // indirect
	github.com/yuin/goldmark v1.8.6 // indirect
	golang.org/x/crypto v0.41.0 // indirect
	golang.org/x/net v0.43.0 // indirect
	golang.org/x/sys v0.35.0 // indirect
	modernc.org/libc v1.55.3 // indirect
	modernc.org/mathutil v1.6.0 // indirect
	modernc.org/memory v1.8.0 // indirect
	modernc.org/sqlite v1.34.5 // indirect
)

tool (
	github.com/CyL-Cly/CodexRM-Agent/cmd/codexrm-agent
	github.com/CyL-Cly/CodexRM-Agent/cmd/codexrm-setup
	github.com/CyL-Cly/CodexRM-Server/cmd/codexrm-nat-stun
	github.com/CyL-Cly/CodexRM-Server/cmd/codexrm-server
	github.com/CyL-Cly/CodexRM-Server/cmd/codexrm-server-updater
)
