//go:build !windows

package claudedesktop

import "errors"

func dpapiUnprotect([]byte) ([]byte, error) {
	return nil, errors.New("DPAPI is only available on Windows")
}
