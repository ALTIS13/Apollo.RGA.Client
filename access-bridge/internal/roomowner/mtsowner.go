package roomowner

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/mtslinkapi"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/rtcprovider"
)

// MTSAPI is one exact credential version, shared across its owners so API pacing
// is per organizer, not per room. No token enters a binding or publication.
type MTSAPI interface {
	CreateEvent(context.Context, string) (mtslinkapi.Event, error)
	CreateSession(context.Context, int64) (mtslinkapi.Session, error)
	StopSession(context.Context, int64) error
	DeleteSession(context.Context, int64) error
	DeleteEvent(context.Context, int64) error
}

var _ MTSAPI = (*mtslinkapi.Client)(nil)

type MTSOwner struct {
	mu            sync.Mutex
	stateMu       sync.Mutex
	bound         *Binding
	retiring      bool
	journal       MTSJournal
	api           MTSAPI
	credentialRef string
	authority     Authority
	carrier       Carrier
	now           func() time.Time
}

func mtsBindingValid(b Binding) bool {
	return b.Provider == "mtslink" && b.commonValid()
}
func mtsResourceValid(r MTSResource) bool {
	if r.EventID <= 0 || r.SessionID <= 0 || !rtcprovider.Valid("mtslink", "vp8channel", r.Locator) {
		return false
	}
	u, err := url.Parse(r.Locator)
	if err != nil {
		return false
	}
	p := strings.Split(u.Path, "/")
	return len(p) == 6 && p[3] == strconv.FormatInt(r.EventID, 10) && p[5] == strconv.FormatInt(r.SessionID, 10)
}

func NewMTS(j MTSJournal, p MTSAPI, credentialRef string, a Authority, c Carrier, now func() time.Time) (*MTSOwner, error) {
	if nilInterface(j) || nilInterface(p) || !opaque.MatchString(credentialRef) || nilInterface(a) || nilInterface(c) || now == nil {
		return nil, ErrConfig
	}
	return &MTSOwner{journal: j, api: p, credentialRef: credentialRef, authority: a, carrier: c, now: now}, nil
}

// One owner is one immutable worker generation. A new credential version or
// binding needs a new instance, never a mutable organizer/default substitution.
func (o *MTSOwner) bind(b Binding, retire bool) error {
	if !mtsBindingValid(b) || b.CredentialRef != o.credentialRef {
		return ErrConfig
	}
	o.stateMu.Lock()
	defer o.stateMu.Unlock()
	if o.bound == nil {
		copy := b
		o.bound = &copy
	} else if *o.bound != b {
		return ErrConfig
	}
	if retire {
		o.retiring = true
	}
	return nil
}

func (o *MTSOwner) allowed(ctx context.Context, b Binding) error {
	if ctx == nil || ctx.Err() != nil {
		return ErrDenied
	}
	if !b.NotAfter.After(o.now()) {
		return ErrExpired
	}
	retired, err := o.journal.Retiring(b)
	if err != nil {
		return ErrJournal
	}
	if retired {
		return ErrDenied
	}
	if o.authority.Check(ctx, b) != nil || ctx.Err() != nil {
		return ErrDenied
	}
	if !b.NotAfter.After(o.now()) {
		return ErrExpired
	}
	o.stateMu.Lock()
	defer o.stateMu.Unlock()
	if o.retiring {
		return ErrDenied
	}
	return nil
}

func mtsEventValid(e mtslinkapi.Event) bool {
	if e.ID <= 0 {
		return false
	}
	u, err := url.Parse(e.Locator)
	if err != nil {
		return false
	}
	p := strings.Split(u.Path, "/")
	return len(p) == 4 && appID.MatchString(p[2]) && e.Locator == "https://my.mts-link.ru/j/"+p[2]+"/"+strconv.FormatInt(e.ID, 10)
}

