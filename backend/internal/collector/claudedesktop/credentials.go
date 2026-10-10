package claudedesktop

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// claudeClientID is the OAuth client Claude Desktop uses for its own account.
const claudeClientID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

// DesktopToken is the access token read from the Desktop caches.
type DesktopToken struct {
	AccessToken string
	// ExpiresAt is unix seconds, or 0 when the cache carried no expiry.
	ExpiresAt int64
}

type localState struct {
	OSCrypt struct {
		EncryptedKey string `json:"encrypted_key"`
	} `json:"os_crypt"`
}

type tokenCandidate struct {
	accessToken string
	expiresAt   int64
	clientID    string
	isV2        bool
}

// GetDesktopToken reads and decrypts the Claude Desktop OAuth token cache.
func GetDesktopToken() (*DesktopToken, error) {
	dir, err := desktopDir()
	if err != nil {
		return nil, err
	}
	key, err := safeStorageKey(dir)
	if err != nil {
		return nil, err
	}
	raw, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err != nil {
		return nil, err
	}
	var config map[string]json.RawMessage
	if err := json.Unmarshal(raw, &config); err != nil {
		return nil, fmt.Errorf("parse config.json: %w", err)
	}

	var candidates []tokenCandidate
	for _, cache := range []struct {
		key string
		v2  bool
	}{
		{"oauth:tokenCacheV2", true},
		{"oauth:tokenCache", false},
	} {
		rawValue, ok := config[cache.key]
		if !ok {
			continue
		}
		var encoded string
		if err := json.Unmarshal(rawValue, &encoded); err != nil || encoded == "" {
			continue
		}
		plaintext, err := decryptChromiumValue(encoded, key)
		if err != nil {
			continue
		}
		parsed, err := parseTokenCache(plaintext, cache.v2)
		if err != nil {
			continue
		}
		candidates = append(candidates, parsed...)
	}

	best := bestToken(candidates)
	if best == nil {
		return nil, errors.New("no Claude Desktop OAuth token found in config.json")
	}
	return &DesktopToken{AccessToken: best.accessToken, ExpiresAt: best.expiresAt}, nil
}

func safeStorageKey(dir string) ([]byte, error) {
	raw, err := os.ReadFile(filepath.Join(dir, "Local State"))
	if err != nil {
		return nil, err
	}
	var state localState
	if err := json.Unmarshal(raw, &state); err != nil {
		return nil, fmt.Errorf("parse Local State: %w", err)
	}
	if state.OSCrypt.EncryptedKey == "" {
		return nil, errors.New("Local State has no os_crypt.encrypted_key")
	}
	blob, err := base64Decode(state.OSCrypt.EncryptedKey)
	if err != nil {
		return nil, fmt.Errorf("decode encrypted_key: %w", err)
	}
	if len(blob) < 5 || string(blob[:5]) != "DPAPI" {
		return nil, errors.New("encrypted_key is not a DPAPI blob")
	}
	key, err := dpapiUnprotect(blob[5:])
	if err != nil {
		return nil, fmt.Errorf("unprotect key: %w", err)
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("unexpected safe storage key length %d", len(key))
	}
	return key, nil
}

// parseTokenCache extracts candidate tokens from a decrypted cache. Only entries
// scoped to the Anthropic API user profile carry a usable access token.
func parseTokenCache(plaintext []byte, isV2 bool) ([]tokenCandidate, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(plaintext, &root); err != nil {
		return nil, err
	}
	var out []tokenCandidate
	for key, raw := range root {
		if !strings.Contains(key, "api.anthropic.com") || !strings.Contains(key, "user:profile") {
			continue
		}
		var entry struct {
			Token       string `json:"token"`
			AccessToken string `json:"accessToken"`
			ExpiresAt   any    `json:"expiresAt"`
		}
		if err := json.Unmarshal(raw, &entry); err != nil {
			continue
		}
		access := entry.Token
		if access == "" {
			access = entry.AccessToken
		}
		if access == "" {
			continue
		}
		clientID := key
		if i := strings.IndexByte(key, ':'); i >= 0 {
			clientID = key[:i]
		}
		out = append(out, tokenCandidate{
			accessToken: access,
			expiresAt:   normalizeExpiry(entry.ExpiresAt),
			clientID:    clientID,
			isV2:        isV2,
		})
	}
	return out, nil
}

// bestToken prefers the primary Claude client, then the V2 cache, then the
// latest expiry, mirroring the validated C++ reader.
func bestToken(candidates []tokenCandidate) *tokenCandidate {
	if len(candidates) == 0 {
		return nil
	}
	best := candidates[0]
	for _, c := range candidates[1:] {
		if tokenBetter(c, best) {
			best = c
		}
	}
	return &best
}

func tokenBetter(a, b tokenCandidate) bool {
	aPrimary := a.clientID == claudeClientID
	bPrimary := b.clientID == claudeClientID
	if aPrimary != bPrimary {
		return aPrimary
	}
	if a.isV2 != b.isV2 {
		return a.isV2
	}
	return a.expiresAt > b.expiresAt
}
