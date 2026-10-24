//go:build !linux && !windows

package roomowner

import "os"

// Unsupported worker platforms fail closed; no in-memory lock substitute.
func mtsTryLock(*os.File) (bool, error) { return false, ErrConfig }
