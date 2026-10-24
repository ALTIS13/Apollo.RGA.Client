package personal

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

type fakeBootstrapLinker struct {
	err   error
	calls int
}

func (f *fakeBootstrapLinker) CompleteLink(context.Context, string, string, []byte, []byte) error {
	f.calls++
	return f.err
}

type fakeBootstrapState struct {
	challenge    access.LinkChallenge
	challengeErr error
	state        access.SubjectState
	stateErr     error
	afterState   func()
	loadCalls    int
	stateCalls   int
	lastSubject  access.Subject
	lastBinding  access.InstallationBinding
}

func (f *fakeBootstrapState) LoadChallenge(_ context.Context, _ string) (access.LinkChallenge, error) {
	f.loadCalls++
	return f.challenge, f.challengeErr
}

func (f *fakeBootstrapState) LoadPersonalBootstrapState(_ context.Context, subject access.Subject, binding access.InstallationBinding) (access.SubjectState, error) {
	f.stateCalls++
	f.lastSubject, f.lastBinding = subject, binding
	if f.afterState != nil {
		f.afterState()
	}
	return f.state, f.stateErr
}

func bootstrapFixture(t *testing.T) (*BootstrapService, *fakeBootstrapLinker, *fakeBootstrapState, *fakePanel, *time.Time, []byte, []byte, BootstrapConfig) {
	t.Helper()
	now := time.Date(2026, 9, 29, 10, 0, 0, 0, time.UTC)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	fingerprint, err := access.FingerprintLinkPublicKey(der)
	if err != nil {
		t.Fatal(err)
	}
	config := BootstrapConfig{
		Subject: access.Subject{PanelInstance: "rga-main", UserID: 42},
		Installation: access.InstallationBinding{
			ID: "test_installation_A063_01", PublicKeySHA256: fingerprint,
		},
		RoomID: "test-room-000001", Country: "NL", RoomKey: strings.Repeat("ab", 32),
	}
	challenge, err := access.NewLinkChallenge(config.Subject, config.Installation)
	if err != nil {
		t.Fatal(err)
	}
	challenge.ExpiresAt = now.Add(5 * time.Minute)
	payload, err := challenge.SigningPayload()
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(payload)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	linker := &fakeBootstrapLinker{}
	state := &fakeBootstrapState{
		challenge: challenge,
		state:     access.SubjectState{Subject: config.Subject, OlcRTCEnabled: true, Revision: 7},
	}
	panel := &fakePanel{user: remnawave.UserSnapshot{
		ID: 42, Status: remnawave.StatusActive, ExpiresAt: now.Add(time.Hour),
		TrafficLimitBytes: 1000, TrafficLimitStrategy: remnawave.StrategyNoReset,
		UsedTrafficBytes: 100,
	}}
	service, err := NewBootstrapService(config, linker, state, panel)
	if err != nil {
		t.Fatal(err)
	}
	service.now = func() time.Time { return now }
	return service, linker, state, panel, &now, der, signature, config
}

func TestBootstrapOnlyAfterExactCompletedLinkAndFreshCanonicalState(t *testing.T) {
	service, linker, state, panel, now, der, signature, config := bootstrapFixture(t)
	got, err := service.CompleteLinkAndPrefetch(context.Background(), "test-short-uuid", state.challenge.ID, der, signature)
	if err != nil {
		t.Fatalf("exact bootstrap denied: %v", err)
	}
	if linker.calls != 1 || state.loadCalls != 1 || state.stateCalls != 1 || panel.calls != 1 || panel.id != config.Subject.UserID ||
		state.lastSubject != config.Subject || state.lastBinding != config.Installation {
		t.Fatal("PoP link, canonical refresh or exact bound QA state was skipped")
	}
	if got.SchemaVersion != 1 || got.Provider != "telemost" || got.Transport != "vp8channel" ||
		got.RoomID != config.RoomID || got.Country != config.Country || got.RoomKey != config.RoomKey ||
		got.PolicyRevision != 7 || got.ExpiresAtEpochMs != now.Add(time.Hour).UnixMilli() {
		t.Fatal("bootstrap wire fields do not match server-owned room and subscription")
	}
	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	var wire map[string]any
	if err := json.Unmarshal(encoded, &wire); err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{"schemaVersion", "provider", "transport", "roomId", "country", "roomKey", "policyRevision", "expiresAtEpochMs"} {
		if _, ok := wire[field]; !ok {
			t.Fatalf("missing Android wire field %q", field)
		}
	}
	if len(wire) != 8 || strings.Contains(string(encoded), "test-short-uuid") || strings.Contains(string(encoded), "admin-token") {
		t.Fatal("bootstrap carried a bearer, admin token or unexpected field")
	}
	if strings.Contains(fmt.Sprintf("%+v", got), config.RoomKey) || strings.Contains(fmt.Sprintf("%#v", got), config.RoomKey) {
		t.Fatal("ordinary formatting exposed room key")
	}
}

