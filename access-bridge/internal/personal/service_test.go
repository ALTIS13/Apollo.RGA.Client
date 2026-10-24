package personal

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

type fakePanel struct {
	user  remnawave.UserSnapshot
	err   error
	calls int
	id    int64
}

func (f *fakePanel) LookupByID(_ context.Context, id int64) (remnawave.UserSnapshot, error) {
	f.calls++
	f.id = id
	return f.user, f.err
}

type fakeStore struct {
	state       access.SubjectState
	loadErr     error
	redeemErr   error
	renewErr    error
	renewResult time.Time
	afterLoad   func()
	loadCalls   int
	redeemCalls int
	renewCalls  int
	last        store.QAJoinRequest
	lastRenew   store.QARenewRequest
	used        map[[32]byte]bool
}

func (f *fakeStore) Load(_ context.Context, _ access.Subject) (access.SubjectState, error) {
	f.loadCalls++
	if f.afterLoad != nil {
		f.afterLoad()
	}
	return f.state, f.loadErr
}

func (f *fakeStore) RedeemQAJoin(_ context.Context, req store.QAJoinRequest) error {
	f.redeemCalls++
	f.last = req
	if f.redeemErr != nil {
		return f.redeemErr
	}
	if f.used == nil {
		f.used = make(map[[32]byte]bool)
	}
	if f.used[req.Nonce] {
		return store.ErrQAJoinReplay
	}
	f.used[req.Nonce] = true
	return nil
}

func (f *fakeStore) RenewQADeadline(_ context.Context, req store.QARenewRequest) (time.Time, error) {
	f.renewCalls++
	f.lastRenew = req
	if f.renewErr != nil {
		return f.renewResult, f.renewErr
	}
	if !f.renewResult.IsZero() {
		return f.renewResult, nil
	}
	return req.NewDeadline, nil
}

func signedClaims(t *testing.T, issuedAt time.Time, room, country string) (access.InstallationBinding, map[string]any) {
	t.Helper()
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
	binding := access.InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: fingerprint}
	var nonce [32]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	issuedAtText := issuedAt.UTC().Format(time.RFC3339Nano)
	payload, err := access.PersonalJoinSigningPayload(binding, room, country, issuedAtText, nonce)
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(payload)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return binding, map[string]any{
		"installationId": binding.ID,
		"issuedAt":       issuedAtText,
		"nonce":          base64.RawURLEncoding.EncodeToString(nonce[:]),
		"publicKey":      base64.RawURLEncoding.EncodeToString(der),
		"signature":      base64.RawURLEncoding.EncodeToString(signature),
	}
}

func testService(t *testing.T) (*Service, map[string]any, *fakePanel, *fakeStore, *time.Time, access.PersonalTestConfig) {
	t.Helper()
	now := time.Date(2026, 9, 29, 10, 0, 0, 0, time.UTC)
	binding, claims := signedClaims(t, now, "test-room-000001", "NL")
	config := access.PersonalTestConfig{
		Subject:        access.Subject{PanelInstance: "rga-main", UserID: 42},
		Installation:   binding,
		RoomID:         "test-room-000001",
		Country:        "NL",
		LeaseTTL:       5 * time.Minute,
		MaxSnapshotAge: 15 * time.Second,
		SessionByteCap: 16 << 30,
	}
	panel := &fakePanel{user: remnawave.UserSnapshot{
		ID: 42, Status: remnawave.StatusActive, ExpiresAt: now.Add(2 * time.Minute),
		TrafficLimitBytes: 1000, TrafficLimitStrategy: remnawave.StrategyNoReset,
		UsedTrafficBytes: 100,
	}}
	state := &fakeStore{state: access.SubjectState{
		Subject: config.Subject, OlcRTCEnabled: true, Revision: 7,
	}}
	service, err := NewService(config, panel, state)
	if err != nil {
		t.Fatal(err)
	}
	service.now = func() time.Time { return now }
	return service, claims, panel, state, &now, config
}

