package agent

import (
	"fmt"
	"net/url"
	"time"

	"github.com/codexrm/codexrm/internal/protocol"
	"github.com/skip2/go-qrcode"
)

func (a *Agent) PairingInstructions(pair *protocol.PairStartResponse) (string, error) {
	code := pair.ManualPairingCode
	if code == "" {
		code = pair.PairingCode
	}
	link := a.cfg.ServerURL + "/#pair=" + url.QueryEscape(code)
	qr, err := qrcode.New(link, qrcode.Medium)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("Pairing code: %s\nPairing link: %s\nExpires at: %s\nScan to pair this browser:\n%s", code, link, time.Unix(pair.ExpiresAt, 0).Local().Format(time.RFC3339), qr.ToSmallString(false)), nil
}
