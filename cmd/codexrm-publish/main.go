package main

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"os"

	"github.com/codexrm/codexrm/internal/publisher"
)

func main() {
	configPath := flag.String("config", "build/publish.config.json", "private Cloudreve configuration")
	archive := flag.String("package", "", "release ZIP or APK package")
	manifest := flag.String("manifest", "build/dist/update.json", "package update manifest")
	flag.Parse()
	data, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	var cfg publisher.Config
	if err = json.Unmarshal(data, &cfg); err != nil {
		log.Fatal(err)
	}
	c, err := publisher.Login(context.Background(), cfg.Cloudreve)
	if err != nil {
		log.Fatal(err)
	}
	if *archive == "" {
		log.Fatal("-package is required")
	}
	m, err := c.Publish(context.Background(), *archive, *manifest)
	if err != nil {
		log.Fatal(err)
	}
	_ = json.NewEncoder(os.Stdout).Encode(m)
}
