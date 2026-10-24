package access

import (
	"encoding/base64"
	"testing"
	"time"
)

// This is the fixed Android host-test vector (public fixture key d=1, test-only k=1).
// The mobile test asserts every emitted flat claim; no production credential is here.
func TestAndroidFixedPersonalJoinVector(t *testing.T) {
	const publicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q"
	const signature = "MEUCIGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWAiEA4HKtt6HnAsMZ7iY_boNfdaK-dhtBILFlNJMXpTihjJM"
	publicDER, err := base64.RawURLEncoding.DecodeString(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	fingerprint, err := FingerprintLinkPublicKey(publicDER)
	if err != nil {
		t.Fatal(err)
	}
	binding := InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: fingerprint}
	claims := map[string]any{
		"installationId": binding.ID,
		"issuedAt":       "2026-09-28T19:00:00Z",
		"nonce":          "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
		"publicKey":      publicKey,
		"signature":      signature,
	}
	now := time.Date(2026, time.September, 28, 19, 0, 0, 0, time.UTC)
	proof, err := VerifyPersonalJoinProof(now, binding, "test-room-000001", "NL", claims)
	if err != nil {
		t.Fatalf("Android fixed vector rejected: %v", err)
	}
	if proof.Installation != binding || proof.IssuedAt != now || proof.Nonce[0] != 0 || proof.Nonce[31] != 31 {
		t.Fatal("verified proof did not preserve fixed vector binding, time or nonce")
	}
}
