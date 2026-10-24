// Package personal orchestrates one explicitly bound diagnostic OlcRTC join.
// It has no network handler and does not issue a production runtime grant.
package personal

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io"
	"reflect"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

var (
	ErrInvalidConfig = errors.New("personal: invalid configuration")
	ErrDenied        = errors.New("personal: join denied")
	ErrUnavailable   = errors.New("personal: service unavailable")
)

type Panel interface {
	LookupByID(context.Context, int64) (remnawave.UserSnapshot, error)
}

type StateStore interface {
	Load(context.Context, access.Subject) (access.SubjectState, error)
	RedeemQAJoin(context.Context, store.QAJoinRequest) error
	RenewQADeadline(context.Context, store.QARenewRequest) (time.Time, error)
}

// SessionLease is returned only after the store confirms atomic redemption or
// renewal. It is not an embeddable OlcRTC grant by itself.
type SessionLease struct {
	SessionID string
	Deadline  time.Time
	ByteCap   int64
}

type Service struct {
	config  access.PersonalTestConfig
	panel   Panel
	store   StateStore
	now     func() time.Time
	entropy io.Reader
}

// NewService binds one server-owned subject, installation, room and country.
// Client input to Admit consists only of signed flat proof claims.
func NewService(config access.PersonalTestConfig, panel Panel, state StateStore) (*Service, error) {
	if config.Subject.Validate() != nil || config.Installation.Validate() != nil || nilDependency(panel) || nilDependency(state) {
		return nil, ErrInvalidConfig
	}
	return &Service{
		config: config, panel: panel, store: state,
		now: time.Now, entropy: rand.Reader,
	}, nil
}

func nilDependency(value any) bool {
	if value == nil {
		return true
	}
	v := reflect.ValueOf(value)
	switch v.Kind() {
	case reflect.Chan, reflect.Func, reflect.Interface, reflect.Map, reflect.Pointer, reflect.Slice:
		return v.IsNil()
	default:
		return false
	}
}

// Admit verifies possession, refreshes the canonical Remnawave user, loads
// Bridge state, plans a short personal lease and atomically consumes the proof
// nonce. No claims, URLs, tokens or upstream error text enter returned errors.
func (s *Service) Admit(ctx context.Context, claims map[string]any) (SessionLease, error) {
	if ctx == nil || ctx.Err() != nil {
		return SessionLease{}, ErrUnavailable
	}
	policyStarted := s.now()
	verified, err := access.VerifyPersonalJoinProof(
		s.now(), s.config.Installation, s.config.RoomID, s.config.Country, claims,
	)
	if err != nil {
		return SessionLease{}, ErrDenied
	}
	user, err := s.panel.LookupByID(ctx, s.config.Subject.UserID)
	if err != nil {
		if errors.Is(err, remnawave.ErrNotFound) {
			return SessionLease{}, ErrDenied
		}
		return SessionLease{}, ErrUnavailable
	}
	observedAt := policyStarted
	state, err := s.store.Load(ctx, s.config.Subject)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return SessionLease{}, ErrDenied
		}
		return SessionLease{}, ErrUnavailable
	}
	now := s.now()
	// External work may have taken time; a proof valid at entry must still be
	// fresh immediately before the atomic redemption.
	if _, err := access.VerifyPersonalJoinProof(
		now, s.config.Installation, s.config.RoomID, s.config.Country, claims,
	); err != nil {
		return SessionLease{}, ErrDenied
	}
	plan, err := access.PlanPersonalTestSession(now, s.config, access.PersonalTestFacts{
		State: state, BoundInstallation: s.config.Installation,
		User: user, ObservedAt: observedAt,
	})
	if err != nil {
		return SessionLease{}, ErrDenied
	}
	if cap := policyStarted.Add(s.config.LeaseTTL); plan.Deadline.After(cap) {
		plan.Deadline = cap
	}
	if !plan.Deadline.After(now) {
		return SessionLease{}, ErrDenied
	}
	if ctx.Err() != nil {
		return SessionLease{}, ErrUnavailable
	}
	var randomBytes [32]byte
	if _, err := io.ReadFull(s.entropy, randomBytes[:]); err != nil {
		return SessionLease{}, ErrUnavailable
	}
	sessionID := base64.RawURLEncoding.EncodeToString(randomBytes[:])
	err = s.store.RedeemQAJoin(ctx, store.QAJoinRequest{
		Subject: plan.Subject, InstallationID: plan.Installation.ID,
		PublicKeySHA256: plan.Installation.PublicKeySHA256,
		RoomID:          plan.RoomID, Country: plan.Country,
		SessionID: sessionID, Nonce: verified.Nonce,
		ProofDeadline:  verified.ProofDeadline,
		PolicyRevision: plan.PolicyRevision,
		ObservedAt:     observedAt, MaxSnapshotAge: s.config.MaxSnapshotAge,
		SubscriptionExpiresAt: user.ExpiresAt, Deadline: plan.Deadline,
	})
	if err != nil {
		if errors.Is(err, store.ErrQAJoinDenied) || errors.Is(err, store.ErrQAJoinReplay) || errors.Is(err, store.ErrQAJoinInvalid) {
			return SessionLease{}, ErrDenied
		}
		return SessionLease{}, ErrUnavailable
	}
	return SessionLease{SessionID: sessionID, Deadline: plan.Deadline, ByteCap: plan.ByteCap}, nil
}

