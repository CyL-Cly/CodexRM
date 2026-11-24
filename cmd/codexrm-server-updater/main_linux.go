package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/codexrm/codexrm/internal/release"
	"github.com/codexrm/codexrm/internal/serverupdate"
	"golang.org/x/sys/unix"
)

const installRoot = "/opt/codexrm"
const defaultFeed = "https://codexrm.dagongren.tech/" + release.ServerManifestName
const healthURL = "http://127.0.0.1:18080/api/v1/health"

func main() {
	now := flag.Bool("now", false, "run once without waiting for the cron schedule")
	check := flag.Bool("check-config", false, "validate update environment variables and show the next scheduled check")
	flag.Parse()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx, *now, *check); err != nil {
		log.Fatal(err)
	}
}

func env(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}

func run(ctx context.Context, immediate, check bool) error {
	enabled, err := strconv.ParseBool(env("CODEXRM_SERVER_AUTO_UPDATE", "true"))
	if err != nil {
		return errors.New("set CODEXRM_SERVER_AUTO_UPDATE to true or false")
	}
	if !enabled && !check && !immediate {
		return nil
	}
	spec := env("CODEXRM_SERVER_UPDATE_CRON", serverupdate.DefaultCron)
	zone := env("CODEXRM_SERVER_UPDATE_TZ", serverupdate.DefaultTimezone)
	schedule, err := serverupdate.ParseSchedule(spec, zone)
	if err != nil {
		return err
	}
	feed, err := serverupdate.ParseFeed(env("CODEXRM_SERVER_UPDATE_URL", defaultFeed))
	if err != nil {
		return err
	}
	if check {
		fmt.Printf("Automatic Server updates: %t; cron: %s; timezone: %s; next matching time: %s\n",
			enabled, spec, zone, schedule.Next(time.Now().In(schedule.Location)).Format(time.RFC3339))
		return nil
	}
	if !immediate && !serverupdate.Due(schedule, time.Now()) {
		return nil
	}
	if os.Geteuid() != 0 {
		return errors.New("Server updates require the root-owned systemd updater")
	}
	for _, dir := range []string{filepath.Dir(installRoot), installRoot, filepath.Join(installRoot, "bin"), filepath.Join(installRoot, "web")} {
		var st unix.Stat_t
		if err := unix.Lstat(dir, &st); err != nil {
			return err
		}
		if st.Uid != 0 || st.Mode&unix.S_IFMT != unix.S_IFDIR || st.Mode&0022 != 0 {
			return fmt.Errorf("Server update requires a root-owned directory not writable by other users: %s", dir)
		}
	}
	unix.Umask(0022) // Installed web files must be readable by the unprivileged relay.
	fd, err := unix.Open(filepath.Join(installRoot, ".install.lock"), unix.O_CREAT|unix.O_RDWR|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0600)
	if err != nil {
		return err
	}
	defer unix.Close(fd)
	if err := unix.Flock(fd, unix.LOCK_EX|unix.LOCK_NB); err != nil {
		if errors.Is(err, unix.EWOULDBLOCK) {
			log.Print("Server update skipped: another installation holds the lock")
			return nil
		}
		return err
	}
	unitCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	user, err := exec.CommandContext(unitCtx, "/usr/bin/systemctl", "show", "--property=User", "--value", "codexrm.service").Output()
	if err != nil || strings.TrimSpace(string(user)) != "codexrm" {
		return errors.New("Server automatic updates require codexrm.service to run as the unprivileged codexrm account")
	}
	probe := &http.Client{
		Timeout:       2 * time.Second,
		Transport:     &http.Transport{}, // Direct loopback connection, without an HTTPS/HTTP proxy.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return (serverupdate.Runner{
		Root: installRoot, Feed: feed, Client: http.DefaultClient,
		Service: func(ctx context.Context, action string) error {
			ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
			defer cancel()
			out, err := exec.CommandContext(ctx, "/usr/bin/systemctl", action, "codexrm.service").CombinedOutput()
			if err != nil {
				return fmt.Errorf("systemctl %s codexrm.service: %w (%s)", action, err, out)
			}
			return nil
		},
		Health: func(ctx context.Context) (release.ServerHealth, error) {
			return serverupdate.ReadHealth(ctx, probe, healthURL)
		},
	}).Run(ctx)
}
