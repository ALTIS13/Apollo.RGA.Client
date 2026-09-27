package policy

import (
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

var testNow = time.Date(2026, 9, 27, 12, 0, 0, 0, time.UTC)

func activeUser() remnawave.UserSnapshot {
	return remnawave.UserSnapshot{
		ID:                   42,
		Status:               remnawave.StatusActive,
		ExpiresAt:            testNow.Add(time.Hour),
		TrafficLimitBytes:    1000,
		TrafficLimitStrategy: remnawave.StrategyMonth,
		UsedTrafficBytes:     250,
	}
}

func TestActiveSubscriptionStillDeniesAlternateUntilEnforcementReady(t *testing.T) {
	user := activeUser()
	got := Evaluate(testNow, user, ProtocolFlag{Enabled: true})
	if got.Status != "ACTIVE" || got.Reason != ReasonEnforcementUnavailable || !got.OrdinaryAllowed || !got.OlcRTCEnabled || got.AlternateAllowed {
		t.Fatalf("unsafe active decision: %+v", got)
	}
	if got.ExpiresAt != user.ExpiresAt || got.TotalLimitBytes != 1000 || got.ConfirmedUsedBytes != 250 || got.RemainingBytes == nil || *got.RemainingBytes != 750 {
		t.Fatalf("wrong subscription facts: %+v", got)
	}
}

func TestInactiveStatusesDenyBothModes(t *testing.T) {
	for _, tc := range []struct {
		status remnawave.Status
		reason Reason
	}{
		{remnawave.StatusDisabled, ReasonSubscriptionDisabled},
		{remnawave.StatusLimited, ReasonSubscriptionLimited},
		{remnawave.StatusExpired, ReasonSubscriptionExpired},
	} {
		t.Run(string(tc.status), func(t *testing.T) {
			user := activeUser()
			user.Status = tc.status
			got := Evaluate(testNow, user, ProtocolFlag{Enabled: true})
			if got.OrdinaryAllowed || got.AlternateAllowed || got.Reason != tc.reason || got.Status != string(tc.status) {
				t.Fatalf("inactive status allowed: %+v", got)
			}
		})
	}
}

func TestExactExpiryInstantDeniesBothModes(t *testing.T) {
	user := activeUser()
	user.ExpiresAt = testNow
	got := Evaluate(testNow, user, ProtocolFlag{Enabled: true})
	if got.OrdinaryAllowed || got.AlternateAllowed || got.Reason != ReasonSubscriptionExpired {
		t.Fatalf("expiry boundary allowed: %+v", got)
	}
}

func TestZeroLimitMeansUnlimitedWithNoFabricatedRemaining(t *testing.T) {
	user := activeUser()
	user.TrafficLimitBytes = 0
	got := Evaluate(testNow, user, ProtocolFlag{Enabled: true})
	if !got.OrdinaryAllowed || got.AlternateAllowed || got.RemainingBytes != nil || got.TotalLimitBytes != 0 || got.ConfirmedUsedBytes != 250 {
		t.Fatalf("unlimited subscription misread: %+v", got)
	}
}

func TestMissingFlagDefaultsOlcRTCOffWithoutDisablingOrdinary(t *testing.T) {
	got := Evaluate(testNow, activeUser(), ProtocolFlag{})
	if !got.OrdinaryAllowed || got.OlcRTCEnabled || got.AlternateAllowed || got.Reason != ReasonOlcRTCDisabled {
		t.Fatalf("absent flag misread: %+v", got)
	}
}

func TestExhaustedBytesDenyBothModes(t *testing.T) {
	user := activeUser()
	user.UsedTrafficBytes = 1000
	got := Evaluate(testNow, user, ProtocolFlag{Enabled: true})
	if got.OrdinaryAllowed || got.AlternateAllowed || got.Reason != ReasonTrafficExhausted || got.RemainingBytes == nil || *got.RemainingBytes != 0 {
		t.Fatalf("exhausted quota allowed: %+v", got)
	}
}

func TestUnknownFactsFailClosedForAlternate(t *testing.T) {
	for _, tc := range []struct {
		name   string
		change func(*remnawave.UserSnapshot)
	}{
		{"unknown status", func(u *remnawave.UserSnapshot) { u.Status = "FROZEN" }},
		{"unknown reset strategy", func(u *remnawave.UserSnapshot) { u.TrafficLimitStrategy = "UNKNOWN" }},
		{"missing user ID", func(u *remnawave.UserSnapshot) { u.ID = 0 }},
		{"missing expiry", func(u *remnawave.UserSnapshot) { u.ExpiresAt = time.Time{} }},
		{"negative limit", func(u *remnawave.UserSnapshot) { u.TrafficLimitBytes = -1 }},
		{"negative usage", func(u *remnawave.UserSnapshot) { u.UsedTrafficBytes = -1 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			user := activeUser()
			tc.change(&user)
			got := Evaluate(testNow, user, ProtocolFlag{Enabled: true})
			if got.OrdinaryAllowed || got.AlternateAllowed || got.Status != "UNAVAILABLE" || got.Reason != ReasonSubscriptionUnavailable || got.TotalLimitBytes < 0 || got.ConfirmedUsedBytes < 0 {
				t.Fatalf("unknown facts allowed: %+v", got)
			}
		})
	}
}

func TestUnknownClockDeniesBothModes(t *testing.T) {
	got := Evaluate(time.Time{}, activeUser(), ProtocolFlag{Enabled: true})
	if got.OrdinaryAllowed || got.AlternateAllowed || got.Reason != ReasonSubscriptionUnavailable {
		t.Fatalf("missing clock allowed: %+v", got)
	}
}