// Renew refreshes canonical entitlement and the server-owned binding before
// extending exactly one already admitted session. No new client proof is
// accepted or required. The managed wrapper must extend its tunnel lease only
// after this returns a durable lease, never on an ambiguous store outcome.
func (s *Service) Renew(ctx context.Context, sessionID string) (SessionLease, error) {
	if ctx == nil || ctx.Err() != nil {
		return SessionLease{}, ErrUnavailable
	}
	policyStarted := s.now()
	decoded, err := base64.RawURLEncoding.DecodeString(sessionID)
	if err != nil || len(decoded) != 32 || base64.RawURLEncoding.EncodeToString(decoded) != sessionID {
		return SessionLease{}, ErrDenied
	}
	user, err := s.panel.LookupByID(ctx, s.config.Subject.UserID)
	if err != nil {
		if errors.Is(err, remnawave.ErrNotFound) {
			return SessionLease{}, ErrDenied
		}
		return SessionLease{}, ErrUnavailable
	}
	observedAt := policyStarted
	state, err := s.store.Load(ctx, s.config.Subject)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return SessionLease{}, ErrDenied
		}
		return SessionLease{}, ErrUnavailable
	}
	plan, err := access.PlanPersonalTestSession(s.now(), s.config, access.PersonalTestFacts{
		State: state, BoundInstallation: s.config.Installation,
		User: user, ObservedAt: observedAt,
	})
	if err != nil {
		return SessionLease{}, ErrDenied
	}
	if cap := policyStarted.Add(s.config.LeaseTTL); plan.Deadline.After(cap) {
		plan.Deadline = cap
	}
	if !plan.Deadline.After(s.now()) {
		return SessionLease{}, ErrDenied
	}
	if ctx.Err() != nil {
		return SessionLease{}, ErrUnavailable
	}
	deadline, err := s.store.RenewQADeadline(ctx, store.QARenewRequest{
		Subject: plan.Subject, InstallationID: plan.Installation.ID,
		PublicKeySHA256: plan.Installation.PublicKeySHA256,
		RoomID:          plan.RoomID, Country: plan.Country,
		SessionID: sessionID, PolicyRevision: plan.PolicyRevision,
		ObservedAt: observedAt, MaxSnapshotAge: s.config.MaxSnapshotAge,
		SubscriptionExpiresAt: user.ExpiresAt, NewDeadline: plan.Deadline,
	})
	if err != nil {
		if errors.Is(err, store.ErrQARenewDenied) || errors.Is(err, store.ErrQARenewInvalid) {
			return SessionLease{}, ErrDenied
		}
		return SessionLease{}, ErrUnavailable
	}
	if !deadline.Equal(plan.Deadline.UTC().Truncate(time.Microsecond)) {
		return SessionLease{}, ErrUnavailable
	}
	return SessionLease{SessionID: sessionID, Deadline: deadline, ByteCap: plan.ByteCap}, nil
}
