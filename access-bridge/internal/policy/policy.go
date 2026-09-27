// Package policy evaluates a redacted Remnawave user snapshot without I/O.
// Decisions are presentation facts, never credentials or server grants.
package policy

import (
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

type Reason string

const (
	ReasonSubscriptionUnavailable Reason = "SUBSCRIPTION_UNAVAILABLE"
	ReasonSubscriptionDisabled    Reason = "SUBSCRIPTION_DISABLED"
	ReasonSubscriptionLimited     Reason = "SUBSCRIPTION_LIMITED"
	ReasonSubscriptionExpired     Reason = "SUBSCRIPTION_EXPIRED"
	ReasonTrafficExhausted        Reason = "TRAFFIC_EXHAUSTED"
	ReasonOlcRTCDisabled          Reason = "OLCRTC_DISABLED"
	ReasonEnforcementUnavailable  Reason = "ENFORCEMENT_UNAVAILABLE"
)

// ProtocolFlag must be retrieved using the canonical (panel instance, numeric
// user ID) subject. Its zero value represents an absent, disabled flag.
type ProtocolFlag struct {
	Enabled bool
}

type Decision struct {
	Status             string
	Reason             Reason
	ExpiresAt          time.Time
	TotalLimitBytes    int64
	ConfirmedUsedBytes int64
	RemainingBytes     *int64 // Nil denotes an unlimited subscription.
	OlcRTCEnabled      bool
	OrdinaryAllowed    bool // Informational; this module never controls ordinary hosts.
	AlternateAllowed   bool // Never authorizes a server without an independent grant.
}

// Evaluate has no clock, database or network access. An unavailable fact denies
// the alternate transport; a Bridge outage must not revoke ordinary Xray.
func Evaluate(now time.Time, user remnawave.UserSnapshot, flag ProtocolFlag) Decision {
	decision := Decision{
		Status:             string(user.Status),
		ExpiresAt:          user.ExpiresAt,
		TotalLimitBytes:    max(user.TrafficLimitBytes, 0),
		ConfirmedUsedBytes: max(user.UsedTrafficBytes, 0),
		OlcRTCEnabled:      flag.Enabled,
	}
	if user.TrafficLimitBytes > 0 && user.UsedTrafficBytes >= 0 {
		remaining := max(user.TrafficLimitBytes-user.UsedTrafficBytes, 0)
		decision.RemainingBytes = &remaining
	}
	if now.IsZero() || user.ID <= 0 || user.ExpiresAt.IsZero() || user.TrafficLimitBytes < 0 || user.UsedTrafficBytes < 0 || !knownStatus(user.Status) || !knownStrategy(user.TrafficLimitStrategy) {
		decision.Status = "UNAVAILABLE"
		decision.Reason = ReasonSubscriptionUnavailable
		return decision
	}

	switch user.Status {
	case remnawave.StatusDisabled:
		decision.Reason = ReasonSubscriptionDisabled
		return decision
	case remnawave.StatusLimited:
		decision.Reason = ReasonSubscriptionLimited
		return decision
	case remnawave.StatusExpired:
		decision.Reason = ReasonSubscriptionExpired
		return decision
	}
	if !now.Before(user.ExpiresAt) {
		decision.Status = string(remnawave.StatusExpired)
		decision.Reason = ReasonSubscriptionExpired
		return decision
	}
	if user.TrafficLimitBytes > 0 && user.UsedTrafficBytes >= user.TrafficLimitBytes {
		decision.Status = string(remnawave.StatusLimited)
		decision.Reason = ReasonTrafficExhausted
		return decision
	}
	decision.OrdinaryAllowed = true
	if !flag.Enabled {
		decision.Reason = ReasonOlcRTCDisabled
		return decision
	}

	// The foundation lacks admission, metering and Xray-side enforcement. This
	// must stay false until those independent gates are verified end to end.
	const enforcementReady = false
	decision.AlternateAllowed = enforcementReady
	decision.Reason = ReasonEnforcementUnavailable
	return decision
}

func knownStatus(status remnawave.Status) bool {
	switch status {
	case remnawave.StatusActive, remnawave.StatusDisabled, remnawave.StatusLimited, remnawave.StatusExpired:
		return true
	default:
		return false
	}
}

func knownStrategy(strategy remnawave.TrafficStrategy) bool {
	switch strategy {
	case remnawave.StrategyNoReset, remnawave.StrategyDay, remnawave.StrategyWeek, remnawave.StrategyMonth, remnawave.StrategyMonthRolling:
		return true
	default:
		return false
	}
}
