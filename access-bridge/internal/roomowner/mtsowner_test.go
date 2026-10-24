package roomowner

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/mtslinkapi"
)

const mtsFixtureLocator = "https://my.mts-link.ru/j/123/456/session/789?ice=tcp"

func mtsPrivateDirectory(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	// testing.TempDir's numbered child uses0777 minus umask. Production requires
	// an explicitly private root; do not depend on the test runner's umask.
	if err := os.Chmod(root, 0700); err != nil {
		t.Fatal(err)
	}
	return root
}

// Only the external organizer is replaced. Journal commits and owner decisions
// remain real; callbacks inspect durable intent at each external side effect.
type mtsFixtureAPI struct {
	event                            func() (mtslinkapi.Event, error)
	session                          func(int64) (mtslinkapi.Session, error)
	stop, deleteSession, deleteEvent func(int64) error
	creates                          atomic.Int32
}

func (p *mtsFixtureAPI) CreateEvent(_ context.Context, name string) (mtslinkapi.Event, error) {
	p.creates.Add(1)
	// Same bounded input contract as mtslinkapi.Client, before any HTTP effect.
	if len(name) > 128 {
		return mtslinkapi.Event{}, mtslinkapi.ErrInput
	}
	if p.event != nil {
		return p.event()
	}
	return mtslinkapi.Event{ID: 456, Locator: "https://my.mts-link.ru/j/123/456"}, nil
}
func (p *mtsFixtureAPI) CreateSession(_ context.Context, id int64) (mtslinkapi.Session, error) {
	if p.session != nil {
		return p.session(id)
	}
	return mtslinkapi.Session{ID: 789, EventID: id, Locator: "https://my.mts-link.ru/j/123/456/session/789"}, nil
}
func (p *mtsFixtureAPI) StopSession(_ context.Context, id int64) error {
	if p.stop != nil {
		return p.stop(id)
	}
	return nil
}
func (p *mtsFixtureAPI) DeleteSession(_ context.Context, id int64) error {
	if p.deleteSession != nil {
		return p.deleteSession(id)
	}
	return nil
}
func (p *mtsFixtureAPI) DeleteEvent(_ context.Context, id int64) error {
	if p.deleteEvent != nil {
		return p.deleteEvent(id)
	}
	return nil
}

func mtsBinding(now time.Time) Binding { b := fixtureBinding(now); b.Provider = "mtslink"; return b }
func mtsOwner(t *testing.T, root string, p MTSAPI, now func() time.Time, a Authority, c Carrier) (*MTSOwner, *MTSFileJournal) {
	t.Helper()
	j, err := NewMTSFileJournal(root)
	if err != nil {
		t.Fatal(err)
	}
	o, err := NewMTS(j, p, "organizer-v1", a, c, now)
	if err != nil {
		t.Fatal(err)
	}
	return o, j
}

// Removing either committed intent would send a provider mutation without custody.
func TestMTSProvisionCommitsEachIDBeforeNextMutationAndPublication(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	p := &mtsFixtureAPI{}
	o, j := mtsOwner(t, mtsPrivateDirectory(t), p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	p.event = func() (mtslinkapi.Event, error) {
		r, err := j.Load(b)
		if err != nil || r.Phase != MTSEventCreating {
			t.Fatal("event sent without intent")
		}
		return mtslinkapi.Event{ID: 456, Locator: "https://my.mts-link.ru/j/123/456"}, nil
	}
	p.session = func(id int64) (mtslinkapi.Session, error) {
		r, err := j.Load(b)
		if err != nil || r.Phase != MTSSessionCreating || r.Resource.EventID != 456 || id != 456 {
			t.Fatal("session sent before EventID persisted")
		}
		return mtslinkapi.Session{ID: 789, EventID: 456, Locator: "https://my.mts-link.ru/j/123/456/session/789"}, nil
	}
	pub, err := o.Provision(context.Background(), b)
	if err != nil || pub.Locator != mtsFixtureLocator || pub.Binding != b.canonical() {
		t.Fatalf("no bound publication: %v", err)
	}
	r, err := j.Load(b)
	if err != nil || r.Phase != MTSCreated || r.Resource.EventID != 456 || r.Resource.SessionID != 789 || r.Resource.Locator != mtsFixtureLocator {
		t.Fatal("published before ID commit")
	}
	if _, err = o.Provision(context.Background(), b); !errors.Is(err, ErrOwned) {
		t.Fatal("replayed owned attempt")
	}
}

// Removing exclusive claim would create duplicate rooms across processes/restarts.
func TestMTSConcurrentAndRestartedOwnersNeverDuplicateOrAdopt(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	root := mtsPrivateDirectory(t)
	p := &mtsFixtureAPI{}
	var wg sync.WaitGroup
	var successes atomic.Int32
	for n := 0; n < 6; n++ {
		o, _ := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := o.Provision(context.Background(), b); err == nil {
				successes.Add(1)
			}
		}()
	}
	wg.Wait()
	restarted, _ := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	if _, err := restarted.Provision(context.Background(), b); !errors.Is(err, ErrOwned) || successes.Load() != 1 || p.creates.Load() != 1 {
		t.Fatal("duplicate or adopted room")
	}
}

