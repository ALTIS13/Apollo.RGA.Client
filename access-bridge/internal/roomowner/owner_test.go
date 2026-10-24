package roomowner

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

const fixtureCall = "01234567-89ab-cdef-0123-456789abcdef"
const fixtureLocator = "https://vk.ru/call/join/fixture-room-one"

type fixtureProvider struct {
	created  atomic.Int32
	finished atomic.Int32
	create   func(string) (CreatedRoom, error)
	finish   func(string, string) error
}

func (p *fixtureProvider) Create(_ context.Context, ref string) (CreatedRoom, error) {
	p.created.Add(1)
	if p.create != nil {
		return p.create(ref)
	}
	return CreatedRoom{CallID: fixtureCall, Locator: fixtureLocator}, nil
}
func (p *fixtureProvider) Finish(_ context.Context, ref, id string) error {
	p.finished.Add(1)
	if p.finish != nil {
		return p.finish(ref, id)
	}
	return nil
}

type fixtureAuthority struct{ check func(Binding) error }

func (a fixtureAuthority) Check(_ context.Context, b Binding) error {
	if a.check != nil {
		return a.check(b)
	}
	return nil
}

type fixtureCarrier struct{ cutoff func(Binding) error }

func (c fixtureCarrier) Fence(b Binding) error {
	if c.cutoff != nil {
		return c.cutoff(b)
	}
	return nil
}
func (c fixtureCarrier) Join(ctx context.Context, _ Binding) error { return ctx.Err() }
func fixtureBinding(now time.Time) Binding {
	return Binding{Provider: "vkcalls", Worker: "worker-fixture", Epoch: "epoch-fixture-01",
		Room: "authority-room-01", Route: "route-fixture-nl", PolicyRevision: 7,
		Subject:       access.Subject{PanelInstance: "fixture-panel", UserID: 42},
		Installation:  access.InstallationBinding{ID: "fixture-installation-01", PublicKeySHA256: [32]byte{1}},
		CredentialRef: "organizer-v1", NotAfter: now.Add(time.Minute)}
}
func fixtureOwner(t *testing.T, root string, p *fixtureProvider, now func() time.Time, a fixtureAuthority, c fixtureCarrier) (*Owner, *FileJournal) {
	t.Helper()
	j, err := NewFileJournal(root)
	if err != nil {
		t.Fatal(err)
	}
	o, err := New(j, p, a, c, now)
	if err != nil {
		t.Fatal(err)
	}
	return o, j
}

// Losing the create claim must never create another remote room after restart.
func TestProvisionCommitsRoomBeforePublicationAndDoesNotReplay(t *testing.T) {
	now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
	b := fixtureBinding(now)
	root := t.TempDir()
	p := &fixtureProvider{}
	o, j := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	p.create = func(ref string) (CreatedRoom, error) {
		r, err := j.Load(b)
		if err != nil || r.Phase != Creating || ref != "organizer-v1" {
			t.Fatalf("create before bound durable intent: %v", err)
		}
		return CreatedRoom{fixtureCall, fixtureLocator}, nil
	}
	pub, err := o.Provision(context.Background(), b)
	if err != nil || pub.Binding != b || pub.Locator != fixtureLocator {
		t.Fatalf("publication failed: %v", err)
	}
	r, err := j.Load(b)
	if err != nil || r.Phase != Created || r.Room.CallID != fixtureCall {
		t.Fatal("publication preceded created commit")
	}
	restarted, _ := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	if _, err := restarted.Provision(context.Background(), b); !errors.Is(err, ErrOwned) {
		t.Fatal("replayed/adopted attempt")
	}
	if p.created.Load() != 1 {
		t.Fatal("duplicate provider creation")
	}
}

