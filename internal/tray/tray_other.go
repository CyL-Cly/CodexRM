//go:build !windows

package tray

import "context"

// Run keeps non-Windows agents console-based; the system tray is Windows-only.
func Run(ctx context.Context, _ string, _ func(), run func(context.Context) error) error {
	return run(ctx)
}
