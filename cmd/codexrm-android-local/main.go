package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/codexrm/codexrm/internal/agent"
)

func main() {
	if len(os.Args) != 4 {
		log.Fatal("usage: codexrm-android-local <codex executable> <agent state dir> <workspace>")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer cancel()
	err := agent.RunLocal(ctx, agent.Config{
		CodexPath: os.Args[1], DataDir: os.Args[2], Cwd: os.Args[3], Name: "本机 Codex", AllowFileManagement: true,
	}, os.Stdin, os.Stdout)
	if err != nil {
		log.Fatal(err)
	}
}
