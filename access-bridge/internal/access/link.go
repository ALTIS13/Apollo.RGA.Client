package access

import (
	"context"
	"errors"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/policy"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

var (
	ErrLinkConfig      = errors.New("access: invalid link configuration")
	ErrLinkDenied      = errors.New("access: link denied")
	ErrLinkUnavailable = errors.New("access: link unavailable")
)

// LinkResolver is the server-side, read-only Remnawave lookup. The bearer is
// used transiently and must not be persisted or forwarded to an OlcRTC node.
type LinkResolver interface {
	Lookup(context.Context, string) (remnawave.UserSnapshot, error)
}

// ChallengeStore must implement subject creation, binding and challenge
// consumption in one transaction. The concrete PostgreSQL store does so.
type ChallengeStore interface {
	CreateChallenge(context.Context, LinkChallenge) (LinkChallenge, error)
	LoadChallenge(context.Context, string) (LinkChallenge, error)
	ConsumeChallengeAndBind(context.Context, string, Subject, InstallationBinding, time.Time) error
}

// DeviceEligibility has no permissive default. Installed-panel HWID semantics
// must be proved before any non-test implementation may allow linking.
type DeviceEligibility interface {
	Allowed(context.Context, remnawave.UserSnapshot, InstallationBinding) (bool, error)
}

type DenyAllDeviceEligibility struct{}

func (DenyAllDeviceEligibility) Allowed(context.Context, remnawave.UserSnapshot, InstallationBinding) (bool, error) {
	return false, nil
}

// LinkService is internal association infrastructure, not an admission API.
type LinkService struct {
	panelInstance string
	resolver      LinkResolver
	challenges    ChallengeStore
	eligibility   DeviceEligibility
	now           func() time.Time
}

func NewLinkService(panelInstance string, resolver LinkResolver, challenges ChallengeStore, eligibility DeviceEligibility, now func() time.Time) (*LinkService, error) {
	if (Subject{PanelInstance: panelInstance, UserID: 1}).Validate() != nil || resolver == nil || challenges == nil {
		return nil, ErrLinkConfig
	}
	if eligibility == nil {
		eligibility = DenyAllDeviceEligibility{}
	}
	if now == nil {
		now = time.Now
	}
	return &LinkService{panelInstance, resolver, challenges, eligibility, now}, nil
}

// BeginLink resolves the existing bearer before creating a subject-bound
// challenge. Neither proof nor bearer alone grants network access.
func (s *LinkService) BeginLink(ctx context.Context, shortUUID, installationID string, publicKeyDER []byte) (LinkChallenge, error) {
	fingerprint, err := FingerprintLinkPublicKey(publicKeyDER)
	if err != nil {
		return LinkChallenge{}, ErrLinkDenied
	}
	binding := InstallationBinding{ID: installationID, PublicKeySHA256: fingerprint}
	if binding.Validate() != nil {
		return LinkChallenge{}, ErrLinkDenied
	}
	user, err := s.resolver.Lookup(ctx, shortUUID)
	if err != nil {
		return LinkChallenge{}, linkLookupError(err)
	}
	if err := s.checkEligibility(ctx, user, binding); err != nil {
		return LinkChallenge{}, err
	}
	subject := Subject{PanelInstance: s.panelInstance, UserID: user.ID}
	challenge, err := NewLinkChallenge(subject, binding)
	if err != nil {
		return LinkChallenge{}, ErrLinkUnavailable
	}
	challenge, err = s.challenges.CreateChallenge(ctx, challenge)
	if err != nil {
		return LinkChallenge{}, ErrLinkUnavailable
	}
	return challenge, nil
}

// CompleteLink checks possession before a second fresh bearer lookup, then
// requires the resolved canonical subject to equal the challenge's subject.
func (s *LinkService) CompleteLink(ctx context.Context, shortUUID, challengeID string, publicKeyDER, signature []byte) error {
	challenge, err := s.challenges.LoadChallenge(ctx, challengeID)
	if err != nil {
		return ErrLinkUnavailable
	}
	if challenge.Subject.PanelInstance != s.panelInstance {
		return ErrLinkDenied
	}
	binding, err := VerifyLinkProof(challenge, publicKeyDER, signature)
	if err != nil {
		return ErrLinkDenied
	}
	user, err := s.resolver.Lookup(ctx, shortUUID)
	if err != nil {
		return linkLookupError(err)
	}
	if user.ID != challenge.Subject.UserID {
		return ErrLinkDenied
	}
	if err := s.checkEligibility(ctx, user, binding); err != nil {
		return err
	}
	if err := s.challenges.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, binding, user.ExpiresAt); err != nil {
		return ErrLinkUnavailable
	}
	return nil
}

func (s *LinkService) checkEligibility(ctx context.Context, user remnawave.UserSnapshot, binding InstallationBinding) error {
	decision := policy.Evaluate(s.now(), user, policy.ProtocolFlag{})
	if !decision.OrdinaryAllowed {
		return ErrLinkDenied
	}
	allowed, err := s.eligibility.Allowed(ctx, user, binding)
	if err != nil {
		return ErrLinkUnavailable
	}
	if !allowed {
		return ErrLinkDenied
	}
	// Device eligibility may involve I/O; expiry can pass while it runs.
	if !policy.Evaluate(s.now(), user, policy.ProtocolFlag{}).OrdinaryAllowed {
		return ErrLinkDenied
	}
	return nil
}

func linkLookupError(err error) error {
	if errors.Is(err, remnawave.ErrNotFound) {
		return ErrLinkDenied
	}
	return ErrLinkUnavailable
}
