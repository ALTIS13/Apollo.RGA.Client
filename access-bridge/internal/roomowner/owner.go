// Package roomowner owns a provider room on RU, not subscription admission.
// Its publication is discovery data, never a payload lease. Runtime wiring must
// revalidate that publication through authenticated Home control before bootstrap.
package roomowner

import (
	"context"
	"errors"
	"reflect"
	"regexp"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/rtcprovider"
)

var (
	ErrConfig          = errors.New("room owner: invalid configuration")
	ErrOwned           = errors.New("room owner: attempt already owned; no replay")
	ErrJournal         = errors.New("room owner: journal unavailable or uncertain")
	ErrDenied          = errors.New("room owner: authorization unavailable or denied")
	ErrExpired         = errors.New("room owner: intent expired")
	ErrCreationUnknown = errors.New("room owner: creation unknown; no retry")
	ErrCleanupUnknown  = errors.New("room owner: cleanup unknown; no replay")
	// A Provider may return this only for a definitive no-room-created response.
	ErrProviderRejected = errors.New("room owner: provider rejected creation")
	opaque              = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)
	callID              = regexp.MustCompile(`^[A-Za-z0-9_-]{36}$`)
)

// Binding is immutable server-owned intent. Room is the Apollo authority ID;
// it must never be replaced with a provider call_id or guest join locator.
// CredentialRef identifies a specific organizer/app credential custody version,
// not a token and not a mutable "current default" credential selector.
type Binding struct {
	Provider       string
	Worker         string
	Epoch          string
	Room           string
	Route          string
	Subject        access.Subject
	Installation   access.InstallationBinding
	PolicyRevision int64
	CredentialRef  string
	NotAfter       time.Time
}

func (b Binding) canonical() Binding {
	// Wall-clock deadline is the durable identity. Go's process-local monotonic
	// clock and location pointers cannot be part of a cross-process binding.
	b.NotAfter = b.NotAfter.Round(0).UTC()
	return b
}

func (b Binding) valid() bool {
	return b.Provider == "vkcalls" && b.commonValid()
}

func (b Binding) commonValid() bool {
	return opaque.MatchString(b.Worker) && opaque.MatchString(b.Epoch) &&
		opaque.MatchString(b.Room) && opaque.MatchString(b.Route) && opaque.MatchString(b.CredentialRef) &&
		b.Subject.Validate() == nil && b.Installation.Validate() == nil && b.PolicyRevision > 0 && !b.NotAfter.IsZero()
}

type CreatedRoom struct{ CallID, Locator string }

func (r CreatedRoom) valid() bool {
	return callID.MatchString(r.CallID) && rtcprovider.Valid("vkcalls", "vp8channel", r.Locator)
}
func (CreatedRoom) String() string { return "provider room [private]" }

type Publication struct {
	Binding  Binding
	Locator  string
	Revision uint32
}

func (Publication) String() string { return "room publication [private; not admission]" }

// Provider is a separately authorized operator API. It receives a credential
// reference; no account token enters owner state or a client publication.
// Ambiguous/transport/malformed responses must not be classified as Rejected.
type Provider interface {
	Create(context.Context, string) (CreatedRoom, error)
	Finish(context.Context, string, string) error
}
type Authority interface {
	Check(context.Context, Binding) error
}

// Carrier validates the full binding against its actual native owner. Fence is
// synchronous, idempotent and independent of caller cancellation or journal I/O;
// it must not open/adopt an owner that does not already exist. Join may be bounded
// by ctx. A best-effort hangup alone cannot authorize global provider completion.
type Carrier interface {
	Fence(Binding) error
	Join(context.Context, Binding) error
}

type Journal interface {
	Claim(Binding) (bool, error)
	Load(Binding) (Record, error)
	Advance(Record, Phase, CreatedRoom) (Record, error)
}

type Owner struct {
	mu        sync.Mutex
	flightMu  sync.Mutex
	flight    *provisioning
	journal   Journal
	provider  Provider
	authority Authority
	carrier   Carrier
	now       func() time.Time
}

type provisioning struct {
	binding  Binding
	retiring bool
}

func nilInterface(v any) bool {
	if v == nil {
		return true
	}
	r := reflect.ValueOf(v)
	return (r.Kind() == reflect.Pointer || r.Kind() == reflect.Interface || r.Kind() == reflect.Func) && r.IsNil()
}

func New(j Journal, p Provider, a Authority, c Carrier, now func() time.Time) (*Owner, error) {
	if nilInterface(j) || nilInterface(p) || nilInterface(a) || nilInterface(c) || now == nil {
		return nil, ErrConfig
	}
	return &Owner{journal: j, provider: p, authority: a, carrier: c, now: now}, nil
}

