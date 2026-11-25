package main

import (
	"context"
	"encoding/hex"
	"flag"
	"fmt"
	"log"
	"net/netip"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/codexrm/codexrm/internal/natstun"
)

func main() {
	listen := flag.String("listen-ip", os.Getenv("NATSTUN_LISTEN_IP"), "local IPv4 address for STUN")
	public := flag.String("public-ip", os.Getenv("NATSTUN_PUBLIC_IP"), "public IPv4 address for this node")
	other := flag.String("other-ip", os.Getenv("NATSTUN_OTHER_IP"), "public IPv4 address for the other node")
	primary := flag.Uint("primary-port", 3478, "primary STUN UDP port, identical on both nodes")
	alternate := flag.Uint("alternate-port", 3479, "alternate STUN UDP port, identical on both nodes")
	peerListen := flag.String("peer-listen", os.Getenv("NATSTUN_PEER_LISTEN"), "local IPv4:UDP port for peer traffic")
	peerRemote := flag.String("peer-remote", os.Getenv("NATSTUN_PEER_REMOTE"), "remote IPv4:UDP port for peer traffic")
	keyFile := flag.String("key-file", os.Getenv("NATSTUN_KEY_FILE"), "file containing the shared 32-byte hexadecimal key")
	flag.Parse()
	if *primary > 65535 || *alternate > 65535 {
		log.Fatal("STUN port out of range")
	}
	cfg := natstun.Config{Ports: [2]uint16{uint16(*primary), uint16(*alternate)}}
	var err error
	for _, item := range []struct {
		name, raw string
		out       *netip.Addr
	}{{"listen-ip", *listen, &cfg.ListenIP}, {"public-ip", *public, &cfg.PublicIP}, {"other-ip", *other, &cfg.OtherIP}} {
		*item.out, err = netip.ParseAddr(item.raw)
		if err != nil {
			log.Fatalf("--%s requires an IPv4 address", item.name)
		}
	}
	for _, item := range []struct {
		name, raw string
		out       *netip.AddrPort
	}{{"peer-listen", *peerListen, &cfg.PeerListen}, {"peer-remote", *peerRemote, &cfg.PeerRemote}} {
		*item.out, err = netip.ParseAddrPort(item.raw)
		if err != nil {
			log.Fatalf("--%s requires an IPv4:UDP-port endpoint", item.name)
		}
	}
	if err := loadKey(*keyFile, &cfg.Key); err != nil {
		log.Fatal(err)
	}
	s, err := natstun.Listen(cfg)
	if err != nil {
		log.Fatal(err)
	}
	defer s.Close()
	log.Printf("NAT diagnostic STUN bound to %s UDP %d/%d, advertised as %s with alternate %s", cfg.ListenIP, cfg.Ports[0], cfg.Ports[1], cfg.PublicIP, cfg.OtherIP)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	<-ctx.Done()
}

func loadKey(path string, out *[32]byte) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read peer key file: %w", err)
	}
	encoded := strings.TrimSpace(string(raw))
	if len(encoded) != hex.EncodedLen(len(out)) {
		return fmt.Errorf("peer key must be 32 bytes encoded as hexadecimal")
	}
	if _, err := hex.Decode(out[:], []byte(encoded)); err != nil || *out == [32]byte{} {
		return fmt.Errorf("peer key must be 32 nonzero-key bytes encoded as hexadecimal")
	}
	return nil
}
