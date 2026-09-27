package access

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

type testLinkResolver struct {
	users  map[string]remnawave.UserSnapshot
	errors map[string]error
	calls  int
}

func (r *testLinkResolver) Lookup(_ context.Context, bearer string) (remnawave.UserSnapshot, error) {
	r.calls++
	if err := r.errors[bearer]; err != nil {
		return remnawave.UserSnapshot{}, err
	}
	user, ok := r.users[bearer]
	if !ok {
		return remnawave.UserSnapshot{}, remnawave.ErrNotFound
	}
	return user, nil
}

type testLinkStore struct {
	challenge    LinkChallenge
	used         bool
	createCalls  int
	consumeCalls int
	createError  error
	loadError    error
	consumeError error
}

func (s *testLinkStore) CreateChallenge(_ context.Context, challenge LinkChallenge) (LinkChallenge, error) {
	s.createCalls++
	if s.createError != nil {
		return LinkChallenge{}, s.createError
	}
	challenge.ExpiresAt = time.Now().Add(5 * time.Minute)
	s.challenge = challenge
	return challenge, nil
}

func (s *testLinkStore) LoadChallenge(_ context.Context, id string) (LinkChallenge, error) {
	if s.loadError != nil {
		return LinkChallenge{}, s.loadError
	}
	if s.used || id != s.challenge.ID {
		return LinkChallenge{}, errors.New("missing or consumed challenge")
	}
	return s.challenge, nil
}

func (s *testLinkStore) ConsumeChallengeAndBind(_ context.Context, id string, subject Subject, binding InstallationBinding) error {
	s.consumeCalls++
	if s.consumeError != nil {
		return s.consumeError
	}
	if s.used || id != s.challenge.ID || subject != s.challenge.Subject || binding != s.challenge.Installation {
		return errors.New("challenge context mismatch")
	}
	s.used = true
	return nil
}

type testDeviceEligibility struct {
	allowed bool
	err     error
	calls   int
}

func (g *testDeviceEligibility) Allowed(_ context.Context, _ remnawave.UserSnapshot, _ InstallationBinding) (bool, error) {
	g.calls++
	return g.allowed, g.err
}

func testLinkKey(t *testing.T) (*ecdsa.PrivateKey, []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("could not generate test installation key")
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal("could not encode test installation key")
	}
	return key, der
}

func signLinkChallenge(t *testing.T, key *ecdsa.PrivateKey, challenge LinkChallenge) []byte {
	t.Helper()
	message, err := challenge.SigningPayload()
	if err != nil {
		t.Fatalf("SigningPayload: %v", err)
	}
	digest := sha256.Sum256(message)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal("could not sign test challenge")
	}
	return signature
}

func activeLinkUser(now time.Time, id int64) remnawave.UserSnapshot {
	return remnawave.UserSnapshot{
		ID:                   id,
		Status:               remnawave.StatusActive,
		ExpiresAt:            now.Add(time.Hour),
		TrafficLimitBytes:    1000,
		TrafficLimitStrategy: remnawave.StrategyNoReset,
		UsedTrafficBytes:     100,
	}
}

func TestLinkServiceRequiresConfiguredDependenciesAndDefaultsDeviceGateToDeny(t *testing.T) {
	now := time.Date(2026, 9, 27, 20, 0, 0, 0, time.UTC)
	resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{"bearer": activeLinkUser(now, 42)}}
	store := &testLinkStore{}
	if _, err := NewLinkService("https://invalid.example", resolver, store, nil, func() time.Time { return now }); !errors.Is(err, ErrLinkConfig) {
		t.Fatalf("URL used as panel instance: %v", err)
	}
	if _, err := NewLinkService("rga-main", nil, store, nil, func() time.Time { return now }); !errors.Is(err, ErrLinkConfig) {
		t.Fatalf("nil resolver accepted: %v", err)
	}
	service, err := NewLinkService("rga-main", resolver, store, nil, func() time.Time { return now })
	if err != nil {
		t.Fatalf("NewLinkService: %v", err)
	}
	_, der := testLinkKey(t)
	if _, err := service.BeginLink(context.Background(), "bearer", "installation_opaque_1234", der); !errors.Is(err, ErrLinkDenied) {
		t.Fatalf("unknown device eligibility allowed link: %v", err)
	}
	if store.createCalls != 0 {
		t.Fatal("default deny still created a challenge")
	}
}

