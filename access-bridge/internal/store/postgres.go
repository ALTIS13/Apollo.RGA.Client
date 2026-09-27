// Package store persists non-authorizing Bridge control state.
package store

import (
	"bytes"
	"context"
	"database/sql"
	_ "embed"
	"errors"
	"strings"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrUnavailable          = errors.New("store: unavailable")
	ErrNotFound             = errors.New("store: subject not found")
	ErrInstallationConflict = errors.New("store: installation conflict")
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