func (o *Owner) allowed(ctx context.Context, b Binding) error {
	if !b.NotAfter.After(o.now()) {
		return ErrExpired
	}
	if ctx.Err() != nil || o.authority.Check(ctx, b) != nil {
		return ErrDenied
	}
	if ctx.Err() != nil {
		return ErrDenied
	}
	if !b.NotAfter.After(o.now()) {
		return ErrExpired
	}
	return nil
}

func (o *Owner) Provision(ctx context.Context, b Binding) (Publication, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	b = b.canonical()
	if !b.valid() {
		return Publication{}, ErrConfig
	}
	flight := &provisioning{binding: b}
	o.flightMu.Lock()
	o.flight = flight
	o.flightMu.Unlock()
	defer func() {
		o.flightMu.Lock()
		if o.flight == flight {
			o.flight = nil
		}
		o.flightMu.Unlock()
	}()
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
	if err != nil || r.Phase != Creating {
		return Publication{}, ErrJournal
	}
	room, createErr := o.provider.Create(ctx, b.CredentialRef)
	if createErr != nil || !room.valid() {
		phase, result := CreationUnknown, ErrCreationUnknown
		if errors.Is(createErr, ErrProviderRejected) {
			phase, result = Rejected, ErrProviderRejected
		}
		if _, err := o.journal.Advance(r, phase, CreatedRoom{}); err != nil {
			return Publication{}, ErrJournal
		}
		return Publication{}, result
	}
	// Always retain a returned owned room, even when the caller was cancelled or
	// admission expired during creation. Cleanup can run without renewing access.
	r, err = o.journal.Advance(r, Created, room)
	if err != nil {
		return Publication{}, ErrJournal
	}
	current, err := o.journal.Load(b)
	if err != nil || current != r {
		return Publication{}, ErrJournal
	}
	if err := o.allowed(ctx, b); err != nil {
		return Publication{}, err
	}
	o.flightMu.Lock()
	retiring := flight.retiring
	o.flightMu.Unlock()
	if retiring {
		return Publication{}, ErrDenied
	}
	return Publication{Binding: b, Locator: room.Locator, Revision: r.Revision}, nil
}

// Close never reauthorizes payload and can retire an expired/revoked intent.
// Restarted callers may close a known created room, but cannot adopt/re-publish
// it or replay an uncertain create/finish. Recovery of UNKNOWN needs independent
// provider evidence, not a retry hidden inside this method.
func (o *Owner) Close(ctx context.Context, b Binding) error {
	b = b.canonical()
	if !b.valid() {
		return ErrConfig
	}
	// Retire the in-flight publication before fencing. A slow provider request
	// may still return a room ID needed for cleanup, but cannot publish that room
	// as usable after this exact generation was explicitly closed.
	o.flightMu.Lock()
	if o.flight != nil && o.flight.binding == b {
		o.flight.retiring = true
	}
	o.flightMu.Unlock()
	// Payload cutoff is not contingent on successful resource accounting. Do it
	// before the mutex too: an outstanding provider request cannot delay fencing.
	fenceErr := o.carrier.Fence(b)
	o.mu.Lock()
	defer o.mu.Unlock()
	join := func() error {
		if fenceErr != nil {
			return ErrCleanupUnknown
		}
		return o.carrier.Join(ctx, b)
	}
	r, err := o.journal.Load(b)
	if err != nil {
		_ = join()
		return ErrJournal
	}
	if r.Phase == Closed {
		if join() != nil {
			return ErrCleanupUnknown
		}
		return nil
	}
	if r.Phase != Created {
		_ = join()
		if r.Phase == Closing {
			if _, err := o.journal.Advance(r, CleanupUnknown, r.Room); err != nil {
				return ErrJournal
			}
		}
		return ErrCleanupUnknown
	}
	r, err = o.journal.Advance(r, Closing, r.Room)
	if err != nil {
		_ = join()
		return ErrJournal
	}
	if join() != nil || ctx.Err() != nil {
		if _, err = o.journal.Advance(r, CleanupUnknown, r.Room); err != nil {
			return ErrJournal
		}
		return ErrCleanupUnknown
	}
	if ctx.Err() != nil || o.provider.Finish(ctx, b.CredentialRef, r.Room.CallID) != nil {
		if _, err = o.journal.Advance(r, CleanupUnknown, r.Room); err != nil {
			return ErrJournal
		}
		return ErrCleanupUnknown
	}
	if _, err = o.journal.Advance(r, Closed, r.Room); err != nil {
		return ErrJournal
	}
	return nil
}
