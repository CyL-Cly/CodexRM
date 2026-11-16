package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/codexrm/codexrm/internal/agent"
)

func main() {
	serverURL := flag.String("server", "http://127.0.0.1:8080", "CodexRM server URL")
	appServer := flag.String("appserver", "", "local Codex app-server websocket URL, e.g. ws://127.0.0.1:4500")
	name := flag.String("name", "", "display name for this host")
	dataDir := flag.String("data", "", "agent state directory")
	mock := flag.Bool("mock", false, "start an in-process mock app-server for demo")
	mockAddr := flag.String("mock-addr", "127.0.0.1:0", "mock app-server listen address")
	doPair := flag.Bool("pair", false, "create a pairing code and exit")
	codexPath := flag.String("codex", "codex", "Codex CLI executable (stdio mode)")
	cwd := flag.String("cwd", "", "default local workspace directory")
	ccswitchDB := flag.String("ccswitch-db", os.Getenv("CODEXRM_CCSWITCH_DB"), "CCSwitch database path (default ~/.cc-switch/cc-switch.db)")
	enrollKey := flag.String("enroll-key", os.Getenv("CODEXRM_ENROLL_KEY"), "server enrollment key")
	appToken := flag.String("appserver-token", os.Getenv("CODEXRM_APPSERVER_TOKEN"), "optional app-server WebSocket bearer token")
	flag.Parse()

	a, err := agent.New(agent.Config{
		ServerURL:    *serverURL,
		AppServerURL: *appServer,
		Name:         *name,
		DataDir:      *dataDir,
		Mock:         *mock && !*doPair,
		MockAddr:     *mockAddr,
		CCSwitchDB:   *ccswitchDB,
		CodexPath:    *codexPath, Cwd: *cwd, EnrollmentKey: *enrollKey, AppServerToken: *appToken,
	})
	if err != nil {
		log.Fatal(err)
	}

	if *doPair {
		out, err := a.Pair(context.Background())
		if err != nil {
			log.Fatal(err)
		}
		code := out.ManualPairingCode
		if code == "" {
			code = out.PairingCode
		}
		fmt.Printf("Pairing code: %s\n", code)
		fmt.Printf("Expires at unix: %d\n", out.ExpiresAt)
		fmt.Printf("Open %s and enter the code.\n", *serverURL)
		return
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := a.Run(ctx); err != nil && ctx.Err() == nil {
		log.Fatal(err)
	}
}
