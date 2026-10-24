package node

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

type fakeHooks struct {
	mu        sync.Mutex
	renewed   []string
	closed    []string
	renewErr  error
	renewedCh chan string
}

func (h *fakeHooks) Renew(_ context.Context, id string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.renewed = append(h.renewed, id)
	if h.renewedCh != nil {
		h.renewedCh <- id
	}
	return h.renewErr
}

func (h *fakeHooks) Close(_ context.Context, id string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.closed = append(h.closed, id)
	return nil
}

func (h *fakeHooks) snapshot() (renewed, closed []string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]string(nil), h.renewed...), append([]string(nil), h.closed...)
}

type fakeControl struct {
	mu      sync.Mutex
	closed  []string
	onClose func(string)
	entered chan struct{}
	gate    <-chan struct{}
}

func (c *fakeControl) CloseSession(id string) bool {
	c.mu.Lock()
	c.closed = append(c.closed, id)
	c.mu.Unlock()
	if c.entered != nil {
		c.entered <- struct{}{}
	}
	if c.gate != nil {
		<-c.gate
	}
	if c.onClose != nil {
		c.onClose(id)
	}
	return true
}

func (c *fakeControl) snapshot() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.closed...)
}

func nextSchedule(t *testing.T, scheduled <-chan time.Duration) time.Duration {
	t.Helper()
	select {
	case d := <-scheduled:
		return d
	case <-time.After(time.Second):
		t.Fatal("renewal was not scheduled")
		return 0
	}
}

func TestRelayLifecycleRetriesBeforeShortLeaseCutoff(t *testing.T) {
	ticks := make(chan time.Time, 1)
	scheduled := make(chan time.Duration, 3)
	renewed := make(chan string, 1)
	hooks := &fakeHooks{renewErr: personal.ErrNodeUnavailable, renewedCh: renewed}
	l := NewRelayLifecycle(context.Background(), hooks, &fakeControl{})
	l.newTimer = func(d time.Duration) (<-chan time.Time, func()) { scheduled <- d; return ticks, func() {} }
	l.OnOpen("short_session")
	if d := nextSchedule(t, scheduled); d != 15*time.Second {
		t.Fatalf("first refresh = %v", d)
	}
	ticks <- time.Now()
	<-renewed
	if d := nextSchedule(t, scheduled); d != 5*time.Second {
		t.Fatalf("retry outlives short lease = %v", d)
	}
	l.OnClose("short_session")
}

func TestLifecycleRenewsOpenSessionAndStopsAfterClose(t *testing.T) {
	ticks := make(chan time.Time, 1)
	scheduled := make(chan time.Duration, 2)
	renewedCh := make(chan string, 1)
	hooks, control := &fakeHooks{renewedCh: renewedCh}, &fakeControl{}
	l := NewLifecycle(context.Background(), hooks, control, 2*time.Minute)
	l.newTimer = func(d time.Duration) (<-chan time.Time, func()) { scheduled <- d; return ticks, func() {} }
	l.OnOpen("session_one")
	if got := nextSchedule(t, scheduled); got != 2*time.Minute {
		t.Fatalf("first renewal = %s", got)
	}
	ticks <- time.Now()
	select {
	case id := <-renewedCh:
		if id != "session_one" {
			t.Fatalf("renewed %q", id)
		}
	case <-time.After(time.Second):
		t.Fatal("opened session was not renewed on tick")
	}
	l.mu.Lock()
	done := l.active["session_one"].done
	l.mu.Unlock()
	l.OnClose("session_one")
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("close did not stop renewal loop")
	}
	_, closed := hooks.snapshot()
	if len(closed) != 1 || closed[0] != "session_one" {
		t.Fatalf("journal closes = %v", closed)
	}
	if got := control.snapshot(); len(got) != 0 {
		t.Fatalf("normal close called native cutoff: %v", got)
	}
}

func TestLifecycleRenewalFailureCutsExactSession(t *testing.T) {
	ticks := make(chan time.Time, 1)
	scheduled := make(chan time.Duration, 3)
	hooks, control := &fakeHooks{renewErr: personal.ErrNodeDenied}, &fakeControl{entered: make(chan struct{}, 1)}
	l := NewLifecycle(context.Background(), hooks, control, 2*time.Minute)
	l.newTimer = func(d time.Duration) (<-chan time.Time, func()) { scheduled <- d; return ticks, func() {} }
	control.onClose = l.OnClose
	l.OnOpen("denied_session")
	if got := nextSchedule(t, scheduled); got != 2*time.Minute {
		t.Fatalf("first renewal = %s", got)
	}
	ticks <- time.Now()
	select {
	case <-control.entered:
	case <-time.After(time.Second):
		t.Fatal("renewal denial did not cut session")
	}
	if got := control.snapshot(); len(got) != 1 || got[0] != "denied_session" {
		t.Fatalf("wrong cutoff: %v", got)
	}
	l.Shutdown()
}

