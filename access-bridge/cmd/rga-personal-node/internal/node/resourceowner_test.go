package node

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

type resourceAdmission func(context.Context, string, map[string]any) (personal.NodeLease, error)

type resourceOwnerTestAPI interface {
	Admit(context.Context, string, map[string]any) (personal.NodeLease, error)
	OnOpen(string)
	OnClose(string)
	Fence()
	JoinWorkers(context.Context) error
}

func resourceLease(id string) personal.NodeLease {
	return personal.NodeLease{SessionID: id, NotAfter: time.Now().Add(time.Minute), ByteLimit: 4096}
}

func resourceFixture(t *testing.T, admit resourceAdmission) (resourceOwnerTestAPI, context.Context, *atomic.Int32, *fakeControl) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	var cancels atomic.Int32
	control := &fakeControl{}
	lifecycle := NewRelayLifecycle(ctx, &fakeHooks{}, control)
	owner := newResourceOwnerForTest(ctx, admit, lifecycle, control, func() { cancels.Add(1); cancel() })
	control.onClose = owner.OnClose
	t.Cleanup(func() { owner.Fence(); cancel(); lifecycle.Shutdown() })
	return owner, ctx, &cancels, control
}

func resourceAdmit(t *testing.T, owner resourceOwnerTestAPI, id string) personal.NodeLease {
	t.Helper()
	lease, err := owner.Admit(context.Background(), id, map[string]any{"opaque": "unchanged"})
	if err != nil || lease.SessionID != id || lease.ByteLimit != 4096 {
		t.Fatalf("admission changed: lease=%+v err=%v", lease, err)
	}
	return lease
}

func resourceStillRunning(t *testing.T, ctx context.Context) {
	t.Helper()
	if ctx.Err() != nil {
		t.Fatal("RESOURCE_OWNER: retired while another exact resource remained")
	}
}

func resourceRetired(t *testing.T, ctx context.Context) {
	t.Helper()
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Fatal("RESOURCE_OWNER: terminal empty generation was not retired")
	}
}

func TestResourceOwnerLastActiveCloseRetiresOnlyItsGeneration(t *testing.T) {
	owner, ctx, cancels, _ := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		return resourceLease(id), nil
	})
	other, otherCtx, _, _ := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		return resourceLease(id), nil
	})
	resourceAdmit(t, other, "other_generation")
	other.OnOpen("other_generation")
	resourceAdmit(t, owner, "active_alpha")
	owner.OnOpen("active_alpha")
	resourceAdmit(t, owner, "active_beta")
	owner.OnOpen("active_beta")
	owner.OnClose("unknown_id")
	owner.OnClose("active_alpha")
	resourceStillRunning(t, ctx)
	owner.OnClose("active_beta")
	resourceRetired(t, ctx)
	owner.OnClose("active_beta")
	if cancels.Load() != 1 {
		t.Fatalf("retirement repeated: %d", cancels.Load())
	}
	resourceStillRunning(t, otherCtx)
}

func TestResourceOwnerPendingAdmissionAndReservedResultProtectCarrier(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	owner, ctx, _, _ := resourceFixture(t, func(_ context.Context, id string, claims map[string]any) (personal.NodeLease, error) {
		if claims["opaque"] != "unchanged" {
			return personal.NodeLease{}, errors.New("claims changed")
		}
		if id == "pending_beta" {
			close(entered)
			<-release
		}
		return resourceLease(id), nil
	})
	resourceAdmit(t, owner, "active_alpha")
	owner.OnOpen("active_alpha")
	result := make(chan error, 1)
	go func() {
		_, err := owner.Admit(context.Background(), "pending_beta", map[string]any{"opaque": "unchanged"})
		result <- err
	}()
	<-entered
	owner.OnClose("active_alpha")
	resourceStillRunning(t, ctx)
	close(release)
	if err := <-result; err != nil {
		t.Fatal(err)
	}
	resourceStillRunning(t, ctx) // returned Home lease is not yet an OnOpen callback
	owner.OnOpen("pending_beta")
	resourceStillRunning(t, ctx)
	owner.OnClose("pending_beta")
	resourceRetired(t, ctx)
}

func TestResourceOwnerFailedPendingCallReleasesOnlyItsOwnReservation(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	owner, ctx, _, _ := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		if id == "failed_pending" {
			close(entered)
			<-release
			return personal.NodeLease{}, personal.ErrNodeUnavailable
		}
		return resourceLease(id), nil
	})
	resourceAdmit(t, owner, "active_alpha")
	owner.OnOpen("active_alpha")
	done := make(chan error, 1)
	go func() { _, err := owner.Admit(context.Background(), "failed_pending", nil); done <- err }()
	<-entered
	owner.OnClose("active_alpha")
	resourceStillRunning(t, ctx)
	close(release)
	if !errors.Is(<-done, personal.ErrNodeUnavailable) {
		t.Fatal("original admission failure lost")
	}
	resourceRetired(t, ctx)
}