func TestCompleteLinkVerifiesProofBeforeLookupAndRejectsAnotherValidAccount(t *testing.T) {
	now := time.Date(2026, 9, 27, 20, 0, 0, 0, time.UTC)
	resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{
		"first": activeLinkUser(now, 42),
		"other": activeLinkUser(now, 43),
	}}
	store := &testLinkStore{}
	gate := &testDeviceEligibility{allowed: true}
	service, err := NewLinkService("rga-main", resolver, store, gate, func() time.Time { return now })
	if err != nil {
		t.Fatalf("NewLinkService: %v", err)
	}
	key, der := testLinkKey(t)
	challenge, err := service.BeginLink(context.Background(), "first", "installation_opaque_1234", der)
	if err != nil || challenge.Subject != (Subject{PanelInstance: "rga-main", UserID: 42}) {
		t.Fatalf("BeginLink did not bind resolved subject: %+v, %v", challenge, err)
	}
	if resolver.calls != 1 || store.createCalls != 1 {
		t.Fatal("begin did not use one fresh resolver lookup before challenge")
	}
	if err := service.CompleteLink(context.Background(), "first", challenge.ID, der, []byte("wrong-signature")); !errors.Is(err, ErrLinkDenied) {
		t.Fatalf("invalid signature allowed completion: %v", err)
	}
	if resolver.calls != 1 || store.consumeCalls != 0 {
		t.Fatal("invalid signature reached bearer lookup or bind")
	}
	signature := signLinkChallenge(t, key, challenge)
	if err := service.CompleteLink(context.Background(), "other", challenge.ID, der, signature); !errors.Is(err, ErrLinkDenied) {
		t.Fatalf("other valid bearer substituted subject: %v", err)
	}
	if store.consumeCalls != 0 {
		t.Fatal("subject substitution reached atomic consume")
	}
	if err := service.CompleteLink(context.Background(), "first", challenge.ID, der, signature); err != nil {
		t.Fatalf("valid completion denied: %v", err)
	}
	if !store.used || store.consumeCalls != 1 {
		t.Fatal("valid completion did not use the one-time store consume")
	}
	if err := service.CompleteLink(context.Background(), "first", challenge.ID, der, signature); err == nil {
		t.Fatal("replayed completion accepted")
	}
}

func TestLinkDeniedForInactiveExpiredLimitedOrExhaustedSubscription(t *testing.T) {
	now := time.Date(2026, 9, 27, 20, 0, 0, 0, time.UTC)
	base := activeLinkUser(now, 42)
	cases := []struct {
		name string
		user remnawave.UserSnapshot
	}{
		{"disabled", func() remnawave.UserSnapshot { x := base; x.Status = remnawave.StatusDisabled; return x }()},
		{"expired-status", func() remnawave.UserSnapshot { x := base; x.Status = remnawave.StatusExpired; return x }()},
		{"expiry-equality", func() remnawave.UserSnapshot { x := base; x.ExpiresAt = now; return x }()},
		{"limited", func() remnawave.UserSnapshot { x := base; x.Status = remnawave.StatusLimited; return x }()},
		{"quota-equality", func() remnawave.UserSnapshot { x := base; x.UsedTrafficBytes = x.TrafficLimitBytes; return x }()},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{"bearer": tc.user}}
			store := &testLinkStore{}
			service, err := NewLinkService("rga-main", resolver, store, &testDeviceEligibility{allowed: true}, func() time.Time { return now })
			if err != nil {
				t.Fatalf("NewLinkService: %v", err)
			}
			_, der := testLinkKey(t)
			if _, err := service.BeginLink(context.Background(), "bearer", "installation_opaque_1234", der); !errors.Is(err, ErrLinkDenied) {
				t.Fatalf("ineligible subscription linked: %v", err)
			}
			if store.createCalls != 0 {
				t.Fatal("ineligible subscription created a challenge")
			}
		})
	}
}