func TestConcurrentOwnersCreateAtMostOneRoom(t *testing.T) {
	now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
	b := fixtureBinding(now)
	root := t.TempDir()
	p := &fixtureProvider{}
	var wg sync.WaitGroup
	var successes atomic.Int32
	for i := 0; i < 8; i++ {
		o, _ := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := o.Provision(context.Background(), b); err == nil {
				successes.Add(1)
			}
		}()
	}
	wg.Wait()
	if p.created.Load() != 1 || successes.Load() != 1 {
		t.Fatal("concurrent create/publication duplicated")
	}
}

func TestAmbiguousCreationAndOrphanIntentNeverRetry(t *testing.T) {
	for _, orphan := range []bool{false, true} {
		t.Run(map[bool]string{false: "ambiguous", true: "crash-after-intent"}[orphan], func(t *testing.T) {
			now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
			b := fixtureBinding(now)
			root := t.TempDir()
			p := &fixtureProvider{create: func(string) (CreatedRoom, error) { return CreatedRoom{}, errors.New("private response") }}
			o, j := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			if orphan {
				if _, err := j.Claim(b); err != nil {
					t.Fatal(err)
				}
			} else {
				if _, err := o.Provision(context.Background(), b); !errors.Is(err, ErrCreationUnknown) {
					t.Fatal("ambiguous response not quarantined")
				}
			}
			restarted, _ := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			if _, err := restarted.Provision(context.Background(), b); !errors.Is(err, ErrOwned) {
				t.Fatal("orphan retried")
			}
			want := int32(1)
			if orphan {
				want = 0
			}
			if p.created.Load() != want {
				t.Fatal("blind create retry")
			}
		})
	}
}

func TestStaleAuthorityOrDeadlineNeverPublishesCreatedRoom(t *testing.T) {
	for _, expired := range []bool{false, true} {
		t.Run(map[bool]string{false: "revoked", true: "expired"}[expired], func(t *testing.T) {
			now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
			b := fixtureBinding(now)
			checks := 0
			a := fixtureAuthority{check: func(Binding) error {
				checks++
				if checks > 1 && !expired {
					return errors.New("denied")
				}
				return nil
			}}
			p := &fixtureProvider{create: func(string) (CreatedRoom, error) {
				if expired {
					now = b.NotAfter
				}
				return CreatedRoom{fixtureCall, fixtureLocator}, nil
			}}
			o, j := fixtureOwner(t, t.TempDir(), p, func() time.Time { return now }, a, fixtureCarrier{})
			if pub, err := o.Provision(context.Background(), b); err == nil || pub.Locator != "" {
				t.Fatal("stale authority published")
			}
			r, err := j.Load(b)
			if err != nil || r.Phase != Created || r.Room.CallID != fixtureCall {
				t.Fatal("lost owned room needed for cleanup")
			}
		})
	}
}

func TestCloseCutsOffExactCarrierBeforeProviderAndRetainsCredentialRef(t *testing.T) {
	now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
	b := fixtureBinding(now)
	root := t.TempDir()
	stopped := false
	p := &fixtureProvider{}
	c := fixtureCarrier{cutoff: func(got Binding) error {
		if got != b {
			t.Fatal("wrong carrier")
		}
		stopped = true
		return nil
	}}
	o, j := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, c)
	if _, err := o.Provision(context.Background(), b); err != nil {
		t.Fatal(err)
	}
	p.finish = func(ref, id string) error {
		r, err := j.Load(b)
		if err != nil || r.Phase != Closing || !stopped || ref != "organizer-v1" || id != fixtureCall {
			t.Fatal("unowned/early provider finish")
		}
		return nil
	}
	now = b.NotAfter.Add(time.Minute) // Cleanup must not require renewing expired admission.
	if err := o.Close(context.Background(), b); err != nil {
		t.Fatal(err)
	}
	r, err := j.Load(b)
	if err != nil || r.Phase != Closed {
		t.Fatal("provider ACK not committed")
	}
	if err := o.Close(context.Background(), b); err != nil || p.finished.Load() != 1 {
		t.Fatal("closed room finish replayed")
	}
}