func TestResourceOwnerFailedFirstAdmissionDoesNotRetireAndFencedSuccessCannotPublish(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	owner, ctx, _, control := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		calls.Add(1)
		if id == "failed_first" {
			return personal.NodeLease{}, personal.ErrNodeDenied
		}
		close(entered)
		<-release
		return resourceLease(id), nil
	})
	if _, err := owner.Admit(context.Background(), "failed_first", nil); err == nil {
		t.Fatal("failed admission allowed")
	}
	owner.OnClose("unknown_id")
	resourceStillRunning(t, ctx)
	done := make(chan error, 1)
	go func() { _, err := owner.Admit(context.Background(), "late_success", nil); done <- err }()
	<-entered
	owner.Fence()
	close(release)
	if err := <-done; err == nil {
		t.Fatal("RESOURCE_OWNER: fenced late admission published a lease")
	}
	if _, err := owner.Admit(context.Background(), "after_fence", nil); err == nil || calls.Load() != 2 {
		t.Fatal("RESOURCE_OWNER: fenced generation called Home again")
	}
	owner.OnOpen("late_success")
	if got := control.snapshot(); len(got) == 0 || got[len(got)-1] != "late_success" {
		t.Fatal("late native publication was not cut")
	}
}

func TestResourceOwnerUnpublishedReservationExpiresAtOriginalEnd(t *testing.T) {
	end := time.Now().Add(40 * time.Millisecond)
	owner, ctx, _, _ := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		lease := resourceLease(id)
		lease.NotAfter = end
		return lease, nil
	})
	lease := resourceAdmit(t, owner, "reserved_no_open")
	if !lease.NotAfter.Equal(end) {
		t.Fatal("original server lease end changed")
	}
	resourceRetired(t, ctx)
	join, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := owner.JoinWorkers(join); err != nil {
		t.Fatalf("retired reservation scheduler not joined: %v", err)
	}
}

func TestResourceOwnerCanceledRPCDoesNotReleaseSuccessfulUnpublishedLease(t *testing.T) {
	owner, ctx, _, control := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		return resourceLease(id), nil
	})
	rpc, cancel := context.WithCancel(context.Background())
	if _, err := owner.Admit(rpc, "reserved_after_rpc", nil); err != nil {
		t.Fatal(err)
	}
	cancel()
	resourceAdmit(t, owner, "active_alpha")
	owner.OnOpen("active_alpha")
	owner.OnClose("active_alpha")
	resourceStillRunning(t, ctx)
	owner.OnClose("reserved_after_rpc")
	resourceRetired(t, ctx)
	owner.OnOpen("reserved_after_rpc")
	if got := control.snapshot(); len(got) == 0 || got[len(got)-1] != "reserved_after_rpc" {
		t.Fatal("closed reservation was republished")
	}
}

func TestResourceOwnerJoinCannotHideHeldAdmissionAfterFence(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	owner, _, _, _ := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		close(entered)
		<-release
		return resourceLease(id), nil
	})
	done := make(chan struct{})
	go func() { _, _ = owner.Admit(context.Background(), "held_admission", nil); close(done) }()
	<-entered
	owner.Fence()
	before := owner.JoinWorkers(canceledResourceContext())
	close(release)
	<-done
	if before == nil {
		t.Fatal("RESOURCE_JOIN: held Home admission was falsely joined")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := owner.JoinWorkers(ctx); err != nil {
		t.Fatalf("released admission/scheduler not joined: %v", err)
	}
}

func TestResourceOwnerCapacityRejectsBeforeHomeAndRetainsExistingReservations(t *testing.T) {
	var calls atomic.Int32
	owner, ctx, _, _ := resourceFixture(t, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
		calls.Add(1)
		return resourceLease(id), nil
	})
	for i := 0; i < 64; i++ {
		resourceAdmit(t, owner, fmt.Sprintf("reserved_%02d", i))
	}
	if _, err := owner.Admit(context.Background(), "overflow", nil); err == nil || calls.Load() != 64 {
		t.Fatal("RESOURCE_OWNER: reservation capacity did not fence Home call")
	}
	owner.OnClose("reserved_00")
	resourceStillRunning(t, ctx)
}

