package access

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"time"
)

var ErrInvalidPersonalJoinProof = errors.New("access: invalid personal join proof")

const (
	personalJoinDomain = "Apollo.RGA/olcrtc-test-join/v1"
	maxJoinClockSkew   = 3 * time.Minute
)

// PersonalJoinProof proves possession of one installation key for one room.
// It is NOT a grant: the server must atomically redeem Nonce once, look up the
// linked subject by installation ID, check fresh panel facts and then register
// the short managed session before returning a lease to OlcRTC.
type PersonalJoinProof struct {
	Installation InstallationBinding
	Nonce        [32]byte
	IssuedAt     time.Time
	// Immutable upper freshness boundary derived from the signed IssuedAt.
	// Atomic redemption must recheck it after waiting for database locks.
	ProofDeadline time.Time
}

// PersonalJoinSigningPayload is the exact cross-platform transcript signed by
// the Android Keystore P-256 key. The payload contains no subscription URL,
// room credential or admin token. Each field is uint16-length-prefixed.
func PersonalJoinSigningPayload(binding InstallationBinding, room, country, issuedAt string, nonce [32]byte) ([]byte, error) {
	if binding.Validate() != nil || !personalRoomPattern.MatchString(room) ||
		len(country) != 2 || country[0] < 'A' || country[0] > 'Z' ||
		country[1] < 'A' || country[1] > 'Z' || nonce == ([32]byte{}) {
		return nil, ErrInvalidPersonalJoinProof
	}
	parsed, err := time.Parse(time.RFC3339Nano, issuedAt)
	if err != nil || parsed.UTC().Format(time.RFC3339Nano) != issuedAt {
		return nil, ErrInvalidPersonalJoinProof
	}
	var transcript bytes.Buffer
	transcript.WriteString(personalJoinDomain)
	transcript.WriteByte(0)
	for _, field := range [][]byte{
		[]byte(binding.ID), binding.PublicKeySHA256[:],
		[]byte(room), []byte(country), []byte(issuedAt), nonce[:],
	} {
		var size [2]byte
		binary.BigEndian.PutUint16(size[:], uint16(len(field)))
		transcript.Write(size[:])
		transcript.Write(field)
	}
	return transcript.Bytes(), nil
}

// VerifyPersonalJoinProof accepts only five flat, bounded string claims. The
// caller must obtain expectedBinding from the trusted Bridge installation row
// and room/country from server config, never from these untrusted claims.
func VerifyPersonalJoinProof(now time.Time, expectedBinding InstallationBinding, room, country string, claims map[string]any) (PersonalJoinProof, error) {
	if now.IsZero() || expectedBinding.Validate() != nil || len(claims) != 5 {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	read := func(key string, maxLength int) (string, bool) {
		value, ok := claims[key].(string)
		return value, ok && value != "" && len(value) <= maxLength
	}
	installationID, idOK := read("installationId", 128)
	issuedAtText, timeOK := read("issuedAt", 40)
	nonceText, nonceOK := read("nonce", 43)
	publicText, publicOK := read("publicKey", 342)
	signatureText, signatureOK := read("signature", 107)
	if !idOK || !timeOK || !nonceOK || !publicOK || !signatureOK || installationID != expectedBinding.ID {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	issuedAt, err := time.Parse(time.RFC3339Nano, issuedAtText)
	if err != nil || issuedAt.UTC().Format(time.RFC3339Nano) != issuedAtText ||
		now.Sub(issuedAt) > maxJoinClockSkew || issuedAt.Sub(now) > maxJoinClockSkew {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	nonceBytes, nonceErr := base64.RawURLEncoding.DecodeString(nonceText)
	publicDER, publicErr := base64.RawURLEncoding.DecodeString(publicText)
	signature, signatureErr := base64.RawURLEncoding.DecodeString(signatureText)
	if nonceErr != nil || publicErr != nil || signatureErr != nil ||
		len(nonceBytes) != 32 || len(publicDER) > maxPublicKeyDER || len(signature) == 0 || len(signature) > maxSignatureDER ||
		base64.RawURLEncoding.EncodeToString(nonceBytes) != nonceText ||
		base64.RawURLEncoding.EncodeToString(publicDER) != publicText ||
		base64.RawURLEncoding.EncodeToString(signature) != signatureText {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	publicKey, canonical, err := parseLinkPublicKey(publicDER)
	if err != nil || sha256.Sum256(canonical) != expectedBinding.PublicKeySHA256 {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	var nonce [32]byte
	copy(nonce[:], nonceBytes)
	payload, err := PersonalJoinSigningPayload(expectedBinding, room, country, issuedAtText, nonce)
	if err != nil {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	digest := sha256.Sum256(payload)
	if !ecdsa.VerifyASN1(publicKey, digest[:], signature) {
		return PersonalJoinProof{}, ErrInvalidPersonalJoinProof
	}
	return PersonalJoinProof{Installation: expectedBinding, Nonce: nonce, IssuedAt: issuedAt,
		ProofDeadline: issuedAt.Add(maxJoinClockSkew)}, nil
}
