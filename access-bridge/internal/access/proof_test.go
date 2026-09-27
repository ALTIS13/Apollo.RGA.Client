package access

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"strings"
	"testing"
)

func proofFixture(t *testing.T) (*ecdsa.PrivateKey, []byte, LinkChallenge, []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("could not create test key")
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal("could not encode test public key")
	}
	fingerprint, err := FingerprintLinkPublicKey(der)
	if err != nil {
		t.Fatalf("fingerprint: %v", err)
	}
	challenge, err := NewLinkChallenge(
		Subject{PanelInstance: "rga-main", UserID: 42},
		InstallationBinding{ID: "installation_opaque_1234", PublicKeySHA256: fingerprint},
	)
	if err != nil {
		t.Fatalf("NewLinkChallenge: %v", err)
	}
	message, err := challenge.SigningPayload()
	if err != nil {
		t.Fatalf("SigningPayload: %v", err)
	}
	digest := sha256.Sum256(message)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal("could not sign challenge")
	}
	return key, der, challenge, signature
}

func TestP256SPKIDERSignatureProvesInstallationKey(t *testing.T) {
	_, der, challenge, signature := proofFixture(t)
	if challenge.ExpiresAt.IsZero() != true {
		t.Fatal("pure challenge constructor claimed database expiry")
	}
	if challenge.ID == "" || challenge.Nonce == ([32]byte{}) {
		t.Fatal("challenge lacks random identity or nonce")
	}
	bound, err := VerifyLinkProof(challenge, der, signature)
	if err != nil || bound != challenge.Installation {
		t.Fatalf("valid proof rejected: %+v, %v", bound, err)
	}
	other, err := NewLinkChallenge(challenge.Subject, challenge.Installation)
	if err != nil || other.ID == challenge.ID || other.Nonce == challenge.Nonce {
		t.Fatalf("challenge generator repeated identity or nonce: %v", err)
	}
}

func TestProofTranscriptBindsPanelInstallationChallengeAndFingerprint(t *testing.T) {
	_, der, challenge, signature := proofFixture(t)
	changes := []LinkChallenge{
		func() LinkChallenge { x := challenge; x.Subject.PanelInstance = "rga-other"; return x }(),
		func() LinkChallenge { x := challenge; x.Installation.ID = "installation_opaque_9999"; return x }(),
		func() LinkChallenge { x := challenge; x.ID = "different_challenge_id_12345"; return x }(),
		func() LinkChallenge { x := challenge; x.Nonce[0] ^= 1; return x }(),
		func() LinkChallenge { x := challenge; x.Installation.PublicKeySHA256[0] ^= 1; return x }(),
	}
	for _, changed := range changes {
		_, err := VerifyLinkProof(changed, der, signature)
		if !errors.Is(err, ErrInvalidProof) {
			t.Fatalf("altered challenge accepted: %v", err)
		}
	}
}

func TestProofRejectsDifferentKeyCurveAndNoncanonicalEncoding(t *testing.T) {
	_, der, challenge, signature := proofFixture(t)
	otherKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal("could not create test key")
	}
	otherDER, err := x509.MarshalPKIXPublicKey(&otherKey.PublicKey)
	if err != nil {
		t.Fatal("could not encode test key")
	}
	p384, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatal("could not create P-384 test key")
	}
	p384DER, err := x509.MarshalPKIXPublicKey(&p384.PublicKey)
	if err != nil {
		t.Fatal("could not encode P-384 test key")
	}
	for _, invalid := range [][]byte{otherDER, p384DER, append(bytes.Clone(der), 0), bytes.Repeat([]byte{1}, 257)} {
		if _, err := VerifyLinkProof(challenge, invalid, signature); !errors.Is(err, ErrInvalidProof) {
			t.Fatalf("different or noncanonical key accepted: %v", err)
		}
	}
	for _, invalid := range [][]byte{p384DER, append(bytes.Clone(der), 0), bytes.Repeat([]byte{1}, 257)} {
		if _, err := FingerprintLinkPublicKey(invalid); !errors.Is(err, ErrInvalidProof) {
			t.Fatalf("invalid public key fingerprinted: %v", err)
		}
	}
	if _, err := VerifyLinkProof(challenge, der, bytes.Repeat([]byte{1}, 81)); !errors.Is(err, ErrInvalidProof) {
		t.Fatalf("oversized signature accepted: %v", err)
	}
}

func TestProofAndChallengeValidationErrorsAreRedacted(t *testing.T) {
	_, der, challenge, signature := proofFixture(t)
	if _, err := NewLinkChallenge(challenge.Subject, InstallationBinding{ID: challenge.Installation.ID}); !errors.Is(err, ErrInvalidChallenge) {
		t.Fatalf("zero fingerprint accepted: %v", err)
	}
	invalid := challenge
	invalid.Subject.PanelInstance = "https://private.example.invalid"
	if _, err := VerifyLinkProof(invalid, der, signature); !errors.Is(err, ErrInvalidProof) || strings.Contains(err.Error(), "private.example.invalid") {
		t.Fatalf("invalid panel leaked in proof error: %v", err)
	}
	if _, err := VerifyLinkProof(challenge, der, []byte("subscription-secret")); !errors.Is(err, ErrInvalidProof) || strings.Contains(err.Error(), "subscription-secret") {
		t.Fatalf("invalid signature leaked in proof error: %v", err)
	}
	invalid = challenge
	invalid.Nonce = [32]byte{}
	if _, err := invalid.SigningPayload(); !errors.Is(err, ErrInvalidChallenge) {
		t.Fatalf("zero nonce accepted: %v", err)
	}
}
