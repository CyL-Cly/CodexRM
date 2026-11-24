package agent

import (
	"testing"

	"github.com/codexrm/codexrm/internal/desktop"
)

func TestDesktopStreamMagicIsNotUploadID(t *testing.T) {
	magic := desktop.StreamMagic
	hex := (magic >= '0' && magic <= '9') || (magic >= 'a' && magic <= 'f') || (magic >= 'A' && magic <= 'F')
	if hex {
		t.Fatalf("desktop stream magic collides with a share UUID: %d", magic)
	}
}
