package p2p

import (
	"errors"
	"syscall"
	"testing"
)

func TestIsProhibited(t *testing.T) {
	if isProhibited(errors.New("timeout")) {
		t.Fatal("ordinary errors are not a firewall")
	}
	if !isProhibited(syscall.EPERM) || !isProhibited(syscall.EACCES) {
		t.Fatal("permission errors should be treated as blocked")
	}
	if isProhibited(syscall.ECONNREFUSED) {
		t.Fatal("a refused port still proves outbound packets leave the host")
	}
}
