//go:build !windows

package desktop

import (
	"context"
	"os/exec"
	"runtime"
)

type Instance struct{}

func Acquire(string, bool) (*Instance, error)                   { return &Instance{}, nil }
func Stop(string) error                                         { return nil }
func (*Instance) Watch(ctx context.Context, _ func(), _ func()) { <-ctx.Done() }
func (*Instance) Close()                                        {}
func EnsureP2PFirewall() error                                  { return nil }
func OpenBrowser(url string) error {
	command := "xdg-open"
	if runtime.GOOS == "darwin" {
		command = "open"
	}
	cmd := exec.Command(command, url)
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}
