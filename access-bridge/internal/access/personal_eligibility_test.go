package access

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

func TestExactPersonalInstallationEligibilityIsScopedToOnePanelUserAndKey(t *testing.T) {
	allowed := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: [32]byte{1}}
	gate, err := NewExactPersonalInstallationEligibility(
		Subject{PanelInstance: "rga-main", UserID: 42}, allowed,
	)
	if err != nil {
		t.Fatalf("NewExactPersonalInstallationEligibility: %v", err)
	}
	if gate.PanelInstance() != "rga-main" {
		t.Fatal("gate lost canonical panel scope")
	}
	for _, tc := range []struct {
		name    string
		userID  int64
		binding InstallationBinding
		want    bool
	}{
		{"exact", 42, allowed, true},
		{"other-user", 43, allowed, false},
		{"other-installation", 42, InstallationBinding{ID: "test_installation_other_01", PublicKeySHA256: [32]byte{1}}, false},
		{"other-key", 42, InstallationBinding{ID: allowed.ID, PublicKeySHA256: [32]byte{2}}, false},
		{"missing-key", 42, InstallationBinding{ID: allowed.ID}, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ok, err := gate.Allowed(context.Background(), remnawave.UserSnapshot{ID: tc.userID}, tc.binding)
			if err != nil || ok != tc.want {
				t.Fatalf("Allowed = %v, %v; want %v", ok, err, tc.want)
			}
		})
	}
}

func TestExactPersonalInstallationEligibilityDeniesMissingScopeAndMismatchedServicePanel(t *testing.T) {
	binding := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: [32]byte{1}}
	for _, tc := range []struct {
		subject Subject
		binding InstallationBinding
	}{
		{Subject{}, binding},
		{Subject{PanelInstance: "rga-main", UserID: 42}, InstallationBinding{}},
	} {
		if _, err := NewExactPersonalInstallationEligibility(tc.subject, tc.binding); !errors.Is(err, ErrLinkConfig) {
			t.Fatalf("invalid scope accepted: %v", err)
		}
	}
	gate, err := NewExactPersonalInstallationEligibility(Subject{PanelInstance: "another-panel", UserID: 42}, binding)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 9, 28, 0, 0, 0, 0, time.UTC)
	resolver := &testLinkResolver{users: map[string]remnawave.UserSnapshot{"bearer": activeLinkUser(now, 42)}}
	if _, err := NewLinkService("rga-main", resolver, &testLinkStore{}, gate, func() time.Time { return now }); !errors.Is(err, ErrLinkConfig) {
		t.Fatalf("eligibility scoped to another panel accepted: %v", err)
	}
}

func TestExactPersonalEligibilityStillRequiresProofForLink(t *testing.T) {
	now := time.Date(2026, 9, 28, 0, 0, 0, 0, time.UTC)
	key, der := testLinkKey(t)
	fingerprint, err := FingerprintLinkPublicKey(der)
	if err != nil {
		t.Fatal(err)
	}
	binding := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: fingerprint}
	gate, err := NewExactPersonalInstallationEligibility(Subject{PanelInstance: "rga-main", UserID: 42}, binding)
	if err != nil {
		t.Fatal(err)
	}
	store := &testLinkStore{}
	service, err := NewLinkService("rga-main", &testLinkResolver{users: map[string]remnawave.UserSnapshot{"bearer": activeLinkUser(now, 42)}}, store, gate, func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := service.BeginLink(context.Background(), "bearer", binding.ID, der)
	if err != nil {
		t.Fatalf("exact installation denied before proof: %v", err)
	}
	if store.consumeCalls != 0 {
		t.Fatal("begin linked installation without possession proof")
	}
	if err := service.CompleteLink(context.Background(), "bearer", challenge.ID, der, []byte("bad-signature")); !errors.Is(err, ErrLinkDenied) {
		t.Fatalf("bad proof accepted: %v", err)
	}
	if err := service.CompleteLink(context.Background(), "bearer", challenge.ID, der, signLinkChallenge(t, key, challenge)); err != nil {
		t.Fatalf("valid proof denied: %v", err)
	}
	if store.consumeCalls != 1 {
		t.Fatal("valid proof did not consume challenge exactly once")
	}
}
