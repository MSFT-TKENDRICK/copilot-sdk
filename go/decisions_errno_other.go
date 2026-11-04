//go:build !windows

package copilot

import "syscall"

var decisionsPlatformErrnoNames []struct {
	errno syscall.Errno
	name  string
}
