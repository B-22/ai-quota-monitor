//go:build !windows

package claudedesktop

func desktopDir() (string, error) {
	return "", errNoDesktopDir
}
