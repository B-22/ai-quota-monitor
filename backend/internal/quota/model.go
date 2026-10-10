// Package quota holds the normalized provider-limit snapshot shared by the
// collector and the hub. The JSON shape mirrors the Hub wire contract so a
// collector snapshot can be uploaded unchanged and a hub can serve it.
package quota

import "time"

// Window kinds recognized by the client contract.
const (
	KindSession = "session"
	KindDaily   = "daily"
	KindWeekly  = "weekly"
	KindBilling = "billing"
)

// Window is one quota window on a provider account. Pointers keep "the provider
// did not report this" distinct from an explicit zero.
type Window struct {
	Kind             string     `json:"kind"`
	Label            string     `json:"label,omitempty"`
	UsedPercent      *float64   `json:"usedPercent,omitempty"`
	RemainingPercent *float64   `json:"remainingPercent,omitempty"`
	ResetsAt         *time.Time `json:"resetsAt,omitempty"`
	Additional       bool       `json:"additional,omitempty"`
}

// Provider is one provider account's latest known limits.
type Provider struct {
	Provider  string    `json:"provider"`
	Status    string    `json:"status"`
	Source    string    `json:"source,omitempty"`
	UpdatedAt time.Time `json:"updatedAt"`
	Windows   []Window  `json:"windows"`
}