func TestBootstrapRejectsWrongChallengeBindingOrProofBeforeLink(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*fakeBootstrapState, *[]byte)
	}{
		{"wrong user", func(s *fakeBootstrapState, _ *[]byte) { s.challenge.Subject.UserID++ }},
		{"wrong installation", func(s *fakeBootstrapState, _ *[]byte) { s.challenge.Installation.ID = "other-installation-000001" }},
		{"wrong key", func(s *fakeBootstrapState, _ *[]byte) { s.challenge.Installation.PublicKeySHA256[0] ^= 1 }},
		{"wrong challenge ID", func(s *fakeBootstrapState, _ *[]byte) { s.challenge.ID = strings.Repeat("A", 43) }},
		{"invalid signature", func(_ *fakeBootstrapState, sig *[]byte) { *sig = []byte("invalid-signature") }},
		{"expired challenge", func(s *fakeBootstrapState, _ *[]byte) {
			s.challenge.ExpiresAt = time.Date(2026, 9, 29, 9, 59, 59, 0, time.UTC)
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, linker, state, panel, _, der, signature, config := bootstrapFixture(t)
			tc.mutate(state, &signature)
			got, err := service.CompleteLinkAndPrefetch(context.Background(), "test-short-uuid", state.challenge.ID, der, signature)
			if !errors.Is(err, ErrBootstrapDenied) || got != (BootstrapV1{}) || linker.calls != 0 || panel.calls != 0 || state.stateCalls != 0 ||
				strings.Contains(err.Error(), config.RoomKey) {
				t.Fatalf("wrong challenge reached link or leaked secret: %+v %v", got, err)
			}
		})
	}
}

func TestBootstrapRejectsInvalidOrUnavailableChallengeWithoutCallingLink(t *testing.T) {
	for _, tc := range []struct {
		name string
		err  error
		want error
	}{
		{"consumed", store.ErrChallengeInvalid, ErrBootstrapDenied},
		{"database outage", fmt.Errorf("secret-db-failure"), ErrBootstrapUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, linker, state, panel, _, der, signature, config := bootstrapFixture(t)
			state.challengeErr = tc.err
			got, err := service.CompleteLinkAndPrefetch(context.Background(), "test-short-uuid", state.challenge.ID, der, signature)
			if !errors.Is(err, tc.want) || got != (BootstrapV1{}) || linker.calls != 0 || panel.calls != 0 ||
				strings.Contains(err.Error(), "secret-") || strings.Contains(err.Error(), config.RoomKey) {
				t.Fatalf("invalid challenge reached link or leaked secret: %+v %v", got, err)
			}
		})
	}
}

