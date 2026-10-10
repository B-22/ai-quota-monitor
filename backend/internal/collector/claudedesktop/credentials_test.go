package claudedesktop

import "testing"

func TestParseTokenCacheFiltersAndNormalizes(t *testing.T) {
	plaintext := []byte(`{
		"9d1c250a-e61b-44d9-88ed-5944d1962f5e:org:https://api.anthropic.com:user:profile": {"token": "tok-a", "expiresAt": 1700000000000},
		"other-client:org:https://api.anthropic.com:user:profile": {"accessToken": "tok-b", "expiresAt": 1700000000},
		"unrelated:key": {"token": "skip"}
	}`)

	candidates, err := parseTokenCache(plaintext, true)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(candidates) != 2 {
		t.Fatalf("got %d candidates, want 2", len(candidates))
	}
	byToken := map[string]tokenCandidate{}
	for _, c := range candidates {
		byToken[c.accessToken] = c
	}
	if got := byToken["tok-a"]; got.clientID != claudeClientID || got.expiresAt != 1700000000 {
		t.Fatalf("tok-a = %+v", got)
	}
	if _, ok := byToken["tok-b"]; !ok {
		t.Fatal("accessToken fallback field was not read")
	}
}

func TestBestTokenPrefersPrimaryClient(t *testing.T) {
	candidates := []tokenCandidate{
		{accessToken: "other-v2", clientID: "other", isV2: true, expiresAt: 9999999999},
		{accessToken: "primary-old", clientID: claudeClientID, isV2: false, expiresAt: 1},
	}
	if got := bestToken(candidates); got == nil || got.accessToken != "primary-old" {
		t.Fatalf("got %+v, want primary-old", got)
	}
}

func TestBestTokenPrefersV2ThenExpiry(t *testing.T) {
	sameClient := []tokenCandidate{
		{accessToken: "v1-late", clientID: claudeClientID, isV2: false, expiresAt: 200},
		{accessToken: "v2-early", clientID: claudeClientID, isV2: true, expiresAt: 100},
	}
	if got := bestToken(sameClient); got == nil || got.accessToken != "v2-early" {
		t.Fatalf("got %+v, want v2-early", got)
	}

	sameClientV2 := []tokenCandidate{
		{accessToken: "early", clientID: claudeClientID, isV2: true, expiresAt: 100},
		{accessToken: "late", clientID: claudeClientID, isV2: true, expiresAt: 200},
	}
	if got := bestToken(sameClientV2); got == nil || got.accessToken != "late" {
		t.Fatalf("got %+v, want late", got)
	}
}

func TestBestTokenEmpty(t *testing.T) {
	if got := bestToken(nil); got != nil {
		t.Fatalf("got %+v, want nil", got)
	}
}

func TestNormalizeExpiry(t *testing.T) {
	cases := []struct {
		in   any
		want int64
	}{
		{float64(1700000000), 1700000000},
		{float64(1700000000000), 1700000000},
		{"1700000000", 1700000000},
		{nil, 0},
		{"not-a-number", 0},
	}
	for _, tc := range cases {
		if got := normalizeExpiry(tc.in); got != tc.want {
			t.Errorf("normalizeExpiry(%v) = %d, want %d", tc.in, got, tc.want)
		}
	}
}
