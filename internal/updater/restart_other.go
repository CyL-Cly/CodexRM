//go:build !linux

package updater

import (
	"context"
	"errors"
)

type LinuxUpdate struct{}

func LinuxExecutable() string { return "" }
func signalLinuxReady()       {}
func RollbackStartup() error  { return nil }

func (*Plan) StageLinux() (*LinuxUpdate, error) {
	return nil, errors.New("standalone Linux updates require Linux")
}

func (*LinuxUpdate) Restart(context.Context) error {
	return errors.New("standalone Linux updates require Linux")
}
