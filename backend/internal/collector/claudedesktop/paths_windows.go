//go:build windows

package claudedesktop

import (
	"os"
	"path/filepath"
)

// desktopDir locates the Claude Desktop data directory: the MSIX (Store)
// package cache first, then the classic %APPDATA%\Claude install.
func desktopDir() (string, error) {
	if local := os.Getenv("LOCALAPPDATA"); local != "" {
		matches, _ := filepath.Glob(filepath.Join(local, "Packages", "Claude_*"))
		for _, match := range matches {
			dir := filepath.Join(match, "LocalCache", "Roaming", "Claude")
			if fileExists(filepath.Join(dir, "config.json")) {
				return dir, nil
			}
		}
	}
	if appdata := os.Getenv("APPDATA"); appdata != "" {
		dir := filepath.Join(appdata, "Claude")
		if fileExists(filepath.Join(dir, "config.json")) {
			return dir, nil
		}
	}
	return "", errNoDesktopDir
}
