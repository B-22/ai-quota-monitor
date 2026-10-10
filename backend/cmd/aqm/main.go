// Command aqm is the single collector/hub executable for AI Quota Monitor.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"ai-quota-monitor/backend/internal/collector/claudedesktop"
	"ai-quota-monitor/backend/internal/collector/claudeusage"
)

func main() {
	mode := flag.String("mode", "collector", "run mode: collector or hub")
	flag.Parse()

	var err error
	switch *mode {
	case "collector":
		err = runCollector()
	case "hub":
		err = fmt.Errorf("hub mode is not implemented yet")
	default:
		fmt.Fprintf(os.Stderr, "unknown mode %q (want collector or hub)\n", *mode)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "aqm:", err)
		os.Exit(1)
	}
}

func runCollector() error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	token, err := claudedesktop.GetDesktopToken()
	if err != nil {
		return err
	}
	provider, err := (&claudeusage.Client{}).Fetch(ctx, token.AccessToken)
	if err != nil {
		return err
	}

	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	return encoder.Encode(provider)
}
