package claudedesktop

import (
	"errors"
	"os"
)

var errNoDesktopDir = errors.New("no Claude Desktop data directory found")

func fileExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && !info.IsDir()
}