func TestAdmitReturnsShortLeaseOnlyAfterCanonicalRefreshAndCommit(t *testing.T) {
	service, claims, panel, state, now, config := testService(t)
	service.entropy = bytes.NewReader(bytes.Repeat([]byte{7}, 32))
	lease, err := service.Admit(context.Background(), claims)
	if err != nil {
		t.Fatalf("valid join denied: %v", err)
	}
	if panel.calls != 1 || panel.id != config.Subject.UserID || state.loadCalls != 1 || state.redeemCalls != 1 {
		t.Fatalf("canonical refresh/commit missing: panel=%d id=%d load=%d redeem=%d", panel.calls, panel.id, state.loadCalls, state.redeemCalls)
	}
	if len(lease.SessionID) != 43 || lease.SessionID != state.last.SessionID || !lease.Deadline.Equal(now.Add(2*time.Minute)) || lease.ByteCap != 16<<30 {
		t.Fatalf("wrong committed lease: %+v", lease)
	}
	if state.last.Subject != config.Subject || state.last.InstallationID != config.Installation.ID ||
		state.last.PublicKeySHA256 != config.Installation.PublicKeySHA256 || state.last.RoomID != config.RoomID ||
		state.last.Country != config.Country || state.last.PolicyRevision != 7 ||
		state.last.SubscriptionExpiresAt != panel.user.ExpiresAt || state.last.ObservedAt != *now ||
		state.last.MaxSnapshotAge != config.MaxSnapshotAge || state.last.Nonce == ([32]byte{}) ||
		!state.last.ProofDeadline.Equal(time.Date(2026, 9, 29, 10, 3, 0, 0, time.UTC)) {
		t.Fatal("transaction lost server-owned binding or fresh facts")
	}
}

type clockAdvancingEntropy struct {
	now   *time.Time
	delay time.Duration
}

func (r clockAdvancingEntropy) Read(p []byte) (int, error) {
	*r.now = r.now.Add(r.delay)
	for i := range p {
		p[i] = 7
	}
	return len(p), nil
}

func TestAdmitCarriesOriginalProofDeadlineAfterEntropyWait(t *testing.T) {
	service, _, _, state, now, config := testService(t)
	// The signed proof has half a second left when it is verified. The
	// atomic-store refusal must not receive a newly restarted freshness window.
	binding, claims := signedClaims(t, now.Add(-179500*time.Millisecond), config.RoomID, config.Country)
	service.config.Installation = binding
	service.entropy = clockAdvancingEntropy{now: now, delay: time.Second}
	state.redeemErr = store.ErrQAJoinDenied
	lease, err := service.Admit(context.Background(), claims)
	if !errors.Is(err, ErrDenied) || lease != (SessionLease{}) || state.redeemCalls != 1 {
		t.Fatalf("atomic proof refusal returned a lease: %+v %v", lease, err)
	}
	want := time.Date(2026, 9, 29, 10, 0, 0, 500000000, time.UTC)
	if !state.last.ProofDeadline.Equal(want) || !now.After(state.last.ProofDeadline) {
		t.Fatal("atomic redemption lost or restarted the original signed-proof deadline")
	}
}

func TestAdmitRejectsWrongKeyRoomAndExpiredProofBeforeExternalCalls(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*testing.T, *Service, map[string]any, *time.Time)
	}{
		{"wrong key", func(t *testing.T, service *Service, _ map[string]any, now *time.Time) {
			other, _ := signedClaims(t, *now, service.config.RoomID, service.config.Country)
			service.config.Installation = other
		}},
		{"wrong room", func(_ *testing.T, service *Service, _ map[string]any, _ *time.Time) {
			service.config.RoomID = "other-room-000001"
		}},
		{"expired proof", func(_ *testing.T, _ *Service, _ map[string]any, now *time.Time) {
			*now = now.Add(4 * time.Minute)
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, claims, panel, state, now, _ := testService(t)
			tc.mutate(t, service, claims, now)
			lease, err := service.Admit(context.Background(), claims)
			if !errors.Is(err, ErrDenied) || lease != (SessionLease{}) || panel.calls != 0 || state.loadCalls != 0 || state.redeemCalls != 0 {
				t.Fatalf("invalid proof passed boundary: lease=%+v err=%v calls=%d/%d/%d", lease, err, panel.calls, state.loadCalls, state.redeemCalls)
			}
		})
	}
}

func TestAdmitDeniesDisabledLimitedExpiredAndStaleServerFacts(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*fakePanel, *fakeStore, *time.Time)
	}{
		{"disabled", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.Status = remnawave.StatusDisabled }},
		{"limited", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.Status = remnawave.StatusLimited }},
		{"expired", func(p *fakePanel, _ *fakeStore, now *time.Time) { p.user.ExpiresAt = *now }},
		{"exhausted", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.UsedTrafficBytes = p.user.TrafficLimitBytes }},
		{"wrong canonical id", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.ID = 43 }},
		{"flag off", func(_ *fakePanel, s *fakeStore, _ *time.Time) { s.state.OlcRTCEnabled = false }},
		{"stale revision", func(_ *fakePanel, s *fakeStore, _ *time.Time) { s.state.Revision = 0 }},
		{"stale snapshot", func(_ *fakePanel, s *fakeStore, now *time.Time) {
			s.afterLoad = func() { *now = now.Add(16 * time.Second) }
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, claims, panel, state, now, _ := testService(t)
			tc.mutate(panel, state, now)
			lease, err := service.Admit(context.Background(), claims)
			if !errors.Is(err, ErrDenied) || lease != (SessionLease{}) || state.redeemCalls != 0 {
				t.Fatalf("invalid facts reached commit: lease=%+v err=%v redeem=%d", lease, err, state.redeemCalls)
			}
		})
	}
}