func TestCompletionRechecksExpiryAndDeviceEligibility(t *testing.T) {
	now := time.Date(2026, 9, 27, 20, 0, 0, 0, time.UTC)
	resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{"bearer": activeLinkUser(now, 42)}}
	store := &testLinkStore{}
	gate := &testDeviceEligibility{allowed: true}
	service, err := NewLinkService("rga-main", resolver, store, gate, func() time.Time { return now })
	if err != nil {
		t.Fatalf("NewLinkService: %v", err)
	}
	key, der := testLinkKey(t)
	challenge, err := service.BeginLink(context.Background(), "bearer", "installation_opaque_1234", der)
	if err != nil {
		t.Fatalf("BeginLink: %v", err)
	}
	signature := signLinkChallenge(t, key, challenge)
	expired := activeLinkUser(now, 42)
	expired.ExpiresAt = now
	resolver.users["bearer"] = expired
	if err := service.CompleteLink(context.Background(), "bearer", challenge.ID, der, signature); !errors.Is(err, ErrLinkDenied) {
		t.Fatalf("expiry after begin ignored: %v", err)
	}
	resolver.users["bearer"] = activeLinkUser(now, 42)
	gate.allowed = false
	if err := service.CompleteLink(context.Background(), "bearer", challenge.ID, der, signature); !errors.Is(err, ErrLinkDenied) {
		t.Fatalf("device denial after begin ignored: %v", err)
	}
	if store.consumeCalls != 0 {
		t.Fatal("revoked account or device reached consume")
	}
}

func TestRotatedBearerFailsClosedAndSameCanonicalUserCanRetry(t *testing.T) {
	now := time.Date(2026, 9, 27, 20, 0, 0, 0, time.UTC)
	resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{
		"old-secret": activeLinkUser(now, 42),
		"new-secret": activeLinkUser(now, 42),
	}, errors: map[string]error{}}
	store := &testLinkStore{}
	service, err := NewLinkService("rga-main", resolver, store, &testDeviceEligibility{allowed: true}, func() time.Time { return now })
	if err != nil {
		t.Fatalf("NewLinkService: %v", err)
	}
	key, der := testLinkKey(t)
	challenge, err := service.BeginLink(context.Background(), "old-secret", "installation_opaque_1234", der)
	if err != nil {
		t.Fatalf("BeginLink: %v", err)
	}
	resolver.errors["old-secret"] = remnawave.ErrNotFound
	signature := signLinkChallenge(t, key, challenge)
	err = service.CompleteLink(context.Background(), "old-secret", challenge.ID, der, signature)
	if !errors.Is(err, ErrLinkDenied) || strings.Contains(err.Error(), "old-secret") {
		t.Fatalf("rotated bearer did not fail redacted: %v", err)
	}
	if err := service.CompleteLink(context.Background(), "new-secret", challenge.ID, der, signature); err != nil {
		t.Fatalf("new bearer for same canonical user rejected: %v", err)
	}
}

func TestPanelAndStoreFailuresNeverLeakInputOrCreateLink(t *testing.T) {
	now := time.Date(2026, 9, 27, 20, 0, 0, 0, time.UTC)
	_, der := testLinkKey(t)
	for _, panelErr := range []error{remnawave.ErrUnavailable, remnawave.ErrPanelAuthentication, errors.New("secret-transport-detail")} {
		resolver := &testLinkResolver{errors: map[string]error{"secret-bearer": panelErr}}
		store := &testLinkStore{}
		service, err := NewLinkService("rga-main", resolver, store, &testDeviceEligibility{allowed: true}, func() time.Time { return now })
		if err != nil {
			t.Fatalf("NewLinkService: %v", err)
		}
		_, err = service.BeginLink(context.Background(), "secret-bearer", "installation_opaque_1234", der)
		if !errors.Is(err, ErrLinkUnavailable) || strings.Contains(err.Error(), "secret") || store.createCalls != 0 {
			t.Fatalf("panel failure leaked or created challenge: %v", err)
		}
	}
	resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{"secret-bearer": activeLinkUser(now, 42)}}
	store := &testLinkStore{createError: errors.New("secret database connection")}
	service, err := NewLinkService("rga-main", resolver, store, &testDeviceEligibility{allowed: true}, func() time.Time { return now })
	if err != nil {
		t.Fatalf("NewLinkService: %v", err)
	}
	if _, err := service.BeginLink(context.Background(), "secret-bearer", "installation_opaque_1234", der); !errors.Is(err, ErrLinkUnavailable) || strings.Contains(err.Error(), "secret") {
		t.Fatalf("store failure leaked: %v", err)
	}
}
