package claudedesktop

import (
	"encoding/json"
	"strconv"
)

// normalizeExpiry converts a Claude expiresAt value, which may be unix seconds,
// unix milliseconds, or a numeric string, into unix seconds. 0 means unknown.
func normalizeExpiry(value any) int64 {
	var f float64
	switch v := value.(type) {
	case float64:
		f = v
	case json.Number:
		parsed, err := v.Float64()
		if err != nil {
			return 0
		}
		f = parsed
	case int64:
		f = float64(v)
	case string:
		parsed, err := strconv.ParseFloat(v, 64)
		if err != nil {
			return 0
		}
		f = parsed
	default:
		return 0
	}
	if f > 1e12 {
		return int64(f / 1000)
	}
	return int64(f)
}
