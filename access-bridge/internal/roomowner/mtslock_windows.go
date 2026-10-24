//go:build windows

package roomowner

import (
	"os"
	"syscall"
	"unsafe"
)

var mtsLockFileEx = syscall.NewLazyDLL("kernel32.dll").NewProc("LockFileEx")

func mtsTryLock(f *os.File) (bool, error) {
	var ov syscall.Overlapped
	result, _, err := mtsLockFileEx.Call(f.Fd(), 3, 0, 1, 0, uintptr(unsafe.Pointer(&ov))) // EXCLUSIVE | FAIL_IMMEDIATELY
	if result != 0 {
		return true, nil
	}
	if err == syscall.Errno(33) {
		return false, nil
	} // ERROR_LOCK_VIOLATION
	return false, err
}