func TestAdmitMapsPanelStoreAndCommitOutageWithoutLeakingErrors(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*fakePanel, *fakeStore)
	}{
		{"panel outage", func(p *fakePanel, _ *fakeStore) { p.err = fmt.Errorf("panel token-secret outage") }},
		{"store outage", func(_ *fakePanel, s *fakeStore) { s.loadErr = fmt.Errorf("store token-secret outage") }},
		{"ambiguous commit", func(_ *fakePanel, s *fakeStore) { s.redeemErr = fmt.Errorf("commit token-secret unknown") }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, claims, panel, state, _, _ := testService(t)
			tc.mutate(panel, state)
			lease, err := service.Admit(context.Background(), claims)
			if !errors.Is(err, ErrUnavailable) || lease != (SessionLease{}) || strings.Contains(err.Error(), "token-secret") {
				t.Fatalf("outage leaked lease or secret: lease=%+v err=%v", lease, err)
			}
		})
	}
}

func TestAdmitDeniesReplayEvenWithAnotherRandomSessionID(t *testing.T) {
	service, claims, _, _, _, _ := testService(t)
	service.entropy = bytes.NewReader(append(bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32)...))
	first, err := service.Admit(context.Background(), claims)
	if err != nil || first.SessionID == "" {
		t.Fatalf("first join failed: %+v %v", first, err)
	}
	second, err := service.Admit(context.Background(), claims)
	if !errors.Is(err, ErrDenied) || second != (SessionLease{}) {
		t.Fatalf("same signed nonce was reused: %+v %v", second, err)
	}
}

type failedEntropy struct{}

func (failedEntropy) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

func TestAdmitDoesNotCommitOrReturnLeaseWhenEntropyFails(t *testing.T) {
	service, claims, _, state, _, _ := testService(t)
	service.entropy = failedEntropy{}
	lease, err := service.Admit(context.Background(), claims)
	if !errors.Is(err, ErrUnavailable) || lease != (SessionLease{}) || state.redeemCalls != 0 {
		t.Fatalf("randomness failure reached commit: %+v %v calls=%d", lease, err, state.redeemCalls)
	}
}

func TestAdmitMapsAtomicRevisionFenceToDenial(t *testing.T) {
	service, claims, _, state, _, _ := testService(t)
	state.redeemErr = store.ErrQAJoinDenied
	lease, err := service.Admit(context.Background(), claims)
	if !errors.Is(err, ErrDenied) || lease != (SessionLease{}) || state.redeemCalls != 1 {
		t.Fatalf("revision/disable fence returned lease: %+v %v", lease, err)
	}
}

func TestNewServiceRejectsTypedNilDependencies(t *testing.T) {
	service, _, panel, state, _, config := testService(t)
	_ = service
	var nilPanel *remnawave.Client
	if _, err := NewService(config, nilPanel, state); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("typed-nil panel accepted: %v", err)
	}
	var nilStore *store.Store
	if _, err := NewService(config, panel, nilStore); !errors.Is(err, ErrInvalidConfig) {
		t.Fatalf("typed-nil store accepted: %v", err)
	}
}

func TestRenewUsesFreshCanonicalFactsAndCommittedDeadlineWithoutAnotherProof(t *testing.T) {
	service, claims, panel, state, now, config := testService(t)
	panel.user.ExpiresAt = now.Add(time.Hour)
	lease, err := service.Admit(context.Background(), claims)
	if err != nil {
		t.Fatal(err)
	}
	*now = now.Add(time.Minute)
	state.renewResult = now.Add(5 * time.Minute).Truncate(time.Microsecond)
	renewed, err := service.Renew(context.Background(), lease.SessionID)
	if err != nil || !renewed.Deadline.Equal(state.renewResult) || renewed.SessionID != lease.SessionID || renewed.ByteCap != config.SessionByteCap {
		t.Fatalf("renewal did not return committed lease: %+v %v", renewed, err)
	}
	if panel.calls != 2 || state.loadCalls != 2 || state.redeemCalls != 1 || state.renewCalls != 1 {
		t.Fatalf("renewal skipped fresh facts or repeated proof: panel=%d load=%d redeem=%d renew=%d",
			panel.calls, state.loadCalls, state.redeemCalls, state.renewCalls)
	}
	req := state.lastRenew
	if req.SessionID != lease.SessionID || req.Subject != config.Subject || req.InstallationID != config.Installation.ID ||
		req.PublicKeySHA256 != config.Installation.PublicKeySHA256 || req.RoomID != config.RoomID ||
		req.Country != config.Country || req.PolicyRevision != state.state.Revision ||
		req.SubscriptionExpiresAt != panel.user.ExpiresAt || req.ObservedAt != *now ||
		req.MaxSnapshotAge != config.MaxSnapshotAge || !req.NewDeadline.Equal(now.Add(5*time.Minute)) {
		t.Fatal("renewal lost server-owned scope or fresh canonical facts")
	}
}