func TestBootstrapDeniesInactiveUserDisabledStateAndOutagesWithoutRoomKey(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*fakeBootstrapLinker, *fakeBootstrapState, *fakePanel, *time.Time)
		want   error
	}{
		{"link denied", func(l *fakeBootstrapLinker, _ *fakeBootstrapState, _ *fakePanel, _ *time.Time) {
			l.err = access.ErrLinkDenied
		}, ErrBootstrapDenied},
		{"link outage", func(l *fakeBootstrapLinker, _ *fakeBootstrapState, _ *fakePanel, _ *time.Time) {
			l.err = fmt.Errorf("secret-link-outage")
		}, ErrBootstrapUnavailable},
		{"disabled", func(_ *fakeBootstrapLinker, _ *fakeBootstrapState, p *fakePanel, _ *time.Time) {
			p.user.Status = remnawave.StatusDisabled
		}, ErrBootstrapDenied},
		{"limited", func(_ *fakeBootstrapLinker, _ *fakeBootstrapState, p *fakePanel, _ *time.Time) {
			p.user.Status = remnawave.StatusLimited
		}, ErrBootstrapDenied},
		{"expired", func(_ *fakeBootstrapLinker, _ *fakeBootstrapState, p *fakePanel, now *time.Time) {
			p.user.ExpiresAt = *now
		}, ErrBootstrapDenied},
		{"exhausted", func(_ *fakeBootstrapLinker, _ *fakeBootstrapState, p *fakePanel, _ *time.Time) {
			p.user.UsedTrafficBytes = p.user.TrafficLimitBytes
		}, ErrBootstrapDenied},
		{"wrong canonical user", func(_ *fakeBootstrapLinker, _ *fakeBootstrapState, p *fakePanel, _ *time.Time) { p.user.ID++ }, ErrBootstrapDenied},
		{"panel outage", func(_ *fakeBootstrapLinker, _ *fakeBootstrapState, p *fakePanel, _ *time.Time) {
			p.err = fmt.Errorf("secret-panel-outage")
		}, ErrBootstrapUnavailable},
		{"flag or QA off", func(_ *fakeBootstrapLinker, s *fakeBootstrapState, _ *fakePanel, _ *time.Time) {
			s.stateErr = store.ErrPersonalBootstrapDenied
		}, ErrBootstrapDenied},
		{"wrong stored subject", func(_ *fakeBootstrapLinker, s *fakeBootstrapState, _ *fakePanel, _ *time.Time) {
			s.state.Subject.UserID++
		}, ErrBootstrapDenied},
		{"stale stored revision", func(_ *fakeBootstrapLinker, s *fakeBootstrapState, _ *fakePanel, _ *time.Time) {
			s.state.Revision = 0
		}, ErrBootstrapDenied},
		{"store outage", func(_ *fakeBootstrapLinker, s *fakeBootstrapState, _ *fakePanel, _ *time.Time) {
			s.stateErr = fmt.Errorf("secret-store-outage")
		}, ErrBootstrapUnavailable},
		{"expiry during state load", func(_ *fakeBootstrapLinker, s *fakeBootstrapState, p *fakePanel, now *time.Time) {
			p.user.ExpiresAt = now.Add(time.Second)
			s.afterState = func() { *now = now.Add(2 * time.Second) }
		}, ErrBootstrapDenied},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, linker, state, panel, now, der, signature, config := bootstrapFixture(t)
			tc.mutate(linker, state, panel, now)
			got, err := service.CompleteLinkAndPrefetch(context.Background(), "test-short-uuid", state.challenge.ID, der, signature)
			if !errors.Is(err, tc.want) || got != (BootstrapV1{}) || strings.Contains(err.Error(), "secret-") || strings.Contains(err.Error(), config.RoomKey) {
				t.Fatalf("ineligible bootstrap returned room or leaked secret: %+v %v", got, err)
			}
		})
	}
}

func TestBootstrapRejectsInvalidServerRoomConfiguration(t *testing.T) {
	service, linker, state, panel, _, _, _, config := bootstrapFixture(t)
	_ = service
	for _, mutate := range []func(*BootstrapConfig){
		func(c *BootstrapConfig) { c.RoomID = "https://example.invalid/room" },
		func(c *BootstrapConfig) { c.Country = "nl" },
		func(c *BootstrapConfig) { c.RoomKey = "" },
		func(c *BootstrapConfig) { c.RoomKey = "key\nsecret" },
		func(c *BootstrapConfig) { c.RoomKey = string([]byte{0xff, 0xfe}) },
		func(c *BootstrapConfig) { c.RoomKey = strings.Repeat("AB", 32) },
		func(c *BootstrapConfig) { c.RoomKey = "z" + strings.Repeat("a", 63) },
		func(c *BootstrapConfig) { c.RoomKey = strings.Repeat("a", 63) },
		func(c *BootstrapConfig) { c.RoomKey = strings.Repeat("0", 64) },
	} {
		bad := config
		mutate(&bad)
		if _, err := NewBootstrapService(bad, linker, state, panel); !errors.Is(err, ErrBootstrapConfig) || strings.Contains(err.Error(), config.RoomKey) {
			t.Fatalf("invalid server room accepted or leaked key: %v", err)
		}
	}
}

func TestBootstrapCancellationDuringStateRefreshReturnsNoRoom(t *testing.T) {
	service, _, state, _, _, der, signature, _ := bootstrapFixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	state.afterState = cancel
	got, err := service.CompleteLinkAndPrefetch(ctx, "test-short-uuid", state.challenge.ID, der, signature)
	if !errors.Is(err, ErrBootstrapUnavailable) || got != (BootstrapV1{}) {
		t.Fatalf("cancelled refresh returned bootstrap: %+v %v", got, err)
	}
}
