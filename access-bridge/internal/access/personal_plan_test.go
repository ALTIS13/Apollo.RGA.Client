package access

import (
	"errors"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

func testPersonalPlanFacts(now time.Time) (PersonalTestConfig, PersonalTestFacts) {
	subject := Subject{PanelInstance: "rga-main", UserID: 42}
	binding := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: [32]byte{1}}
	config := PersonalTestConfig{
		Subject: subject, Installation: binding, RoomID: "test-room-000001", Country: "NL",
		LeaseTTL: 5 * time.Minute, MaxSnapshotAge: 15 * time.Second,
		SessionByteCap: 16 << 30,
	}
	facts := PersonalTestFacts{
		State:             SubjectState{Subject: subject, OlcRTCEnabled: true, Revision: 2},
		BoundInstallation: binding,
		User: remnawave.UserSnapshot{
			ID: 42, Status: remnawave.StatusActive, ExpiresAt: now.Add(time.Hour),
			TrafficLimitBytes: 1000, TrafficLimitStrategy: remnawave.StrategyNoReset,
			UsedTrafficBytes: 100,
		},
		ObservedAt: now,
	}
	return config, facts
}

func TestPersonalTestPlanRequiresExactFreshServerFacts(t *testing.T) {
	now := time.Date(2026, 9, 28, 0, 0, 0, 0, time.UTC)
	config, facts := testPersonalPlanFacts(now)
	plan, err := PlanPersonalTestSession(now, config, facts)
	if err != nil {
		t.Fatalf("valid personal facts denied: %v", err)
	}
	if plan.Subject != config.Subject || plan.Installation != config.Installation || plan.RoomID != config.RoomID ||
		plan.Country != config.Country || plan.PolicyRevision != facts.State.Revision ||
		plan.ByteCap != config.SessionByteCap || !plan.Deadline.Equal(now.Add(5*time.Minute)) {
		t.Fatalf("wrong non-authorizing plan: %+v", plan)
	}
	checks := []struct {
		name   string
		mutate func(*PersonalTestConfig, *PersonalTestFacts)
	}{
		{"another-subject", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.State.UserID++ }},
		{"another-installation", func(_ *PersonalTestConfig, f *PersonalTestFacts) {
			f.BoundInstallation.ID = "test_installation_other_01"
		}},
		{"another-key", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.BoundInstallation.PublicKeySHA256[0]++ }},
		{"another-user", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.User.ID++ }},
		{"disabled", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.State.OlcRTCEnabled = false }},
		{"missing-revision", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.State.Revision = 0 }},
		{"stale", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.ObservedAt = now.Add(-16 * time.Second) }},
		{"future", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.ObservedAt = now.Add(time.Second) }},
		{"disabled-subscription", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.User.Status = remnawave.StatusDisabled }},
		{"expired-subscription", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.User.ExpiresAt = now }},
		{"exhausted-subscription", func(_ *PersonalTestConfig, f *PersonalTestFacts) { f.User.UsedTrafficBytes = f.User.TrafficLimitBytes }},
		{"wrong-room-format", func(c *PersonalTestConfig, _ *PersonalTestFacts) { c.RoomID = "https://example.invalid/room" }},
		{"wrong-country", func(c *PersonalTestConfig, _ *PersonalTestFacts) { c.Country = "nl" }},
		{"overlong-lease", func(c *PersonalTestConfig, _ *PersonalTestFacts) { c.LeaseTTL = 6 * time.Minute }},
		{"zero-cap", func(c *PersonalTestConfig, _ *PersonalTestFacts) { c.SessionByteCap = 0 }},
	}
	for _, tc := range checks {
		t.Run(tc.name, func(t *testing.T) {
			c, f := testPersonalPlanFacts(now)
			tc.mutate(&c, &f)
			if _, err := PlanPersonalTestSession(now, c, f); !errors.Is(err, ErrPersonalTestDenied) {
				t.Fatalf("invalid facts accepted: %v", err)
			}
		})
	}
}

func TestPersonalTestPlanEndsAtSubscriptionExpiryAndDoesNotSubtractDiagnosticBytes(t *testing.T) {
	now := time.Date(2026, 9, 28, 0, 0, 0, 0, time.UTC)
	config, facts := testPersonalPlanFacts(now)
	facts.User.ExpiresAt = now.Add(2 * time.Minute)
	plan, err := PlanPersonalTestSession(now, config, facts)
	if err != nil || !plan.Deadline.Equal(facts.User.ExpiresAt) {
		t.Fatalf("expiry not capped: %+v, %v", plan, err)
	}
	// The diagnostic journal is deliberately absent from this decision. This
	// is a personal test branch, not a shared Remnawave traffic calculation.
	facts.User.TrafficLimitBytes = 0
	facts.User.UsedTrafficBytes = 10 << 30
	plan, err = PlanPersonalTestSession(now, config, facts)
	if err != nil || plan.ByteCap != config.SessionByteCap {
		t.Fatalf("unlimited Remnawave subscription was incorrectly capped: %+v, %v", plan, err)
	}
}