func TestResourceOwnerUnpublishedExpiryAndFenceCloseExactJournalWithoutOpen(t *testing.T) {
	for _, fence := range []bool{false, true} {
		t.Run(fmt.Sprintf("fence_%t", fence), func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			hooks, control := &fakeHooks{}, &fakeControl{}
			l := NewRelayLifecycle(ctx, hooks, control)
			owner := newResourceOwnerForTest(ctx, func(_ context.Context, id string, _ map[string]any) (personal.NodeLease, error) {
				lease := resourceLease(id)
				if !fence {
					lease.NotAfter = time.Now().Add(40 * time.Millisecond)
				}
				return lease, nil
			}, l, control, cancel)
			defer owner.Fence()
			resourceAdmit(t, owner, "unpublished_journal")
			if fence {
				owner.Fence()
			}
			resourceRetired(t, ctx)
			join, stop := context.WithTimeout(context.Background(), time.Second)
			defer stop()
			if err := owner.JoinWorkers(join); err != nil {
				t.Fatal(err)
			}
			l.Shutdown()
			if err := joinResourceLifecycleForTest(l, join); err != nil {
				t.Fatal(err)
			}
			renewed, closed := hooks.snapshot()
			if len(renewed) != 0 || len(closed) != 1 || closed[0] != "unpublished_journal" {
				t.Fatalf("unpublished reservation lost or fake-opened: renewed=%v closed=%v", renewed, closed)
			}
		})
	}
}

type heldResourceHooks struct {
	renewEntered, renewRelease chan struct{}
	closeEntered, closeRelease chan struct{}
}

func (h *heldResourceHooks) Renew(context.Context, string) error {
	if h.renewEntered != nil {
		close(h.renewEntered)
		<-h.renewRelease
	}
	return personal.ErrNodeUnavailable
}

func (h *heldResourceHooks) Close(context.Context, string) error {
	if h.closeEntered != nil {
		close(h.closeEntered)
		<-h.closeRelease
	}
	return personal.ErrNodeUnavailable // a joined worker does not imply journal acknowledgement
}

func canceledResourceContext() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}

func TestResourceLifecycleJoinDoesNotForgetRemovedRenewal(t *testing.T) {
	h := &heldResourceHooks{renewEntered: make(chan struct{}), renewRelease: make(chan struct{})}
	l := NewRelayLifecycle(context.Background(), h, &fakeControl{})
	ticks := make(chan time.Time, 1)
	l.newTimer = func(time.Duration) (<-chan time.Time, func()) { return ticks, func() {} }
	l.OnOpen("held_renewal")
	ticks <- time.Now()
	<-h.renewEntered
	l.OnClose("held_renewal")
	l.Shutdown()
	before := joinResourceLifecycleForTest(l, canceledResourceContext())
	close(h.renewRelease)
	if before == nil {
		t.Fatal("RESOURCE_JOIN: removed session renewal was falsely joined")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := joinResourceLifecycleForTest(l, ctx); err != nil {
		t.Fatalf("released renewal not joined: %v", err)
	}
}

func TestResourceLifecycleJoinSeparatesHeldJournalFromJournalOutcome(t *testing.T) {
	h := &heldResourceHooks{closeEntered: make(chan struct{}), closeRelease: make(chan struct{})}
	l := NewRelayLifecycle(context.Background(), h, &fakeControl{})
	l.OnOpen("held_journal")
	done := make(chan struct{})
	go func() { l.OnClose("held_journal"); close(done) }()
	<-h.closeEntered
	l.Shutdown()
	before := joinResourceLifecycleForTest(l, canceledResourceContext())
	close(h.closeRelease)
	<-done
	if before == nil {
		t.Fatal("RESOURCE_JOIN: running journal worker was falsely joined")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := joinResourceLifecycleForTest(l, ctx); err != nil {
		t.Fatalf("journal worker joined despite failed journal result: %v", err)
	}
}

func TestResourceLifecycleConcurrentShutdownIsNotJoinCompletion(t *testing.T) {
	release := make(chan struct{})
	control := &fakeControl{entered: make(chan struct{}, 1), gate: release}
	l := NewRelayLifecycle(context.Background(), &fakeHooks{}, control)
	l.OnOpen("held_cutoff")
	done := make(chan struct{})
	go func() { l.Shutdown(); close(done) }()
	<-control.entered
	l.Shutdown() // accepted compatibility: this returns while first shutdown is still working
	before := joinResourceLifecycleForTest(l, canceledResourceContext())
	close(release)
	<-done
	if before == nil {
		t.Fatal("RESOURCE_JOIN: repeated Shutdown was falsely taken as completion")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := joinResourceLifecycleForTest(l, ctx); err != nil {
		t.Fatalf("completed shutdown not joined: %v", err)
	}
}
