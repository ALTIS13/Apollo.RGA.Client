package access

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"time"
)

var (
	ErrInvalidChallenge = errors.New("access: invalid link challenge")
	ErrInvalidProof     = errors.New("access: invalid link proof")
)

const (
	maxPublicKeyDER = 256
	maxSignatureDER = 80
	linkProofDomain = "Apollo.RGA/link-proof/v1"
)

// LinkChallenge carries no subscription bearer. ExpiresAt is set by the
// database clock when the challenge is persisted, not by this constructor.
type LinkChallenge struct {
	ID           string
	Subject      Subject
	Installation InstallationBinding
	Nonce        [32]byte
	ExpiresAt    time.Time
}

func NewLinkChallenge(subject Subject, binding InstallationBinding) (LinkChallenge, error) {
	if subject.Validate() != nil || binding.Validate() != nil {
		return LinkChallenge{}, ErrInvalidChallenge
	}
	var id [32]byte
	var nonce [32]byte
	if _, err := rand.Read(id[:]); err != nil {
		return LinkChallenge{}, ErrInvalidChallenge
	}
	if _, err := rand.Read(nonce[:]); err != nil {
		return LinkChallenge{}, ErrInvalidChallenge
	}
	return LinkChallenge{
		ID:           base64.RawURLEncoding.EncodeToString(id[:]),
		Subject:      subject,
		Installation: binding,
		Nonce:        nonce,
	}, nil
}

func (c LinkChallenge) Validate() error {
	if c.Subject.Validate() != nil || c.Installation.Validate() != nil ||
		!installationIDPattern.MatchString(c.ID) || c.Nonce == ([32]byte{}) {
		return ErrInvalidChallenge
	}
	return nil
}

// SigningPayload is the versioned, length-prefixed transcript signed by the
// installation key. The database separately binds the challenge to UserID.
func (c LinkChallenge) SigningPayload() ([]byte, error) {
	if err := c.Validate(); err != nil {
		return nil, err
	}
	var transcript bytes.Buffer
	transcript.WriteString(linkProofDomain)
	transcript.WriteByte(0)
	for _, field := range [][]byte{
		[]byte(c.Subject.PanelInstance),
		[]byte(c.Installation.ID),
		[]byte(c.ID),
		c.Installation.PublicKeySHA256[:],
		c.Nonce[:],
	} {
		var length [2]byte
		binary.BigEndian.PutUint16(length[:], uint16(len(field)))
		transcript.Write(length[:])
		transcript.Write(field)
	}
	return transcript.Bytes(), nil
}

// FingerprintLinkPublicKey accepts only canonical P-256 X.509 SPKI DER.
func FingerprintLinkPublicKey(publicKeyDER []byte) ([32]byte, error) {
	_, canonical, err := parseLinkPublicKey(publicKeyDER)
	if err != nil {
		return [32]byte{}, err
	}
	return sha256.Sum256(canonical), nil
}

func parseLinkPublicKey(publicKeyDER []byte) (*ecdsa.PublicKey, []byte, error) {
	if len(publicKeyDER) == 0 || len(publicKeyDER) > maxPublicKeyDER {
		return nil, nil, ErrInvalidProof
	}
	parsed, err := x509.ParsePKIXPublicKey(publicKeyDER)
	if err != nil {
		return nil, nil, ErrInvalidProof
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, nil, ErrInvalidProof
	}
	canonical, err := x509.MarshalPKIXPublicKey(key)
	if err != nil || !bytes.Equal(canonical, publicKeyDER) {
		return nil, nil, ErrInvalidProof
	}
	return key, canonical, nil
}

func VerifyLinkProof(challenge LinkChallenge, publicKeyDER, signature []byte) (InstallationBinding, error) {
	if challenge.Validate() != nil || len(signature) == 0 || len(signature) > maxSignatureDER {
		return InstallationBinding{}, ErrInvalidProof
	}
	publicKey, canonical, err := parseLinkPublicKey(publicKeyDER)
	if err != nil || sha256.Sum256(canonical) != challenge.Installation.PublicKeySHA256 {
		return InstallationBinding{}, ErrInvalidProof
	}
	message, err := challenge.SigningPayload()
	if err != nil {
		return InstallationBinding{}, ErrInvalidProof
	}
	digest := sha256.Sum256(message)
	if !ecdsa.VerifyASN1(publicKey, digest[:], signature) {
		return InstallationBinding{}, ErrInvalidProof
	}
	return challenge.Installation, nil
}