func TestRenewRejectsInvalidSessionIDBeforeExternalCalls(t *testing.T) {
	for i, id := range []string{"", "qa-session-000001", strings.Repeat("A", 42) + "!", strings.Repeat("x", 44)} {
		t.Run(fmt.Sprintf("invalid-%d", i), func(t *testing.T) {
			service, _, panel, state, _, _ := testService(t)
			renewed, err := service.Renew(context.Background(), id)
			if !errors.Is(err, ErrDenied) || renewed != (SessionLease{}) || panel.calls != 0 || state.loadCalls != 0 || state.renewCalls != 0 {
				t.Fatalf("invalid ID reached external dependency: %+v %v %d/%d/%d", renewed, err, panel.calls, state.loadCalls, state.renewCalls)
			}
		})
	}
}

func TestRenewDeniesDisabledExpiredLimitedStaleOrChangedPolicy(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*fakePanel, *fakeStore, *time.Time)
	}{
		{"disabled", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.Status = remnawave.StatusDisabled }},
		{"limited", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.Status = remnawave.StatusLimited }},
		{"expired", func(p *fakePanel, _ *fakeStore, now *time.Time) { p.user.ExpiresAt = *now }},
		{"exhausted", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.UsedTrafficBytes = p.user.TrafficLimitBytes }},
		{"wrong canonical ID", func(p *fakePanel, _ *fakeStore, _ *time.Time) { p.user.ID++ }},
		{"flag off", func(_ *fakePanel, s *fakeStore, _ *time.Time) { s.state.OlcRTCEnabled = false }},
		{"stale revision", func(_ *fakePanel, s *fakeStore, _ *time.Time) { s.state.Revision = 0 }},
		{"stale snapshot", func(_ *fakePanel, s *fakeStore, now *time.Time) {
			s.afterLoad = func() { *now = now.Add(16 * time.Second) }
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, _, panel, state, now, _ := testService(t)
			id := base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
			tc.mutate(panel, state, now)
			renewed, err := service.Renew(context.Background(), id)
			if !errors.Is(err, ErrDenied) || renewed != (SessionLease{}) || state.renewCalls != 0 {
				t.Fatalf("invalid facts reached renewal store: %+v %v calls=%d", renewed, err, state.renewCalls)
			}
		})
	}
}

func TestRenewMapsOutageAndRevisionFenceWithoutReturningDeadline(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*fakePanel, *fakeStore)
		want   error
	}{
		{"panel outage", func(p *fakePanel, _ *fakeStore) { p.err = fmt.Errorf("panel secret-url outage") }, ErrUnavailable},
		{"store load outage", func(_ *fakePanel, s *fakeStore) { s.loadErr = fmt.Errorf("db secret-url outage") }, ErrUnavailable},
		{"ambiguous commit", func(_ *fakePanel, s *fakeStore) {
			s.renewResult = time.Now().Add(time.Minute)
			s.renewErr = fmt.Errorf("commit secret-url unknown")
		}, ErrUnavailable},
		{"mismatched acknowledgement", func(_ *fakePanel, s *fakeStore) {
			s.renewResult = time.Now().Add(time.Minute)
		}, ErrUnavailable},
		{"revision fence", func(_ *fakePanel, s *fakeStore) { s.renewErr = store.ErrQARenewDenied }, ErrDenied},
	} {
		t.Run(tc.name, func(t *testing.T) {
			service, _, panel, state, _, _ := testService(t)
			id := base64.RawURLEncoding.EncodeToString(bytes.Repeat([]byte{7}, 32))
			tc.mutate(panel, state)
			renewed, err := service.Renew(context.Background(), id)
			if !errors.Is(err, tc.want) || renewed != (SessionLease{}) || strings.Contains(err.Error(), "secret-url") {
				t.Fatalf("renewal failure leaked lease or secret: %+v %v", renewed, err)
			}
		})
	}
}