func TestLifecycleTransientOutageRetriesWithoutExtendingOrClosingNativeLease(t *testing.T) {
	ticks := make(chan time.Time, 2)
	scheduled := make(chan time.Duration, 3)
	renewed := make(chan string, 2)
	hooks, control := &fakeHooks{renewErr: personal.ErrNodeUnavailable, renewedCh: renewed}, &fakeControl{}
	l := NewLifecycle(context.Background(), hooks, control, 2*time.Minute)
	l.newTimer = func(d time.Duration) (<-chan time.Time, func()) { scheduled <- d; return ticks, func() {} }
	l.OnOpen("test_session")
	if got := nextSchedule(t, scheduled); got != 2*time.Minute {
		t.Fatalf("first renewal = %s", got)
	}
	ticks <- time.Now()
	if got := <-renewed; got != "test_session" {
		t.Fatalf("renewed %q", got)
	}
	if got := nextSchedule(t, scheduled); got != 25*time.Second {
		t.Fatalf("transient retry = %s", got)
	}
	if got := control.snapshot(); len(got) != 0 {
		t.Fatalf("transient outage cut native session: %v", got)
	}
	hooks.mu.Lock()
	hooks.renewErr = nil
	hooks.mu.Unlock()
	ticks <- time.Now()
	<-renewed
	if got := nextSchedule(t, scheduled); got != 2*time.Minute {
		t.Fatalf("healthy renewal schedule = %s", got)
	}
	l.OnClose("test_session")
}

func TestLifecycleShutdownClosesExactActiveSessionsAndJournals(t *testing.T) {
	hooks, control := &fakeHooks{}, &fakeControl{}
	l := NewLifecycle(context.Background(), hooks, control, 2*time.Minute)
	control.onClose = l.OnClose
	l.OnOpen("session_alpha")
	l.OnOpen("session_beta")
	l.OnClose("session_alpha")
	l.Shutdown()
	l.Shutdown() // signal handler and Run-return cleanup may race
	if got := control.snapshot(); len(got) != 1 || got[0] != "session_beta" {
		t.Fatalf("shutdown cutoff = %v, want only session_beta", got)
	}
	_, closed := hooks.snapshot()
	if len(closed) != 2 || closed[0] != "session_alpha" || closed[1] != "session_beta" {
		t.Fatalf("journal closes = %v", closed)
	}
	l.OnOpen("too_late")
	if got := control.snapshot(); len(got) != 2 || got[1] != "too_late" {
		t.Fatalf("post-shutdown join was not cut: %v", got)
	}
}

func TestLifecycleConcurrentShutdownCutsEachSessionOnce(t *testing.T) {
	gate := make(chan struct{})
	control := &fakeControl{entered: make(chan struct{}, 2), gate: gate}
	l := NewLifecycle(context.Background(), &fakeHooks{}, control, 2*time.Minute)
	l.OnOpen("one_session")
	firstDone := make(chan struct{})
	go func() { l.Shutdown(); close(firstDone) }()
	<-control.entered // first shutdown is inside native cutoff
	secondDone := make(chan struct{})
	go func() { l.Shutdown(); close(secondDone) }()
	select {
	case <-secondDone:
	case <-time.After(time.Second):
		close(gate)
		t.Fatal("second shutdown repeated or blocked on native cutoff")
	}
	close(gate)
	<-firstDone
	if got := control.snapshot(); len(got) != 1 || got[0] != "one_session" {
		t.Fatalf("concurrent shutdown cutoffs = %v", got)
	}
}

func TestLifecycleShutdownStartsEveryExactCutoffBeforeWaitingForJournal(t *testing.T) {
	gate := make(chan struct{})
	control := &fakeControl{entered: make(chan struct{}, 2), gate: gate}
	l := NewLifecycle(context.Background(), &fakeHooks{}, control, 2*time.Minute)
	control.onClose = l.OnClose
	l.OnOpen("first_session")
	l.OnOpen("second_session")
	done := make(chan struct{})
	go func() { l.Shutdown(); close(done) }()
	<-control.entered
	select {
	case <-control.entered:
	case <-time.After(time.Second):
		close(gate)
		t.Fatal("second native session was not cut until first cutoff returned")
	}
	close(gate)
	<-done
	if got := control.snapshot(); len(got) != 2 {
		t.Fatalf("shutdown exact cutoffs = %v", got)
	}
}
