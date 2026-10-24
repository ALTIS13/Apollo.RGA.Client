package store

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrQARenewInvalid = errors.New("store: invalid personal renewal")
	ErrQARenewDenied  = errors.New("store: personal renewal denied")
)

// QARenewRequest contains only fresh server-derived facts. The caller must
// never fill it from client claims or a cached policy snapshot.
type QARenewRequest struct {
	Subject               access.Subject
	InstallationID        string
	PublicKeySHA256       [32]byte
	RoomID                string
	Country               string
	SessionID             string
	PolicyRevision        int64
	ObservedAt            time.Time
	MaxSnapshotAge        time.Duration
	SubscriptionExpiresAt time.Time
	NewDeadline           time.Time
}

// RenewQADeadline extends only one still-active, originally admitted personal
// session. The returned deadline is the database value after a successful
// commit. Any denied or ambiguous transaction returns zero, so a managed
// server wrapper must retain the old tunnel deadline on error.
func (s *Store) RenewQADeadline(ctx context.Context, req QARenewRequest) (time.Time, error) {
	binding := access.InstallationBinding{ID: req.InstallationID, PublicKeySHA256: req.PublicKeySHA256}
	if req.Subject.Validate() != nil || binding.Validate() != nil ||
		!validMeterSessionID(req.SessionID) || !validQARoom(req.RoomID) ||
		len(req.Country) != 2 || req.Country[0] < 'A' || req.Country[0] > 'Z' ||
		req.Country[1] < 'A' || req.Country[1] > 'Z' || req.PolicyRevision <= 0 ||
		req.ObservedAt.IsZero() || req.MaxSnapshotAge <= 0 || req.MaxSnapshotAge > 30*time.Second ||
		req.SubscriptionExpiresAt.IsZero() || req.NewDeadline.IsZero() {
		return time.Time{}, ErrQARenewInvalid
	}
	newDeadline := req.NewDeadline.UTC().Truncate(time.Microsecond)
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return time.Time{}, ErrUnavailable
	}
	defer tx.Rollback()

	var enabled bool
	var revision int64
	err = tx.QueryRowContext(ctx, `SELECT olcrtc_enabled, policy_revision FROM bridge_subjects
		WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
		req.Subject.PanelInstance, req.Subject.UserID).Scan(&enabled, &revision)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (!enabled || revision != req.PolicyRevision) {
		return time.Time{}, ErrQARenewDenied
	}
	if err != nil {
		return time.Time{}, ErrUnavailable
	}

	var storedHash []byte
	var qaEnabled bool
	err = tx.QueryRowContext(ctx, `SELECT i.public_key_sha256, q.enabled
		FROM bridge_installations i JOIN bridge_qa_installations q ON q.installation_id=i.installation_id
		WHERE i.installation_id=$1 AND i.panel_instance=$2 AND i.user_id=$3 FOR UPDATE OF i, q`,
		req.InstallationID, req.Subject.PanelInstance, req.Subject.UserID).Scan(&storedHash, &qaEnabled)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (!qaEnabled || !bytes.Equal(storedHash, req.PublicKeySHA256[:])) {
		return time.Time{}, ErrQARenewDenied
	}
	if err != nil {
		return time.Time{}, ErrUnavailable
	}

	var oldDeadline time.Time
	var status QAStatus
	var originalRevision sql.NullInt64
	var room, country string
	err = tx.QueryRowContext(ctx, `SELECT s.deadline, s.status, s.policy_revision, j.room_id, j.country
		FROM bridge_qa_sessions s JOIN bridge_qa_join_uses j
		  ON j.session_id=s.session_id AND j.panel_instance=s.panel_instance
		  AND j.user_id=s.user_id AND j.installation_id=s.installation_id
		WHERE s.session_id=$1 AND s.panel_instance=$2 AND s.user_id=$3 AND s.installation_id=$4
		FOR UPDATE OF s`,
		req.SessionID, req.Subject.PanelInstance, req.Subject.UserID, req.InstallationID,
	).Scan(&oldDeadline, &status, &originalRevision, &room, &country)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (status != QAActive || !originalRevision.Valid ||
		originalRevision.Int64 != req.PolicyRevision || room != req.RoomID || country != req.Country) {
		return time.Time{}, ErrQARenewDenied
	}
	if err != nil {
		return time.Time{}, ErrUnavailable
	}

	var dbNow time.Time
	if err := tx.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
		return time.Time{}, ErrUnavailable
	}
	if req.ObservedAt.After(dbNow) || dbNow.Sub(req.ObservedAt) > req.MaxSnapshotAge ||
		!oldDeadline.After(dbNow) || !req.SubscriptionExpiresAt.After(dbNow) ||
		newDeadline.Before(oldDeadline) || newDeadline.After(dbNow.Add(5*time.Minute)) ||
		newDeadline.After(req.SubscriptionExpiresAt) {
		return time.Time{}, ErrQARenewDenied
	}
	if newDeadline.Equal(oldDeadline) {
		// A still-active lease already at the current subscription cutoff has
		// nothing to extend. Revalidation succeeds without touching its end or
		// meter. Non-increasing retries away from that exact cap stay denied.
		if !oldDeadline.Equal(req.SubscriptionExpiresAt.UTC().Truncate(time.Microsecond)) {
			return time.Time{}, ErrQARenewDenied
		}
		if err := tx.Commit(); err != nil {
			return time.Time{}, ErrUnavailable
		}
		return oldDeadline, nil
	}
	var persisted time.Time
	err = tx.QueryRowContext(ctx, `UPDATE bridge_qa_sessions SET deadline=$5, updated_at=clock_timestamp()
		WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3 AND installation_id=$4
		  AND status='active' AND policy_revision=$6 AND deadline=$7
		RETURNING deadline`,
		req.SessionID, req.Subject.PanelInstance, req.Subject.UserID, req.InstallationID,
		newDeadline, req.PolicyRevision, oldDeadline,
	).Scan(&persisted)
	if errors.Is(err, sql.ErrNoRows) {
		return time.Time{}, ErrQARenewDenied
	}
	if err != nil {
		return time.Time{}, ErrUnavailable
	}
	if err := tx.Commit(); err != nil {
		return time.Time{}, ErrUnavailable
	}
	return persisted, nil
}
