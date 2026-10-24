package access

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"testing"
	"time"
)

func signedPersonalJoinClaims(t *testing.T, now time.Time, room, country string) (InstallationBinding, map[string]any) {
	t.Helper()
	key, der := testLinkKey(t)
	fingerprint, err := FingerprintLinkPublicKey(der)
	if err != nil {
		t.Fatal(err)
	}
	binding := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: fingerprint}
	var nonce [32]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	issuedAt := now.UTC().Format(time.RFC3339Nano)
	payload, err := PersonalJoinSigningPayload(binding, room, country, issuedAt, nonce)
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
		"issuedAt":       issuedAt,
		"nonce":          base64.RawURLEncoding.EncodeToString(nonce[:]),
		"publicKey":      base64.RawURLEncoding.EncodeToString(der),
		"signature":      base64.RawURLEncoding.EncodeToString(signature),
	}
}

func TestPersonalJoinProofBindsInstallationRoomCountryAndFreshNonce(t *testing.T) {
	now := time.Date(2026, 9, 28, 19, 0, 0, 0, time.UTC)
	binding, claims := signedPersonalJoinClaims(t, now, "test-room-000001", "NL")
	proof, err := VerifyPersonalJoinProof(now, binding, "test-room-000001", "NL", claims)
	if err != nil || proof.IssuedAt != now || proof.Nonce == ([32]byte{}) {
		t.Fatalf("valid signed proof rejected: %+v, %v", proof, err)
	}
	if proof.Installation != binding {
		t.Fatal("verified proof lost exact installation")
	}
	for _, tc := range []struct {
		name    string
		binding InstallationBinding
		room    string
		country string
		at      time.Time
		mutate  func(map[string]any)
	}{
		{"wrong-key", InstallationBinding{ID: binding.ID, PublicKeySHA256: [32]byte{1}}, "test-room-000001", "NL", now, nil},
		{"wrong-room", binding, "other-room-000001", "NL", now, nil},
		{"wrong-country", binding, "test-room-000001", "DE", now, nil},
		{"expired", binding, "test-room-000001", "NL", now.Add(4 * time.Minute), nil},
		{"future", binding, "test-room-000001", "NL", now.Add(-4 * time.Minute), nil},
		{"changed-installation", binding, "test-room-000001", "NL", now, func(c map[string]any) { c["installationId"] = "test_installation_other_01" }},
		{"changed-nonce", binding, "test-room-000001", "NL", now, func(c map[string]any) { c["nonce"] = base64.RawURLEncoding.EncodeToString(make([]byte, 32)) }},
		{"changed-signature", binding, "test-room-000001", "NL", now, func(c map[string]any) { c["signature"] = "bad" }},
		{"unexpected-claim", binding, "test-room-000001", "NL", now, func(c map[string]any) { c["admin"] = true }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			copyClaims := make(map[string]any, len(claims))
			for k, v := range claims {
				copyClaims[k] = v
			}
			if tc.mutate != nil {
				tc.mutate(copyClaims)
			}
			if _, err := VerifyPersonalJoinProof(tc.at, tc.binding, tc.room, tc.country, copyClaims); !errors.Is(err, ErrInvalidPersonalJoinProof) {
				t.Fatalf("invalid proof accepted: %v", err)
			}
		})
	}
}

func TestPersonalJoinSigningPayloadRejectsInvalidFields(t *testing.T) {
	binding := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: [32]byte{1}}
	for _, tc := range []struct {
		room, country, issuedAt string
		nonce                   [32]byte
	}{
		{"https://example.invalid", "NL", "2026-09-28T19:00:00Z", [32]byte{1}},
		{"test-room-000001", "nl", "2026-09-28T19:00:00Z", [32]byte{1}},
		{"test-room-000001", "NL", "not-time", [32]byte{1}},
		{"test-room-000001", "NL", "2026-09-28T19:00:00Z", [32]byte{}},
	} {
		if _, err := PersonalJoinSigningPayload(binding, tc.room, tc.country, tc.issuedAt, tc.nonce); !errors.Is(err, ErrInvalidPersonalJoinProof) {
			t.Fatalf("invalid signing payload accepted: %v", err)
		}
	}
}
