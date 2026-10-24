package personal

import (
	"context"
	"errors"
	"regexp"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/policy"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

var (
	ErrBootstrapConfig      = errors.New("personal: invalid bootstrap configuration")
	ErrBootstrapDenied      = errors.New("personal: bootstrap denied")
	ErrBootstrapUnavailable = errors.New("personal: bootstrap unavailable")
	bootstrapRoomID         = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)
)

// BootstrapConfig is immutable server-owned configuration for one A063 test
// installation and an existing dedicated room. Never populate it from a
// subscription URL, a client-selected room or an OlcRTC node request.
type BootstrapConfig struct {
	Subject      access.Subject
	Installation access.InstallationBinding
	RoomID       string
	Country      string
	RoomKey      string // Exactly 64 lowercase hex characters for native setKey.
}

func (BootstrapConfig) String() string   { return "BootstrapConfig{redacted}" }
func (BootstrapConfig) GoString() string { return "BootstrapConfig{redacted}" }

// BootstrapV1 is an Android prefetch payload, not a network grant. The room
// key must be protected on the client; the node independently verifies every
// join against a fresh short-lived personal admission.
type BootstrapV1 struct {
	SchemaVersion    int    `json:"schemaVersion"`
	Provider         string `json:"provider"`
	Transport        string `json:"transport"`
	RoomID           string `json:"roomId"`
	Country          string `json:"country"`
	RoomKey          string `json:"roomKey"`
	PolicyRevision   int64  `json:"policyRevision"`
	ExpiresAtEpochMs int64  `json:"expiresAtEpochMs"`
}

func (BootstrapV1) String() string   { return "BootstrapV1{redacted}" }
func (BootstrapV1) GoString() string { return "BootstrapV1{redacted}" }

type bootstrapLinker interface {
	CompleteLink(context.Context, string, string, []byte, []byte) error
}

type bootstrapStateReader interface {
	LoadChallenge(context.Context, string) (access.LinkChallenge, error)
	LoadPersonalBootstrapState(context.Context, access.Subject, access.InstallationBinding) (access.SubjectState, error)
}

type BootstrapService struct {
	config BootstrapConfig
	linker bootstrapLinker
	state  bootstrapStateReader
	panel  Panel
	now    func() time.Time
}

func NewBootstrapService(config BootstrapConfig, linker bootstrapLinker, state bootstrapStateReader, panel Panel) (*BootstrapService, error) {
	if config.Subject.Validate() != nil || config.Installation.Validate() != nil ||
		!bootstrapRoomID.MatchString(config.RoomID) || len(config.Country) != 2 ||
		config.Country[0] < 'A' || config.Country[0] > 'Z' ||
		config.Country[1] < 'A' || config.Country[1] > 'Z' ||
		!validBootstrapRoomKey(config.RoomKey) ||
		nilDependency(linker) || nilDependency(state) || nilDependency(panel) {
		return nil, ErrBootstrapConfig
	}
	return &BootstrapService{config: config, linker: linker, state: state, panel: panel, now: time.Now}, nil
}

func validBootstrapRoomKey(key string) bool {
	if len(key) != 64 {
		return false
	}
	nonZero := false
	for i := 0; i < len(key); i++ {
		c := key[i]
		if c != '0' {
			nonZero = true
		}
		if c < '0' || c > '9' && (c < 'a' || c > 'f') {
			return false
		}
	}
	return nonZero
}

// CompleteLinkAndPrefetch consumes a fresh, signed link challenge using the
// existing subscription bearer, then rechecks the canonical user and exact
// QA-enabled installation before returning room parameters. The bearer is
// never stored in the result or forwarded to an OlcRTC node. A subsequent
// prefetch uses a new challenge for the same already-linked installation.
func (s *BootstrapService) CompleteLinkAndPrefetch(ctx context.Context, shortUUID, challengeID string, publicKeyDER, signature []byte) (BootstrapV1, error) {
	if s == nil || ctx == nil || ctx.Err() != nil {
		return BootstrapV1{}, ErrBootstrapUnavailable
	}
	if shortUUID == "" || len(shortUUID) > 256 {
		return BootstrapV1{}, ErrBootstrapDenied
	}
	challenge, err := s.state.LoadChallenge(ctx, challengeID)
	if err != nil {
		if errors.Is(err, store.ErrChallengeInvalid) {
			return BootstrapV1{}, ErrBootstrapDenied
		}
		return BootstrapV1{}, ErrBootstrapUnavailable
	}
	if challenge.ID != challengeID || challenge.Subject != s.config.Subject ||
		challenge.Installation != s.config.Installation || !s.now().Before(challenge.ExpiresAt) {
		return BootstrapV1{}, ErrBootstrapDenied
	}
	binding, err := access.VerifyLinkProof(challenge, publicKeyDER, signature)
	if err != nil || binding != s.config.Installation {
		return BootstrapV1{}, ErrBootstrapDenied
	}
	if err := s.linker.CompleteLink(ctx, shortUUID, challengeID, publicKeyDER, signature); err != nil {
		if errors.Is(err, access.ErrLinkDenied) {
			return BootstrapV1{}, ErrBootstrapDenied
		}
		return BootstrapV1{}, ErrBootstrapUnavailable
	}
	user, err := s.panel.LookupByID(ctx, s.config.Subject.UserID)
	if err != nil {
		if errors.Is(err, remnawave.ErrNotFound) {
			return BootstrapV1{}, ErrBootstrapDenied
		}
		return BootstrapV1{}, ErrBootstrapUnavailable
	}
	if user.ID != s.config.Subject.UserID || !policy.Evaluate(s.now(), user, policy.ProtocolFlag{}).OrdinaryAllowed {
		return BootstrapV1{}, ErrBootstrapDenied
	}
	state, err := s.state.LoadPersonalBootstrapState(ctx, s.config.Subject, s.config.Installation)
	if err != nil {
		if errors.Is(err, store.ErrPersonalBootstrapDenied) || errors.Is(err, store.ErrNotFound) {
			return BootstrapV1{}, ErrBootstrapDenied
		}
		return BootstrapV1{}, ErrBootstrapUnavailable
	}
	if ctx.Err() != nil {
		return BootstrapV1{}, ErrBootstrapUnavailable
	}
	if state.Subject != s.config.Subject || !state.OlcRTCEnabled || state.Revision <= 0 ||
		!policy.Evaluate(s.now(), user, policy.ProtocolFlag{}).OrdinaryAllowed {
		return BootstrapV1{}, ErrBootstrapDenied
	}
	return BootstrapV1{
		SchemaVersion: 1, Provider: "telemost", Transport: "vp8channel",
		RoomID: s.config.RoomID, Country: s.config.Country, RoomKey: s.config.RoomKey,
		PolicyRevision: state.Revision, ExpiresAtEpochMs: user.ExpiresAt.UnixMilli(),
	}, nil
}
