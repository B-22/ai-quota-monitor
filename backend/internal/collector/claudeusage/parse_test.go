package claudeusage

import (
	"testing"

	"ai-quota-monitor/backend/internal/quota"
)

func TestParseUsageLegacyWindows(t *testing.T) {
	body := []byte(`{
		"five_hour": {"utilization": 12.6, "resets_at": "2026-05-18T05:00:00Z"},
		"seven_day": {"utilization": 20.0, "resets_at": "2026-05-25T00:00:00Z"}
	}`)

	windows, err := ParseUsage(body)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(windows) != 2 {
		t.Fatalf("got %d windows, want 2", len(windows))
	}
	assertWindow(t, windows[0], quota.KindSession, 13, 87)
	assertWindow(t, windows[1], quota.KindWeekly, 20, 80)
	if windows[0].ResetsAt == nil || windows[0].ResetsAt.UTC().Hour() != 5 {
		t.Fatalf("session resets_at not parsed: %+v", windows[0].ResetsAt)
	}
}

func TestParseUsageLimitsArray(t *testing.T) {
	body := []byte(`{
		"limits": [
			{"kind": "session", "percent": 42.4, "resets_at": "2026-05-18T05:00:00Z"},
			{"kind": "weekly_all", "percent": 20},
			{"kind": "weekly_scoped", "percent": 66, "scope": {"model": {"display_name": "Claude Fable"}}},
			{"kind": "weekly_scoped", "percent": 10, "scope": {"model": {"display_name": "Fable"}}}
		]
	}`)

	windows, err := ParseUsage(body)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(windows) != 3 {
		t.Fatalf("got %d windows, want 3", len(windows))
	}
	assertWindow(t, windows[0], quota.KindSession, 42, 58)
	assertWindow(t, windows[1], quota.KindWeekly, 20, 80)
	assertWindow(t, windows[2], quota.KindWeekly, 66, 34)
	if windows[2].Label != "Fable" {
		t.Fatalf("fable label = %q", windows[2].Label)
	}
}

func TestParseUsageClampsAboveHundred(t *testing.T) {
	body := []byte(`{"limits":[{"kind":"session","percent":150}]}`)
	windows, err := ParseUsage(body)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	assertWindow(t, windows[0], quota.KindSession, 100, 0)
}

func TestParseUsageIgnoresNonFableScoped(t *testing.T) {
	body := []byte(`{"limits":[{"kind":"weekly_scoped","percent":50,"scope":{"model":{"display_name":"Opus"}}}]}`)
	windows, err := ParseUsage(body)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(windows) != 0 {
		t.Fatalf("got %d windows, want 0", len(windows))
	}
}

func assertWindow(t *testing.T, w quota.Window, kind string, used, remaining float64) {
	t.Helper()
	if w.Kind != kind {
		t.Fatalf("kind = %q, want %q", w.Kind, kind)
	}
	if w.UsedPercent == nil || *w.UsedPercent != used {
		t.Fatalf("usedPercent = %v, want %v", w.UsedPercent, used)
	}
	if w.RemainingPercent == nil || *w.RemainingPercent != remaining {
		t.Fatalf("remainingPercent = %v, want %v", w.RemainingPercent, remaining)
	}
}