func TestUnknownCleanupNeverTurnsIntoProviderACK(t *testing.T) {
	for _, carrierFails := range []bool{false, true} {
		t.Run(map[bool]string{false: "provider-fails", true: "carrier-fails"}[carrierFails], func(t *testing.T) {
			now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
			b := fixtureBinding(now)
			p := &fixtureProvider{finish: func(string, string) error { return errors.New("private provider failure") }}
			c := fixtureCarrier{cutoff: func(Binding) error {
				if carrierFails {
					return errors.New("join unknown")
				}
				return nil
			}}
			o, j := fixtureOwner(t, t.TempDir(), p, func() time.Time { return now }, fixtureAuthority{}, c)
			if _, err := o.Provision(context.Background(), b); err != nil {
				t.Fatal(err)
			}
			if err := o.Close(context.Background(), b); !errors.Is(err, ErrCleanupUnknown) {
				t.Fatal("cleanup failure hidden")
			}
			r, err := j.Load(b)
			if err != nil || r.Phase != CleanupUnknown {
				t.Fatal("unknown cleanup reported closed")
			}
			before := p.finished.Load()
			if err := o.Close(context.Background(), b); !errors.Is(err, ErrCleanupUnknown) || p.finished.Load() != before {
				t.Fatal("unknown finish replayed")
			}
			if carrierFails && before != 0 {
				t.Fatal("provider finish preceded carrier cutoff")
			}
		})
	}
}

