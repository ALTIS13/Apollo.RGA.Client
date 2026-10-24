package store

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrQAJoinInvalid = errors.New("store: invalid personal join")
	ErrQAJoinDenied  = errors.New("store: personal join denied")
	ErrQAJoinReplay  = errors.New("store: personal join replay")
)

// QAJoinRequest is server-produced after a fresh Remnawave lookup, exact
// installation signature verification and PersonalTestPlan evaluation. This
// transaction protects the nonce and session registration, but does not itself
// verify the signature or authorize the OlcRTC network handshake.
type QAJoinRequest struct {
	Subject               access.Subject
	InstallationID        string
	PublicKeySHA256       [32]byte
	RoomID                string
	Country               string
	SessionID             string
	Nonce                 [32]byte
	ProofDeadline         time.Time
	PolicyRevision        int64
	ObservedAt            time.Time
	MaxSnapshotAge        time.Duration
	SubscriptionExpiresAt time.Time
	Deadline              time.Time
}

func validQARoom(id string) bool {
	if len(id) < 8 || len(id) > 128 {
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

// RedeemQAJoin atomically consumes one proof nonce and opens one diagnostic
// journal row for an already approved short test session. An ambiguous COMMIT
// fails closed on retry: the client must sign a fresh nonce, never reuse one.
func (s *Store) RedeemQAJoin(ctx context.Context, req QAJoinRequest) error {
	binding := access.InstallationBinding{ID: req.InstallationID, PublicKeySHA256: req.PublicKeySHA256}
	if req.Subject.Validate() != nil || binding.Validate() != nil ||
		!validMeterSessionID(req.SessionID) || !validQARoom(req.RoomID) ||
		len(req.Country) != 2 || req.Country[0] < 'A' || req.Country[0] > 'Z' ||
		req.Country[1] < 'A' || req.Country[1] > 'Z' || req.Nonce == ([32]byte{}) ||
		req.PolicyRevision <= 0 || req.Deadline.IsZero() || req.ObservedAt.IsZero() || req.ProofDeadline.IsZero() ||
		req.SubscriptionExpiresAt.IsZero() || req.MaxSnapshotAge <= 0 || req.MaxSnapshotAge > 30*time.Second {
		return ErrQAJoinInvalid
	}
	deadline := req.Deadline.UTC().Truncate(time.Microsecond)
	admissionCurrent := func(at time.Time) bool {
		return !at.After(req.ProofDeadline) && !req.ObservedAt.After(at) &&
			at.Sub(req.ObservedAt) <= req.MaxSnapshotAge && req.SubscriptionExpiresAt.After(at) &&
			!deadline.After(req.SubscriptionExpiresAt) && deadline.After(at) &&
			!deadline.After(at.Add(5*time.Minute))
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()

	var enabled bool
	var revision int64
	err = tx.QueryRowContext(ctx, `SELECT olcrtc_enabled, policy_revision FROM bridge_subjects
		WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
		req.Subject.PanelInstance, req.Subject.UserID).Scan(&enabled, &revision)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (!enabled || revision != req.PolicyRevision) {
		return ErrQAJoinDenied
	}
	if err != nil {
		return ErrUnavailable
	}

	var storedHash []byte
	var qaEnabled bool
	err = tx.QueryRowContext(ctx, `SELECT i.public_key_sha256, q.enabled
		FROM bridge_installations i JOIN bridge_qa_installations q ON q.installation_id=i.installation_id
		WHERE i.installation_id=$1 AND i.panel_instance=$2 AND i.user_id=$3 FOR UPDATE OF i, q`,
		req.InstallationID, req.Subject.PanelInstance, req.Subject.UserID).Scan(&storedHash, &qaEnabled)
	if errors.Is(err, sql.ErrNoRows) || err == nil && (!qaEnabled || !bytes.Equal(storedHash, req.PublicKeySHA256[:])) {
		return ErrQAJoinDenied
	}
	if err != nil {
		return ErrUnavailable
	}

	var dbNow time.Time
	if err := tx.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
		return ErrUnavailable
	}
	// Preserve the verifier's original inclusive freshness boundary; do not
	// derive another window from admission time or extend it after lock waits.
	if !admissionCurrent(dbNow) {
		return ErrQAJoinDenied
	}

	var registeredID string
	err = tx.QueryRowContext(ctx, `INSERT INTO bridge_qa_sessions
		(session_id, panel_instance, user_id, installation_id, policy_revision, deadline)
		VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (session_id) DO NOTHING RETURNING session_id`,
		req.SessionID, req.Subject.PanelInstance, req.Subject.UserID,
		req.InstallationID, req.PolicyRevision, deadline).Scan(&registeredID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrQAJoinReplay
	}
	if err != nil {
		return ErrUnavailable
	}

	nonceHash := sha256.Sum256(req.Nonce[:])
	var consumedID string
	err = tx.QueryRowContext(ctx, `INSERT INTO bridge_qa_join_uses
		(nonce_sha256, session_id, panel_instance, user_id, installation_id, room_id, country)
		VALUES ($1,$2,$3,$4,$5,$6,$7)
		ON CONFLICT DO NOTHING RETURNING session_id`,
		nonceHash[:], req.SessionID, req.Subject.PanelInstance, req.Subject.UserID,
		req.InstallationID, req.RoomID, req.Country).Scan(&consumedID)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrQAJoinReplay
	}
	if err != nil {
		return ErrUnavailable
	}
	if registeredID != req.SessionID || consumedID != req.SessionID {
		return ErrUnavailable
	}
	// INSERTs can wait too (table/unique/FK locks). Recheck the SAME immutable
	// freshness/cutoff after all writes; denial rolls both rows back together.
	if err := tx.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
		return ErrUnavailable
	}
	if !admissionCurrent(dbNow) {
		return ErrQAJoinDenied
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}
