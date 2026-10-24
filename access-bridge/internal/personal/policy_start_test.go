package personal

import (
	"context"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

type delayedPolicyPanel struct {
	panel *fakePanel
	clock *time.Time
}

func (p delayedPolicyPanel) LookupByID(ctx context.Context, id int64) (remnawave.UserSnapshot, error) {
	*p.clock = p.clock.Add(4 * time.Second)
	return p.panel.LookupByID(ctx, id)
}

func TestShortAdmissionLeaseIncludesCanonicalLookupLatency(t *testing.T) {
	s, claims, panel, state, now, _ := testService(t)
	start := *now
	s.config.LeaseTTL = 30 * time.Second
	s.panel = delayedPolicyPanel{panel, now}
	l, err := s.Admit(context.Background(), claims)
	if err != nil {
		t.Fatal(err)
	}
	if !l.Deadline.Equal(start.Add(30*time.Second)) || !state.last.Deadline.Equal(l.Deadline) || !state.last.ObservedAt.Equal(start) {
		t.Fatalf("lookup latency extended durable lease: end=%v observation=%v", l.Deadline.Sub(start), state.last.ObservedAt.Sub(start))
	}
}

func TestShortRenewalLeaseIncludesCanonicalLookupLatency(t *testing.T) {
	s, claims, panel, state, now, _ := testService(t)
	s.config.LeaseTTL = 30 * time.Second
	l, err := s.Admit(context.Background(), claims)
	if err != nil {
		t.Fatal(err)
	}
	*now = now.Add(15 * time.Second)
	start := *now
	s.panel = delayedPolicyPanel{panel, now}
	renewed, err := s.Renew(context.Background(), l.SessionID)
	if err != nil {
		t.Fatal(err)
	}
	if !renewed.Deadline.Equal(start.Add(30*time.Second)) || !state.lastRenew.NewDeadline.Equal(renewed.Deadline) || !state.lastRenew.ObservedAt.Equal(start) {
		t.Fatalf("renew lookup latency extended durable lease: end=%v observation=%v", renewed.Deadline.Sub(start), state.lastRenew.ObservedAt.Sub(start))
	}
}