func TestBindingDriftAndCorruptJournalCannotOwnAnotherRoom(t *testing.T) {
	now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
	b := fixtureBinding(now)
	root := t.TempDir()
	p := &fixtureProvider{}
	o, _ := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	if _, err := o.Provision(context.Background(), b); err != nil {
		t.Fatal(err)
	}
	changed := b
	changed.Subject.UserID++
	if err := o.Close(context.Background(), changed); err == nil || p.finished.Load() != 0 {
		t.Fatal("subject drift closed a room")
	}
	changed = b
	changed.CredentialRef = "organizer-v2"
	if err := o.Close(context.Background(), changed); err == nil || p.finished.Load() != 0 {
		t.Fatal("credential drift closed a room")
	}
	entries, err := os.ReadDir(root)
	if err != nil || len(entries) != 1 {
		t.Fatal("journal directory missing")
	}
	if err := os.WriteFile(filepath.Join(root, entries[0].Name(), "000001.json"), []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := o.Close(context.Background(), b); err == nil || p.finished.Load() != 0 {
		t.Fatal("corrupt journal accepted")
	}
}

func TestRealClockIntentSurvivesJSONRoundTrip(t *testing.T) {
	now := time.Now() // Includes a monotonic component; JSON deliberately does not.
	b := fixtureBinding(now)
	p := &fixtureProvider{}
	o, _ := fixtureOwner(t, t.TempDir(), p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	if _, err := o.Provision(context.Background(), b); err != nil {
		t.Fatalf("real-clock intent rejected after persistence: %v", err)
	}
	if err := o.Close(context.Background(), b); err != nil {
		t.Fatalf("real-clock intent could not be retired: %v", err)
	}
}

func TestCancelledCloseAndRestartedClosingStillFenceCarrier(t *testing.T) {
	for _, crashed := range []bool{false, true} {
		t.Run(fmt.Sprint(crashed), func(t *testing.T) {
			now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
			b := fixtureBinding(now)
			stopped := false
			p := &fixtureProvider{}
			o, j := fixtureOwner(t, t.TempDir(), p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{cutoff: func(Binding) error { stopped = true; return nil }})
			if _, err := o.Provision(context.Background(), b); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			if crashed {
				r, _ := j.Load(b)
				if _, err := j.Advance(r, Closing, r.Room); err != nil {
					t.Fatal(err)
				}
			} else {
				cancel()
			}
			defer cancel()
			if err := o.Close(ctx, b); !errors.Is(err, ErrCleanupUnknown) || !stopped || p.finished.Load() != 0 {
				t.Fatal("local cutoff skipped or ambiguous provider finish replayed")
			}
			stopped = false
			if err := o.Close(context.Background(), b); !errors.Is(err, ErrCleanupUnknown) || !stopped || p.finished.Load() != 0 {
				t.Fatal("unknown cleanup prevented later local cutoff")
			}
		})
	}
}

type finalLoadJournal struct {
	Journal
	loads int
	final func()
}

func (j *finalLoadJournal) Load(b Binding) (Record, error) {
	r, err := j.Journal.Load(b)
	j.loads++
	if j.loads == 2 {
		j.final()
	}
	return r, err
}

func TestFinalJournalDelayCannotPublishExpiredOrCancelledIntent(t *testing.T) {
	for _, expired := range []bool{false, true} {
		t.Run(fmt.Sprint(expired), func(t *testing.T) {
			now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
			b := fixtureBinding(now)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			p := &fixtureProvider{}
			o, j := fixtureOwner(t, t.TempDir(), p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			o.journal = &finalLoadJournal{Journal: j, final: func() {
				if expired {
					now = b.NotAfter
				} else {
					cancel()
				}
			}}
			if pub, err := o.Provision(ctx, b); err == nil || pub.Locator != "" {
				t.Fatal("late expired/cancelled publication escaped")
			}
		})
	}
}

func TestMissingJournalRevisionCannotReplayFinishOrCreate(t *testing.T) {
	for _, missing := range []string{"000000.json", "000002.json"} {
		t.Run(missing, func(t *testing.T) {
			now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
			b := fixtureBinding(now)
			root := t.TempDir()
			p := &fixtureProvider{}
			stopped := false
			o, _ := fixtureOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{cutoff: func(Binding) error { stopped = true; return nil }})
			if _, err := o.Provision(context.Background(), b); err != nil {
				t.Fatal(err)
			}
			if err := o.Close(context.Background(), b); err != nil {
				t.Fatal(err)
			}
			entries, _ := os.ReadDir(root)
			if err := os.Remove(filepath.Join(root, entries[0].Name(), missing)); err != nil {
				t.Fatal(err)
			}
			if _, err := o.Provision(context.Background(), b); err == nil {
				t.Fatal("damaged attempt recreated")
			}
			stopped = false
			if err := o.Close(context.Background(), b); err == nil || !stopped || p.finished.Load() != 1 || p.created.Load() != 1 {
				t.Fatal("corrupt custody replayed provider action or skipped local fence")
			}
		})
	}
}

func TestCloseDuringCreateFencesImmediatelyAndSuppressesLatePublication(t *testing.T) {
	now := time.Date(2026, 10, 18, 16, 0, 0, 0, time.UTC)
	b := fixtureBinding(now)
	started, release, fenced := make(chan struct{}), make(chan struct{}), make(chan struct{})
	p := &fixtureProvider{create: func(string) (CreatedRoom, error) {
		close(started)
		<-release
		return CreatedRoom{fixtureCall, fixtureLocator}, nil
	}}
	o, _ := fixtureOwner(t, t.TempDir(), p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{cutoff: func(Binding) error { close(fenced); return nil }})
	provisioned := make(chan error, 1)
	go func() {
		pub, err := o.Provision(context.Background(), b)
		if pub.Locator != "" {
			provisioned <- errors.New("late publication after explicit Close")
			return
		}
		provisioned <- err
	}()
	<-started
	closed := make(chan error, 1)
	go func() { closed <- o.Close(context.Background(), b) }()
	select {
	case <-fenced:
	case <-time.After(3 * time.Second):
		close(release)
		t.Fatal("provider request delayed synchronous fencing")
	}
	close(release)
	if err := <-provisioned; !errors.Is(err, ErrDenied) {
		t.Fatalf("cancelled generation published: %v", err)
	}
	if err := <-closed; err != nil || p.finished.Load() != 1 {
		t.Fatal("created-after-Close room not retired")
	}
}
