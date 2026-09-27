// Package store persists non-authorizing Bridge control state.
package store

import (
	"bytes"
	"context"
	"database/sql"
	_ "embed"
	"encoding/base64"
	"errors"
	"strings"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrUnavailable          = errors.New("store: unavailable")
	ErrNotFound             = errors.New("store: subject not found")
	ErrInstallationConflict = errors.New("store: installation conflict")
	ErrChallengeInvalid     = errors.New("store: invalid link challenge")
)

//go:embed schema.sql
var schemaSQL string

type Store struct {
	db *sql.DB
}

func New(db *sql.DB) (*Store, error) {
	if db == nil {
		return nil, ErrUnavailable
	}
	return &Store{db: db}, nil
}

func (s *Store) Migrate(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	for _, statement := range strings.Split(schemaSQL, "-- migration-step") {
		if _, err := tx.ExecContext(ctx, statement); err != nil {
			return ErrUnavailable
		}
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}

func (s *Store) EnsureSubject(ctx context.Context, subject access.Subject) (access.SubjectState, error) {
	if err := subject.Validate(); err != nil {
		return access.SubjectState{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx,
		`INSERT INTO bridge_subjects (panel_instance, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
		subject.PanelInstance, subject.UserID)
	if err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	state, err := loadFrom(ctx, tx, subject)
	if err != nil {
		return access.SubjectState{}, err
	}
	if err := tx.Commit(); err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	return state, nil
}

func (s *Store) Load(ctx context.Context, subject access.Subject) (access.SubjectState, error) {
	if err := subject.Validate(); err != nil {
		return access.SubjectState{}, err
	}
	return loadFrom(ctx, s.db, subject)
}

type rowQuerier interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func loadFrom(ctx context.Context, q rowQuerier, subject access.Subject) (access.SubjectState, error) {
	state := access.SubjectState{Subject: subject}
	err := q.QueryRowContext(ctx,
		`SELECT olcrtc_enabled, policy_revision FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2`,
		subject.PanelInstance, subject.UserID).Scan(&state.OlcRTCEnabled, &state.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return access.SubjectState{}, ErrNotFound
	}
	if err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	return state, nil
}

func (s *Store) BindInstallation(ctx context.Context, subject access.Subject, binding access.InstallationBinding) error {
	if err := subject.Validate(); err != nil {
		return err
	}
	if err := binding.Validate(); err != nil {
		return err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	var exists int
	err = tx.QueryRowContext(ctx,
		`SELECT 1 FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2 FOR KEY SHARE`,
		subject.PanelInstance, subject.UserID).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return ErrUnavailable
	}
	_, err = tx.ExecContext(ctx,
		`INSERT INTO bridge_installations (installation_id, panel_instance, user_id, public_key_sha256)
		 VALUES ($1, $2, $3, $4) ON CONFLICT (installation_id) DO NOTHING`,
		binding.ID, subject.PanelInstance, subject.UserID, binding.PublicKeySHA256[:])
	if err != nil {
		return ErrUnavailable
	}
	var panel string
	var userID int64
	var fingerprint []byte
	err = tx.QueryRowContext(ctx,
		`SELECT panel_instance, user_id, public_key_sha256
		 FROM bridge_installations WHERE installation_id=$1 FOR UPDATE`,
		binding.ID).Scan(&panel, &userID, &fingerprint)
	if err != nil {
		return ErrUnavailable
	}
	if panel != subject.PanelInstance || userID != subject.UserID || !bytes.Equal(fingerprint, binding.PublicKeySHA256[:]) {
		return ErrInstallationConflict
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}

func (s *Store) SetProtocolEnabled(ctx context.Context, subject access.Subject, enabled bool) (access.SubjectState, error) {
	if err := subject.Validate(); err != nil {
		return access.SubjectState{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	defer tx.Rollback()
	state := access.SubjectState{Subject: subject}
	err = tx.QueryRowContext(ctx,
		`SELECT olcrtc_enabled, policy_revision FROM bridge_subjects
		 WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
		subject.PanelInstance, subject.UserID).Scan(&state.OlcRTCEnabled, &state.Revision)
	if errors.Is(err, sql.ErrNoRows) {
		return access.SubjectState{}, ErrNotFound
	}
	if err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	if state.OlcRTCEnabled != enabled {
		err = tx.QueryRowContext(ctx,
			`UPDATE bridge_subjects SET olcrtc_enabled=$3, policy_revision=policy_revision+1
			 WHERE panel_instance=$1 AND user_id=$2
			 RETURNING olcrtc_enabled, policy_revision`,
			subject.PanelInstance, subject.UserID, enabled).Scan(&state.OlcRTCEnabled, &state.Revision)
		if err != nil {
			return access.SubjectState{}, ErrUnavailable
		}
	}
	if err := tx.Commit(); err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	return state, nil
}

// CreateChallenge stores no subscription bearer or full public key. Its expiry
// is set by the database clock so all consumers agree on one deadline.
func (s *Store) CreateChallenge(ctx context.Context, challenge access.LinkChallenge) (access.LinkChallenge, error) {
	if challenge.Validate() != nil || !validChallengeID(challenge.ID) || !challenge.ExpiresAt.IsZero() {
		return access.LinkChallenge{}, ErrChallengeInvalid
	}
	err := s.db.QueryRowContext(ctx,
		`INSERT INTO bridge_link_challenges
		 (challenge_id, panel_instance, user_id, installation_id, public_key_sha256, nonce, expires_at)
		 VALUES ($1, $2, $3, $4, $5, $6, clock_timestamp()+interval '5 minutes')
		 ON CONFLICT (challenge_id) DO NOTHING RETURNING expires_at`,
		challenge.ID, challenge.Subject.PanelInstance, challenge.Subject.UserID,
		challenge.Installation.ID, challenge.Installation.PublicKeySHA256[:], challenge.Nonce[:],
	).Scan(&challenge.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return access.LinkChallenge{}, ErrChallengeInvalid
	}
	if err != nil {
		return access.LinkChallenge{}, ErrUnavailable
	}
	return challenge, nil
}

func (s *Store) LoadChallenge(ctx context.Context, id string) (access.LinkChallenge, error) {
	if !validChallengeID(id) {
		return access.LinkChallenge{}, ErrChallengeInvalid
	}
	return readChallenge(ctx, s.db,
		`SELECT panel_instance, user_id, installation_id, public_key_sha256, nonce,
		 expires_at, consumed_at IS NULL, expires_at > clock_timestamp()
		 FROM bridge_link_challenges WHERE challenge_id=$1`, id)
}

func validChallengeID(id string) bool {
	if len(id) != 43 {
		return false
	}
	decoded, err := base64.RawURLEncoding.DecodeString(id)
	return err == nil && len(decoded) == 32 && base64.RawURLEncoding.EncodeToString(decoded) == id
}

func readChallenge(ctx context.Context, q rowQuerier, query, id string) (access.LinkChallenge, error) {
	var challenge access.LinkChallenge
	challenge.ID = id
	var fingerprint, nonce []byte
	var unused, unexpired bool
	err := q.QueryRowContext(ctx, query, id).Scan(
		&challenge.Subject.PanelInstance, &challenge.Subject.UserID,
		&challenge.Installation.ID, &fingerprint, &nonce,
		&challenge.ExpiresAt, &unused, &unexpired,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return access.LinkChallenge{}, ErrChallengeInvalid
	}
	if err != nil {
		return access.LinkChallenge{}, ErrUnavailable
	}
	if len(fingerprint) != 32 || len(nonce) != 32 {
		return access.LinkChallenge{}, ErrUnavailable
	}
	copy(challenge.Installation.PublicKeySHA256[:], fingerprint)
	copy(challenge.Nonce[:], nonce)
	if challenge.Validate() != nil {
		return access.LinkChallenge{}, ErrUnavailable
	}
	if !unused || !unexpired {
		return access.LinkChallenge{}, ErrChallengeInvalid
	}
	return challenge, nil
}

// ConsumeChallengeAndBind is the sole mutation after external proof and
// eligibility checks. A failure rolls back subject, binding and consumption.
func (s *Store) ConsumeChallengeAndBind(ctx context.Context, id string, subject access.Subject, binding access.InstallationBinding, subscriptionExpiresAt time.Time) error {
	if !validChallengeID(id) || subject.Validate() != nil || binding.Validate() != nil || subscriptionExpiresAt.IsZero() {
		return ErrChallengeInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	challenge, err := readChallenge(ctx, tx,
		`SELECT panel_instance, user_id, installation_id, public_key_sha256, nonce,
		 expires_at, consumed_at IS NULL, expires_at > clock_timestamp()
		 FROM bridge_link_challenges WHERE challenge_id=$1 FOR UPDATE`, id)
	if err != nil {
		return err
	}
	if challenge.Subject != subject || challenge.Installation != binding {
		return ErrChallengeInvalid
	}
	_, err = tx.ExecContext(ctx,
		`INSERT INTO bridge_subjects (panel_instance, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
		subject.PanelInstance, subject.UserID)
	if err != nil {
		return ErrUnavailable
	}
	_, err = tx.ExecContext(ctx,
		`INSERT INTO bridge_installations (installation_id, panel_instance, user_id, public_key_sha256)
		 VALUES ($1, $2, $3, $4) ON CONFLICT (installation_id) DO NOTHING`,
		binding.ID, subject.PanelInstance, subject.UserID, binding.PublicKeySHA256[:])
	if err != nil {
		return ErrUnavailable
	}
	var panel string
	var userID int64
	var fingerprint []byte
	err = tx.QueryRowContext(ctx,
		`SELECT panel_instance, user_id, public_key_sha256
		 FROM bridge_installations WHERE installation_id=$1 FOR UPDATE`, binding.ID).Scan(&panel, &userID, &fingerprint)
	if err != nil {
		return ErrUnavailable
	}
	if panel != subject.PanelInstance || userID != subject.UserID || !bytes.Equal(fingerprint, binding.PublicKeySHA256[:]) {
		return ErrInstallationConflict
	}
	result, err := tx.ExecContext(ctx,
		`UPDATE bridge_link_challenges SET consumed_at=clock_timestamp()
		 WHERE challenge_id=$1 AND consumed_at IS NULL AND expires_at > clock_timestamp()
		 AND clock_timestamp() < $2`, id, subscriptionExpiresAt)
	if err != nil {
		return ErrUnavailable
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return ErrUnavailable
	}
	if affected != 1 {
		return ErrChallengeInvalid
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}