// An ambiguous session create must retain the parent and permit only exact cleanup.
func TestMTSPartialCreateRetainsCustodyWithoutRetry(t *testing.T) {
	for _, stage := range []string{"event-unknown", "event-known", "session-unknown", "session-known", "session-mismatch"} {
		t.Run(stage, func(t *testing.T) {
			now := time.Now()
			b := mtsBinding(now)
			p := &mtsFixtureAPI{}
			sessions := 0
			if stage == "event-unknown" {
				p.event = func() (mtslinkapi.Event, error) { return mtslinkapi.Event{}, errors.New("private upstream body") }
			}
			if stage == "event-known" {
				p.event = func() (mtslinkapi.Event, error) { return mtslinkapi.Event{ID: 456}, mtslinkapi.ErrReply }
			}
			p.session = func(id int64) (mtslinkapi.Session, error) {
				sessions++
				switch stage {
				case "session-known":
					return mtslinkapi.Session{ID: 789, EventID: id}, mtslinkapi.ErrReply
				case "session-mismatch":
					return mtslinkapi.Session{ID: 999, EventID: 444, Locator: "https://my.mts-link.ru/j/123/444/session/999"}, nil
				default:
					return mtslinkapi.Session{}, mtslinkapi.ErrTransport
				}
			}
			o, j := mtsOwner(t, mtsPrivateDirectory(t), p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			if pub, err := o.Provision(context.Background(), b); !errors.Is(err, ErrCreationUnknown) || pub.Locator != "" {
				t.Fatal("partial create published")
			}
			r, err := j.Load(b)
			if err != nil || r.Phase != MTSCreationUnknown {
				t.Fatal("lost ambiguity")
			}
			if stage != "event-unknown" && r.Resource.EventID != 456 {
				t.Fatal("lost EventID")
			}
			if stage == "session-known" && r.Resource.SessionID != 789 {
				t.Fatal("lost known SessionID")
			}
			if stage == "session-mismatch" && r.Resource.SessionID != 0 {
				t.Fatal("adopted unrelated session")
			}
			if strings.HasPrefix(stage, "event-") && sessions != 0 {
				t.Fatal("continued after event ambiguity")
			}
			if _, err := o.Provision(context.Background(), b); !errors.Is(err, ErrOwned) || p.creates.Load() != 1 {
				t.Fatal("retried unknown creation")
			}
			stops, deletes, events := 0, 0, 0
			p.stop = func(id int64) error {
				if id != 789 {
					t.Fatal("wrong stop")
				}
				stops++
				return nil
			}
			p.deleteSession = func(id int64) error {
				if id != 789 {
					t.Fatal("wrong session delete")
				}
				deletes++
				return nil
			}
			p.deleteEvent = func(id int64) error {
				if id != 456 {
					t.Fatal("wrong event delete")
				}
				events++
				return nil
			}
			err = o.Close(context.Background(), b)
			if stage == "event-unknown" {
				if !errors.Is(err, ErrCleanupUnknown) || stops+deletes+events != 0 {
					t.Fatal("guessed unknown event")
				}
			} else if err != nil {
				t.Fatal(err)
			}
			if stage == "session-known" {
				if stops != 1 || deletes != 1 || events != 0 {
					t.Fatal("known session not exactly retired")
				}
			} else if stage != "event-unknown" && events != 1 {
				t.Fatal("owned partial event not reclaimed")
			}
		})
	}
}

// Stop ACK must survive a later delete failure and may never be fabricated/replayed.
func TestMTSCloseFencesJoinsAndPersistsStopBeforeDelete(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	p := &mtsFixtureAPI{}
	var order []string
	c := mtsTestCarrier{fence: func(Binding) error { order = append(order, "fence"); return nil }, join: func(context.Context, Binding) error { order = append(order, "join"); return nil }}
	o, j := mtsOwner(t, mtsPrivateDirectory(t), p, func() time.Time { return now }, fixtureAuthority{}, c)
	if _, err := o.Provision(context.Background(), b); err != nil {
		t.Fatal(err)
	}
	p.stop = func(id int64) error {
		r, e := j.Load(b)
		if e != nil || r.Phase != MTSStopPending || id != 789 {
			t.Fatal("stop without exact intent")
		}
		order = append(order, "stop")
		return nil
	}
	p.deleteSession = func(id int64) error {
		r, e := j.Load(b)
		if e != nil || r.Phase != MTSDeletePending || id != 789 {
			t.Fatal("delete without stop ACK")
		}
		order = append(order, "delete")
		return errors.New("private upstream")
	}
	if err := o.Close(context.Background(), b); !errors.Is(err, ErrCleanupUnknown) {
		t.Fatal("delete failure became success")
	}
	if strings.Join(order, ",") != "fence,join,stop,delete" {
		t.Fatal("provider cleanup preceded payload cutoff")
	}
	r, e := j.Load(b)
	if e != nil || r.Phase != MTSCleanupUnknown {
		t.Fatal("lost cleanup uncertainty")
	}
	order = nil
	if err := o.Close(context.Background(), b); !errors.Is(err, ErrCleanupUnknown) || strings.Join(order, ",") != "fence,join" {
		t.Fatal("replayed uncertain provider cleanup")
	}
}

type mtsTestCarrier struct {
	fence func(Binding) error
	join  func(context.Context, Binding) error
}

func (c mtsTestCarrier) Fence(b Binding) error {
	if c.fence != nil {
		return c.fence(b)
	}
	return nil
}
func (c mtsTestCarrier) Join(ctx context.Context, b Binding) error {
	if c.join != nil {
		return c.join(ctx, b)
	}
	return ctx.Err()
}

// A pending external mutation is quarantined; an acknowledged step can advance.
func TestMTSCrashRecoveryOnlyAdvancesAcknowledgedCleanup(t *testing.T) {
	for _, phase := range []MTSPhase{MTSStopPending, MTSStopped, MTSDeletePending, MTSEventDeletePending} {
		t.Run(string(phase), func(t *testing.T) {
			now := time.Now()
			b := mtsBinding(now)
			root := mtsPrivateDirectory(t)
			p := &mtsFixtureAPI{}
			o, j := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			if _, err := o.Provision(context.Background(), b); err != nil {
				t.Fatal(err)
			}
			r, _ := j.Load(b)
			if phase == MTSEventDeletePending { // separate owned partial creation path
				root = mtsPrivateDirectory(t)
				j, _ = NewMTSFileJournal(root)
				j.Claim(b)
				r, _ = j.Load(b)
				r, _ = j.Advance(r, MTSCreationUnknown, MTSResource{EventID: 456})
				r, _ = j.Advance(r, phase, r.Resource)
			} else {
				r, _ = j.Advance(r, MTSStopPending, r.Resource)
				if phase != MTSStopPending {
					r, _ = j.Advance(r, MTSStopped, r.Resource)
				}
				if phase == MTSDeletePending {
					r, _ = j.Advance(r, phase, r.Resource)
				}
			}
			stops, deletes := 0, 0
			p.stop = func(int64) error { stops++; return nil }
			p.deleteSession = func(int64) error { deletes++; return nil }
			p.deleteEvent = func(int64) error { t.Fatal("replayed event delete"); return nil }
			restarted, _ := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			err := restarted.Close(context.Background(), b)
			if phase == MTSStopped {
				if err != nil || stops != 0 || deletes != 1 {
					t.Fatal("did not finish first delete after durable stop ACK")
				}
			} else if !errors.Is(err, ErrCleanupUnknown) || stops+deletes != 0 {
				t.Fatal("replayed pending mutation")
			}
		})
	}
}

// A concurrent Close cannot wait for an API request before fencing, or publish late.
func TestMTSCloseDuringCreateSuppressesLatePublication(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	p := &mtsFixtureAPI{}
	entered, release, fenced := make(chan struct{}), make(chan struct{}), make(chan struct{})
	p.event = func() (mtslinkapi.Event, error) {
		close(entered)
		<-release
		return mtslinkapi.Event{ID: 456, Locator: "https://my.mts-link.ru/j/123/456"}, nil
	}
	p.session = func(int64) (mtslinkapi.Session, error) {
		t.Error("created session after Close")
		return mtslinkapi.Session{}, nil
	}
	c := mtsTestCarrier{fence: func(Binding) error { close(fenced); return nil }}
	o, _ := mtsOwner(t, mtsPrivateDirectory(t), p, func() time.Time { return now }, fixtureAuthority{}, c)
	provisioned, closed := make(chan error, 1), make(chan error, 1)
	go func() { _, e := o.Provision(context.Background(), b); provisioned <- e }()
	<-entered
	go func() { closed <- o.Close(context.Background(), b) }()
	select {
	case <-fenced:
	case <-time.After(time.Second):
		t.Fatal("Close waited behind API before fencing")
	}
	close(release)
	if e := <-provisioned; !errors.Is(e, ErrDenied) {
		t.Fatalf("late publication: %v", e)
	}
	if e := <-closed; e != nil {
		t.Fatal(e)
	}
}

// Authority/credential/deadline changes must stop before the next provider mutation.
func TestMTSAuthorityAndCredentialAreCheckedBetweenCreationSteps(t *testing.T) {
	for _, deny := range []string{"credential", "expiry", "revocation"} {
		t.Run(deny, func(t *testing.T) {
			now := time.Now()
			b := mtsBinding(now)
			p := &mtsFixtureAPI{}
			revoked := false
			a := fixtureAuthority{check: func(Binding) error {
				if revoked {
					return ErrDenied
				}
				return nil
			}}
			p.event = func() (mtslinkapi.Event, error) {
				if deny == "expiry" {
					now = now.Add(2 * time.Minute)
				}
				if deny == "revocation" {
					revoked = true
				}
				return mtslinkapi.Event{ID: 456, Locator: "https://my.mts-link.ru/j/123/456"}, nil
			}
			p.session = func(int64) (mtslinkapi.Session, error) {
				t.Fatal("session created after authority ended")
				return mtslinkapi.Session{}, nil
			}
			o, _ := mtsOwner(t, mtsPrivateDirectory(t), p, func() time.Time { return now }, a, fixtureCarrier{})
			if deny == "credential" {
				b.CredentialRef = "another-version"
			}
			if pub, e := o.Provision(context.Background(), b); e == nil || pub.Locator != "" {
				t.Fatal("denied publication")
			}
			if deny == "credential" && p.creates.Load() != 0 {
				t.Fatal("wrong credential version reached API")
			}
		})
	}
}

// A maximum-length valid generation must not become an invalid provider name.
func TestMTSMaximumValidEpochDoesNotOverflowProviderName(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	b.Epoch = strings.Repeat("e", 128)
	o, _ := mtsOwner(t, mtsPrivateDirectory(t), &mtsFixtureAPI{}, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	if pub, err := o.Provision(context.Background(), b); err != nil || pub.Locator != mtsFixtureLocator {
		t.Fatalf("valid generation cannot provision: %v", err)
	}
}

type mtsJournalHook struct {
	MTSJournal
	fail          MTSPhase
	afterLoad     func(MTSRecord)
	afterRetiring func()
}

func (j mtsJournalHook) Advance(r MTSRecord, p MTSPhase, v MTSResource) (MTSRecord, error) {
	if p == j.fail {
		return MTSRecord{}, ErrJournal
	}
	return j.MTSJournal.Advance(r, p, v)
}
func (j mtsJournalHook) Load(b Binding) (MTSRecord, error) {
	r, e := j.MTSJournal.Load(b)
	if e == nil && j.afterLoad != nil {
		j.afterLoad(r)
	}
	return r, e
}

func (j mtsJournalHook) Retiring(b Binding) (bool, error) {
	retired, e := j.MTSJournal.Retiring(b)
	if e == nil && j.afterRetiring != nil {
		j.afterRetiring()
	}
	return retired, e
}

// A returned ID is not permission to continue after durable storage failure.
func TestMTSFailedIDCommitStopsBeforeSessionCreation(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	p := &mtsFixtureAPI{}
	j, e := NewMTSFileJournal(mtsPrivateDirectory(t))
	if e != nil {
		t.Fatal(e)
	}
	p.session = func(int64) (mtslinkapi.Session, error) {
		t.Fatal("session created despite failed ID commit")
		return mtslinkapi.Session{}, nil
	}
	o, e := NewMTS(mtsJournalHook{MTSJournal: j, fail: MTSEventCreated}, p, "organizer-v1", fixtureAuthority{}, fixtureCarrier{}, func() time.Time { return now })
	if e != nil {
		t.Fatal(e)
	}
	if pub, e := o.Provision(context.Background(), b); !errors.Is(e, ErrJournal) || pub.Locator != "" {
		t.Fatal("uncertain commit published")
	}
	if _, e := o.Provision(context.Background(), b); !errors.Is(e, ErrOwned) || p.creates.Load() != 1 {
		t.Fatal("storage failure retried create")
	}
}

// Deadline is rechecked after the last durable read, not merely before API calls.
func TestMTSExpiryDuringFinalReadSuppressesPublication(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	j, e := NewMTSFileJournal(mtsPrivateDirectory(t))
	if e != nil {
		t.Fatal(e)
	}
	h := mtsJournalHook{MTSJournal: j, afterLoad: func(r MTSRecord) {
		if r.Phase == MTSCreated {
			now = b.NotAfter
		}
	}}
	o, e := NewMTS(h, &mtsFixtureAPI{}, "organizer-v1", fixtureAuthority{}, fixtureCarrier{}, func() time.Time { return now })
	if e != nil {
		t.Fatal(e)
	}
	if pub, e := o.Provision(context.Background(), b); !errors.Is(e, ErrExpired) || pub.Locator != "" {
		t.Fatal("expired final read published")
	}
	r, e := j.Load(b)
	if e != nil || r.Resource.SessionID != 789 || r.Phase != MTSCreated {
		t.Fatal("expired publication lost cleanup custody")
	}
}

// Retirement-sidecar I/O is also before the final deadline/context guard.
func TestMTSExpiryDuringRetirementReadSuppressesPublication(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	j, e := NewMTSFileJournal(mtsPrivateDirectory(t))
	if e != nil {
		t.Fatal(e)
	}
	h := mtsJournalHook{MTSJournal: j, afterRetiring: func() {
		if r, e := j.Load(b); e == nil && r.Phase == MTSCreated {
			now = b.NotAfter
		}
	}}
	o, e := NewMTS(h, &mtsFixtureAPI{}, "organizer-v1", fixtureAuthority{}, fixtureCarrier{}, func() time.Time { return now })
	if e != nil {
		t.Fatal(e)
	}
	if pub, e := o.Provision(context.Background(), b); !errors.Is(e, ErrExpired) || pub.Locator != "" {
		t.Fatal("retirement read escaped final expiry guard")
	}
}

func TestMTSExpiredAuthorityDoesNotPreventKnownResourceCleanup(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	p := &mtsFixtureAPI{}
	revoked := false
	a := fixtureAuthority{check: func(Binding) error {
		if revoked {
			return ErrDenied
		}
		return nil
	}}
	o, j := mtsOwner(t, mtsPrivateDirectory(t), p, func() time.Time { return now }, a, fixtureCarrier{})
	if _, e := o.Provision(context.Background(), b); e != nil {
		t.Fatal(e)
	}
	now = b.NotAfter
	revoked = true
	if e := o.Close(context.Background(), b); e != nil {
		t.Fatalf("expired room could not retire: %v", e)
	}
	r, e := j.Load(b)
	if e != nil || r.Phase != MTSClosed {
		t.Fatal("cleanup did not retain terminal ACK")
	}
}

// This is the external paced boundary, not the journal/authority under test.
// The real Client independently checks ctx after its shared pacing wait.
type mtsQueuedAPI struct {
	*mtsFixtureAPI
	stage              string
	queued, dispatched atomic.Int32
}

func (p *mtsQueuedAPI) wait(ctx context.Context) error {
	p.queued.Add(1)
	timer := time.NewTimer(150 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return mtslinkapi.ErrTransport
	case <-timer.C:
		p.dispatched.Add(1)
		return nil
	}
}
func (p *mtsQueuedAPI) CreateEvent(ctx context.Context, name string) (mtslinkapi.Event, error) {
	if p.stage == "event" {
		if e := p.wait(ctx); e != nil {
			return mtslinkapi.Event{}, e
		}
	}
	return p.mtsFixtureAPI.CreateEvent(ctx, name)
}
func (p *mtsQueuedAPI) CreateSession(ctx context.Context, id int64) (mtslinkapi.Session, error) {
	if p.stage == "session" {
		if e := p.wait(ctx); e != nil {
			return mtslinkapi.Session{}, e
		}
	}
	return p.mtsFixtureAPI.CreateSession(ctx, id)
}

// Checking only before a shared API queue would allow a mutation after expiry.
func TestMTSQueuedCreationCannotOutliveOriginalBindingDeadline(t *testing.T) {
	for _, stage := range []string{"event", "session"} {
		t.Run(stage, func(t *testing.T) {
			b := mtsBinding(time.Now())
			b.NotAfter = time.Now().Add(70 * time.Millisecond)
			p := &mtsQueuedAPI{mtsFixtureAPI: &mtsFixtureAPI{}, stage: stage}
			o, _ := mtsOwner(t, mtsPrivateDirectory(t), p, time.Now, fixtureAuthority{}, fixtureCarrier{})
			if pub, e := o.Provision(context.Background(), b); e == nil || pub.Locator != "" {
				t.Fatal("expired queued create published")
			}
			if p.queued.Load() != 1 {
				t.Fatal("fixture did not reach the queued API boundary")
			}
			if p.dispatched.Load() != 0 {
				t.Fatal("provider mutation dispatched after original deadline")
			}
		})
	}
}

// Per-instance mutexes cannot protect returned IDs against a reopened owner.
func TestMTSOtherOwnerCloseWaitsForReturnedIDAndSuppressesPublication(t *testing.T) {
	now := time.Now()
	b := mtsBinding(now)
	root := mtsPrivateDirectory(t)
	p := &mtsFixtureAPI{}
	entered, release, fenced := make(chan struct{}), make(chan struct{}), make(chan struct{})
	p.event = func() (mtslinkapi.Event, error) {
		close(entered)
		<-release
		return mtslinkapi.Event{ID: 456, Locator: "https://my.mts-link.ru/j/123/456"}, nil
	}
	p.session = func(int64) (mtslinkapi.Session, error) {
		t.Error("session created after inter-owner Close")
		return mtslinkapi.Session{}, nil
	}
	p.deleteEvent = func(id int64) error {
		if id != 456 {
			t.Error("wrong partial event deleted")
		}
		return nil
	}
	creator, j := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
	closer, _ := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, mtsTestCarrier{fence: func(Binding) error { close(fenced); return nil }})
	provisioned, closed := make(chan error, 1), make(chan error, 1)
	go func() { _, e := creator.Provision(context.Background(), b); provisioned <- e }()
	<-entered
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	go func() { closed <- closer.Close(ctx, b) }()
	select {
	case <-fenced:
	case <-time.After(time.Second):
		close(release)
		t.Fatal("cross-owner Close did not fence immediately")
	}
	var closeErr error
	completed := false
	select {
	case closeErr = <-closed:
		completed = true
		t.Error("second owner rewrote pending create before its response was retained")
	case <-time.After(30 * time.Millisecond):
	}
	close(release)
	if e := <-provisioned; !errors.Is(e, ErrDenied) {
		t.Errorf("late provision did not retain then refuse: %v", e)
	}
	if !completed {
		closeErr = <-closed
	}
	if closeErr != nil {
		t.Errorf("known partial cleanup failed: %v", closeErr)
	}
	r, e := j.Load(b)
	if e != nil || r.Phase != MTSClosed || r.Resource.EventID != 456 {
		t.Fatal("late positive EventID was lost from custody")
	}
}

// A process-local mutex must not masquerade as a cross-process transaction lock.
func TestMTSKernelLockExcludesASeparateProcess(t *testing.T) {
	b := mtsBinding(time.Date(2026, 10, 24, 13, 0, 0, 0, time.UTC))
	if root := os.Getenv("RGA_MTS_LOCK_TEST_ROOT"); root != "" {
		j, e := NewMTSFileJournal(root)
		if e != nil {
			t.Fatal(e)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
		defer cancel()
		unlock, e := j.Lock(ctx, b)
		if os.Getenv("RGA_MTS_LOCK_TEST_EXPECT") == "blocked" {
			if e == nil {
				unlock()
				t.Fatal("child acquired parent's kernel lock")
			}
		} else {
			if e != nil {
				t.Fatal("released kernel lock unavailable")
			}
			unlock()
		}
		return
	}
	root := mtsPrivateDirectory(t)
	j, e := NewMTSFileJournal(root)
	if e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	unlock, e := j.Lock(ctx, b)
	if e != nil {
		t.Fatal(e)
	}
	held := true
	defer func() {
		if held {
			unlock()
		}
	}()
	child := func(expected string) {
		childCtx, stop := context.WithTimeout(context.Background(), 3*time.Second)
		defer stop()
		cmd := exec.CommandContext(childCtx, os.Args[0], "-test.run=^TestMTSKernelLockExcludesASeparateProcess$", "-test.timeout=2s")
		cmd.Env = append(os.Environ(), "RGA_MTS_LOCK_TEST_ROOT="+root, "RGA_MTS_LOCK_TEST_EXPECT="+expected)
		if out, e := cmd.CombinedOutput(); e != nil {
			t.Fatalf("kernel lock child failed: %v %s", e, out)
		}
	}
	child("blocked")
	unlock()
	held = false
	child("free")
}

// Missing revision/foreign content must block every next mutation, never repair.
func TestMTSJournalRejectsGapsAndCorruptionWithoutReplay(t *testing.T) {
	for _, damage := range []string{"gap", "corrupt", "empty"} {
		t.Run(damage, func(t *testing.T) {
			now := time.Now()
			b := mtsBinding(now)
			root := mtsPrivateDirectory(t)
			p := &mtsFixtureAPI{}
			o, j := mtsOwner(t, root, p, func() time.Time { return now }, fixtureAuthority{}, fixtureCarrier{})
			if _, e := o.Provision(context.Background(), b); e != nil {
				t.Fatal(e)
			}
			entries, e := os.ReadDir(root)
			var dirs []os.DirEntry
			for _, entry := range entries {
				if entry.IsDir() {
					dirs = append(dirs, entry)
				}
			}
			if e != nil || len(dirs) != 1 {
				t.Fatal("bad fixture")
			}
			dir := filepath.Join(root, dirs[0].Name())
			switch damage {
			case "gap":
				e = os.Remove(filepath.Join(dir, "000001.json"))
			case "corrupt":
				e = os.WriteFile(filepath.Join(dir, "000002.json"), []byte(`{}`), 0600)
			case "empty":
				files, _ := os.ReadDir(dir)
				for _, f := range files {
					if e = os.Remove(filepath.Join(dir, f.Name())); e != nil {
						t.Fatal(e)
					}
				}
			}
			if e != nil {
				t.Fatal(e)
			}
			if _, e = j.Load(b); !errors.Is(e, ErrJournal) {
				t.Fatal("corrupt journal accepted")
			}
			if _, e = o.Provision(context.Background(), b); !errors.Is(e, ErrOwned) || p.creates.Load() != 1 {
				t.Fatal("repaired/replayed corrupt attempt")
			}
		})
	}
}
