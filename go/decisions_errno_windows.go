//go:build windows

package copilot

import "syscall"

// Winsock equivalents of the POSIX errno values recognized by decisionsErrnoCode.
var decisionsPlatformErrnoNames = []struct {
	errno syscall.Errno
	name  string
}{
	{syscall.WSAECONNRESET, "ECONNRESET"},
	{syscall.Errno(10061), "ECONNREFUSED"}, // WSAECONNREFUSED
	{syscall.Errno(10060), "ETIMEDOUT"},    // WSAETIMEDOUT
	{syscall.WSAECONNABORTED, "ECONNABORTED"},
	{syscall.Errno(10065), "EHOSTUNREACH"}, // WSAEHOSTUNREACH
	{syscall.Errno(10051), "ENETUNREACH"},  // WSAENETUNREACH
}
