// Package claudeusage queries the Anthropic OAuth usage endpoint with a
// Desktop access token and normalizes the response into quota windows.
package claudeusage

import (
	"encoding/json"
	"math"
	"strings"
	"time"

	"ai-quota-monitor/backend/internal/quota"
)

type apiWindow struct {
	Utilization *float64 `json:"utilization"`
	Percent     *float64 `json:"percent"`
	ResetsAt    *string  `json:"resets_at"`
}

type apiLimit struct {
	Kind     string   `json:"kind"`
	Percent  *float64 `json:"percent"`
	ResetsAt *string  `json:"resets_at"`
	Scope    *struct {
		Model *struct {
			DisplayName string `json:"display_name"`
		} `json:"model"`
	} `json:"scope"`
}

type apiUsage struct {
	FiveHour *apiWindow `json:"five_hour"`
	SevenDay *apiWindow `json:"seven_day"`
	Limits   []apiLimit `json:"limits"`
}

// ParseUsage turns a /api/oauth/usage body into normalized windows. Window
// order is stable: session, weekly, then the Fable weekly cap when present.
func ParseUsage(body []byte) ([]quota.Window, error) {
	var usage apiUsage
	if err := json.Unmarshal(body, &usage); err != nil {
		return nil, err
	}

	var session, weekly, fable *quota.Window
	if w, ok := legacyWindow(quota.KindSession, usage.FiveHour); ok {
		session = &w
	}
	if w, ok := legacyWindow(quota.KindWeekly, usage.SevenDay); ok {
		weekly = &w
	}

	for i := range usage.Limits {
		entry := &usage.Limits[i]
		if entry.Percent == nil {
			continue
		}
		switch entry.Kind {
		case "session":
			if session == nil {
				w := newWindow(quota.KindSession, "", entry.Percent, entry.ResetsAt)
				session = &w
			}
		case "weekly_all":
			if weekly == nil {
				w := newWindow(quota.KindWeekly, "", entry.Percent, entry.ResetsAt)
				weekly = &w
			}
		case "weekly_scoped":
			name := ""
			if entry.Scope != nil && entry.Scope.Model != nil {
				name = entry.Scope.Model.DisplayName
			}
			if !strings.Contains(strings.ToLower(name), "fable") {
				continue
			}
			candidate := newWindow(quota.KindWeekly, "Fable", entry.Percent, entry.ResetsAt)
			if fable == nil || used(candidate) > used(*fable) {
				fable = &candidate
			}
		}
	}

	windows := make([]quota.Window, 0, 3)
	for _, w := range []*quota.Window{session, weekly, fable} {
		if w != nil {
			windows = append(windows, *w)
		}
	}
	return windows, nil
}

func legacyWindow(kind string, w *apiWindow) (quota.Window, bool) {
	if w == nil {
		return quota.Window{}, false
	}
	percent := w.Percent
	if percent == nil {
		percent = w.Utilization
	}
	if percent == nil {
		return quota.Window{}, false
	}
	return newWindow(kind, "", percent, w.ResetsAt), true
}

func newWindow(kind, label string, percent *float64, resetsAt *string) quota.Window {
	bounded := clamp(*percent)
	usedPercent := math.Round(bounded)
	remaining := 100 - usedPercent
	w := quota.Window{
		Kind:             kind,
		Label:            label,
		UsedPercent:      &usedPercent,
		RemainingPercent: &remaining,
	}
	if resetsAt != nil {
		if t, err := time.Parse(time.RFC3339, *resetsAt); err == nil {
			w.ResetsAt = &t
		}
	}
	return w
}

func clamp(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 100 {
		return 100
	}
	return v
}

func used(w quota.Window) float64 {
	if w.UsedPercent == nil {
		return 0
	}
	return *w.UsedPercent
}
