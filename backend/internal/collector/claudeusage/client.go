package claudeusage

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"ai-quota-monitor/backend/internal/quota"
)

const (
	usageURL       = "https://api.anthropic.com/api/oauth/usage"
	userAgent      = "claude-code/2.1.85"
	anthropicBeta  = "oauth-2025-04-20"
	maxResponseLen = 4 << 20
)

// Client fetches Claude usage over HTTPS. HTTP is injectable for tests.
type Client struct {
	HTTP *http.Client
	URL  string
}

func (c *Client) httpClient() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return &http.Client{Timeout: 25 * time.Second}
}

func (c *Client) url() string {
	if c.URL != "" {
		return c.URL
	}
	return usageURL
}

// Fetch queries the usage endpoint and returns a normalized provider snapshot.
func (c *Client) Fetch(ctx context.Context, accessToken string) (*quota.Provider, error) {
	if accessToken == "" {
		return nil, fmt.Errorf("claude: empty access token")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.url(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("anthropic-beta", anthropicBeta)

	resp, err := c.httpClient().Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseLen))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("claude: usage endpoint returned %d", resp.StatusCode)
	}

	windows, err := ParseUsage(body)
	if err != nil {
		return nil, fmt.Errorf("claude: parse usage: %w", err)
	}
	return &quota.Provider{
		Provider:  "claude",
		Status:    "ok",
		Source:    "oauth",
		UpdatedAt: time.Now().UTC(),
		Windows:   windows,
	}, nil
}
