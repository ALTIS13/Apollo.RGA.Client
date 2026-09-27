package store

import (
	"context"
	"database/sql"
	"errors"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrMeterInvalid         = errors.New("store: invalid meter input")
	ErrMeterSessionConflict = errors.New("store: meter session conflict")
)

// RegisterMeterSession records local meter state only. It grants no network
// access and must be called only after a future admission decision.
func (s *Store) RegisterMeterSession(ctx context.Context, subject access.Subject, sessionID string) error {
	if err := subject.Validate(); err != nil {
		return err
	}
	if !validMeterSessionID(sessionID) {
		return ErrMeterInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	var exists int
	err = tx.QueryRowContext(ctx,
		`SELECT 1 FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2 FOR KEY SHARE`,
		subject.PanelInstance, subject.UserID,
	).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return ErrUnavailable
	}
	_, err = tx.ExecContext(ctx,
		`INSERT INTO bridge_meter_sessions (session_id, panel_instance, user_id)
		 VALUES ($1, $2, $3) ON CONFLICT (session_id) DO NOTHING`,
		sessionID, subject.PanelInstance, subject.UserID,
	)
	if err != nil {
		return ErrUnavailable
	}
	var panel string
	var userID int64
	err = tx.QueryRowContext(ctx,
		`SELECT panel_instance, user_id FROM bridge_meter_sessions WHERE session_id=$1 FOR UPDATE`,
		sessionID,
	).Scan(&panel, &userID)
	if err != nil {
		return ErrUnavailable
	}
	if panel != subject.PanelInstance || userID != subject.UserID {
		return ErrMeterSessionConflict
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}

func validMeterSessionID(id string) bool {
	if len(id) < 16 || len(id) > 128 {
		return false
	}
	for i := 0; i < len(id); i++ {
		c := id[i]
		if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' {
			continue
		}
		return false
	}
	return true
}