func (o *MTSOwner) Provision(ctx context.Context, b Binding) (Publication, error) {
	b = b.canonical()
	if err := o.bind(b, false); err != nil {
		return Publication{}, err
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if err := o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	// Shared API pacing is inside the call. Cap that wait and dispatch by the
	// ORIGINAL wall-clock deadline, not a fresh timeout at each creation hop.
	ctx, cancel := context.WithDeadline(ctx, b.NotAfter)
	defer cancel()
	unlock, err := o.journal.Lock(ctx, b)
	if err != nil {
		return Publication{}, ErrJournal
	}
	defer unlock()
	if err := o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	claimed, err := o.journal.Claim(b)
	if err != nil {
		return Publication{}, ErrJournal
	}
	if !claimed {
		return Publication{}, ErrOwned
	}
	r, err := o.journal.Load(b)
	if err != nil || r.Phase != MTSEventCreating {
		return Publication{}, ErrJournal
	}
	if err = o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	// Provider display name is not custody. Do not leak internal generation IDs
	// or overflow the API's128-byte name limit with a valid128-byte epoch.
	event, createErr := o.api.CreateEvent(ctx, "Apollo.RGA")
	resource := MTSResource{}
	if event.ID > 0 {
		resource.EventID = event.ID
	}
	if createErr != nil || !mtsEventValid(event) {
		if _, err = o.journal.Advance(r, MTSCreationUnknown, resource); err != nil {
			return Publication{}, ErrJournal
		}
		return Publication{}, ErrCreationUnknown
	}
	// Retain returned ownership even if caller cancellation/revoke won the race.
	r, err = o.journal.Advance(r, MTSEventCreated, resource)
	if err != nil {
		return Publication{}, ErrJournal
	}
	if err = o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	r, err = o.journal.Advance(r, MTSSessionCreating, resource)
	if err != nil {
		return Publication{}, ErrJournal
	}
	if err = o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	session, createErr := o.api.CreateSession(ctx, resource.EventID)
	if session.EventID == resource.EventID && session.ID > 0 {
		resource.SessionID = session.ID
	}
	candidate := resource
	candidate.Locator = session.Locator + "?ice=tcp"
	if createErr != nil || !mtsResourceValid(candidate) {
		if _, err = o.journal.Advance(r, MTSCreationUnknown, resource); err != nil {
			return Publication{}, ErrJournal
		}
		return Publication{}, ErrCreationUnknown
	}
	r, err = o.journal.Advance(r, MTSCreated, candidate)
	if err != nil {
		return Publication{}, ErrJournal
	}
	current, err := o.journal.Load(b)
	if err != nil || current != r {
		return Publication{}, ErrJournal
	}
	if err = o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	o.stateMu.Lock()
	defer o.stateMu.Unlock()
	if o.retiring {
		return Publication{}, ErrDenied
	}
	return Publication{Binding: b, Locator: candidate.Locator, Revision: r.Revision}, nil
}

// Close never renews admission. Local cutoff/join and each global provider ACK
// are separate facts. A pending API mutation is quarantined, never replayed;
// a durable Stop ACK may proceed to its first Delete after restart.
func (o *MTSOwner) Close(ctx context.Context, b Binding) error {
	b = b.canonical()
	if err := o.bind(b, true); err != nil {
		return err
	}
	fenceErr := o.carrier.Fence(b) // Must not wait for a slow API or journal lock.
	// This separate append-only intent is visible to the creating process while
	// Close waits on its transaction lock. Never mutate an outstanding create
	// revision before its late positive ID has been durably retained.
	retireErr := o.journal.Retire(b)
	o.mu.Lock()
	defer o.mu.Unlock()
	join := func() error {
		if ctx == nil {
			return ErrCleanupUnknown
		}
		joinErr := o.carrier.Join(ctx, b)
		if fenceErr != nil || joinErr != nil {
			return ErrCleanupUnknown
		}
		return nil
	}
	if retireErr != nil {
		_ = join()
		return ErrJournal
	}
	unlock, err := o.journal.Lock(ctx, b)
	if err != nil {
		_ = join()
		return ErrJournal
	}
	defer unlock()
	r, err := o.journal.Load(b)
	if err != nil {
		_ = join()
		return ErrJournal
	}
	if join() != nil {
		return ErrCleanupUnknown
	}
	if r.Phase == MTSClosed {
		return nil
	}
	unknown := func() error {
		if _, e := o.journal.Advance(r, MTSCleanupUnknown, r.Resource); e != nil {
			return ErrJournal
		}
		return ErrCleanupUnknown
	}
	switch r.Phase {
	case MTSStopPending, MTSDeletePending, MTSEventDeletePending, MTSEventCreating:
		return unknown()
	case MTSCleanupUnknown:
		return ErrCleanupUnknown
	case MTSCreationUnknown:
		if r.Resource.EventID == 0 {
			return unknown()
		}
	}
	if ctx == nil || ctx.Err() != nil {
		return ErrCleanupUnknown
	}
	if r.Phase == MTSCreated || (r.Phase == MTSCreationUnknown && r.Resource.SessionID > 0) {
		r, err = o.journal.Advance(r, MTSStopPending, r.Resource)
		if err != nil {
			return ErrJournal
		}
		if ctx.Err() != nil || o.api.StopSession(ctx, r.Resource.SessionID) != nil {
			return unknown()
		}
		r, err = o.journal.Advance(r, MTSStopped, r.Resource)
		if err != nil {
			return ErrJournal
		}
	}
	if ctx.Err() != nil {
		return ErrCleanupUnknown
	}
	if r.Phase == MTSStopped {
		r, err = o.journal.Advance(r, MTSDeletePending, r.Resource)
		if err != nil {
			return ErrJournal
		}
		if ctx.Err() != nil || o.api.DeleteSession(ctx, r.Resource.SessionID) != nil {
			return unknown()
		}
	} else if (r.Phase == MTSEventCreated || r.Phase == MTSSessionCreating || r.Phase == MTSCreationUnknown) && r.Resource.EventID > 0 && r.Resource.SessionID == 0 {
		r, err = o.journal.Advance(r, MTSEventDeletePending, r.Resource)
		if err != nil {
			return ErrJournal
		}
		if ctx.Err() != nil || o.api.DeleteEvent(ctx, r.Resource.EventID) != nil {
			return unknown()
		}
	} else {
		return ErrCleanupUnknown
	}
	if _, err = o.journal.Advance(r, MTSClosed, r.Resource); err != nil {
		return ErrJournal
	}
	return nil
}
