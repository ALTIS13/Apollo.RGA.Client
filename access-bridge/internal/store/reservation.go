package store

import (
	"context"
	"database/sql"
	"errors"
	"math"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

var (
	ErrReservationInvalid  = errors.New("store: invalid reservation input")
	ErrReservationEvidence = errors.New("store: unverified budget evidence")
	ErrReservationDenied   = errors.New("store: reservation denied")
	ErrReservationConflict = errors.New("store: reservation conflict")
)

// BudgetEvidence is trusted server-side input, never a node or client claim.
// No production producer exists until cycle identity, Xray lag and active
// enforcement have been independently verified.
type BudgetEvidence struct {
	Subject                     access.Subject
	CycleID                     string
	CycleStart                  time.Time
	CycleEnd                    time.Time
	ObservedAt                  time.Time
	SubscriptionExpiresAt       time.Time
	Status                      remnawave.Status
	TotalLimitBytes             int64
	XrayUsedBytes               int64
	MeasuredXrayLag             time.Duration
	ActiveXrayEnforcementProven bool
}

type ReservationRequest struct {
	Subject                                         access.Subject
	InstallationID, RoomID, Country, SessionID, JTI string
	PolicyRevision, RequestedBytes                  int64
}

type ReservationLimits struct {
	MaxLeaseBytes                        int64
	LeaseTTL, MaxEvidenceAge, MaxXrayLag time.Duration
}

type Reservation struct {
	JTI, SessionID string
	ByteLimit      int64
	ExpiresAt      time.Time
}

type budgetCycle struct {
	start, end, observedAt       time.Time
	limit, xray, committed, held int64
	overdrawn                    bool
}

type storedReservation struct {
	lease                                    Reservation
	subject                                  access.Subject
	cycleID, installationID, roomID, country string
	revision                                 int64
	revoked                                  bool
}

// ReserveLease holds a finite per-cycle byte block and creates its meter
// session atomically. It is ledger bookkeeping, not a network grant.
func (s *Store) ReserveLease(ctx context.Context, req ReservationRequest, evidence BudgetEvidence, limits ReservationLimits) (Reservation, error) {
	// PostgreSQL TIMESTAMPTZ persists microseconds. Canonicalize trusted
	// timestamps before immutable-cycle comparison and ambiguous-COMMIT retry.
	evidence.CycleStart = evidence.CycleStart.Round(time.Microsecond)
	evidence.CycleEnd = evidence.CycleEnd.Round(time.Microsecond)
	evidence.ObservedAt = evidence.ObservedAt.Round(time.Microsecond)
	evidence.SubscriptionExpiresAt = evidence.SubscriptionExpiresAt.Round(time.Microsecond)
	if !validReservationRequest(req, limits) {
		return Reservation{}, ErrReservationInvalid
	}
	if !validBudgetEvidence(req.Subject, evidence, limits) {
		return Reservation{}, ErrReservationEvidence
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Reservation{}, ErrUnavailable
	}
	defer tx.Rollback()
	var enabled bool
	var revision int64
	var now time.Time
	err = tx.QueryRowContext(ctx,
		`SELECT olcrtc_enabled, policy_revision, clock_timestamp()
		 FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
		req.Subject.PanelInstance, req.Subject.UserID,
	).Scan(&enabled, &revision, &now)
	if errors.Is(err, sql.ErrNoRows) {
		return Reservation{}, ErrReservationDenied
	}
	if err != nil {
		return Reservation{}, ErrUnavailable
	}
	if !enabled || revision != req.PolicyRevision {
		return Reservation{}, ErrReservationDenied
	}
	if evidence.CycleStart.After(now) || !now.Before(evidence.CycleEnd) ||
		evidence.ObservedAt.After(now) || now.Sub(evidence.ObservedAt) > limits.MaxEvidenceAge ||
		!now.Before(evidence.SubscriptionExpiresAt) {
		return Reservation{}, ErrReservationEvidence
	}
	var bound int
	err = tx.QueryRowContext(ctx,
		`SELECT 1 FROM bridge_installations WHERE installation_id=$1 AND panel_instance=$2 AND user_id=$3 FOR KEY SHARE`,
		req.InstallationID, req.Subject.PanelInstance, req.Subject.UserID,
	).Scan(&bound)
	if errors.Is(err, sql.ErrNoRows) {
		return Reservation{}, ErrReservationDenied
	}
	if err != nil {
		return Reservation{}, ErrUnavailable
	}
	var unattributed int
	err = tx.QueryRowContext(ctx,
		`SELECT 1 FROM bridge_meter_sessions m
		 WHERE m.panel_instance=$1 AND m.user_id=$2 AND NOT EXISTS
		   (SELECT 1 FROM bridge_budget_leases l WHERE l.session_id=m.session_id)
		 LIMIT 1`, req.Subject.PanelInstance, req.Subject.UserID,
	).Scan(&unattributed)
	if err == nil {
		return Reservation{}, ErrReservationDenied
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Reservation{}, ErrUnavailable
	}
	previous, found, err := loadReservationByJTI(ctx, tx, req.JTI)
	if err != nil {
		return Reservation{}, err
	}
	if found && (previous.subject != req.Subject || previous.cycleID != evidence.CycleID ||
		previous.installationID != req.InstallationID || previous.roomID != req.RoomID ||
		previous.country != req.Country || previous.lease.SessionID != req.SessionID ||
		previous.revision != req.PolicyRevision || previous.lease.ByteLimit != req.RequestedBytes) {
		return Reservation{}, ErrReservationConflict
	}
	if found && (previous.revoked || !now.Before(previous.lease.ExpiresAt)) {
		return Reservation{}, ErrReservationDenied
	}
	if !found {
		var existingJTI string
		err = tx.QueryRowContext(ctx,
			`SELECT jti FROM bridge_budget_leases WHERE session_id=$1`, req.SessionID,
		).Scan(&existingJTI)
		if err == nil {
			return Reservation{}, ErrReservationConflict
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return Reservation{}, ErrUnavailable
		}
	}
	cycle, err := loadOrCreateBudgetCycle(ctx, tx, req.Subject, evidence)
	if err != nil {
		return Reservation{}, err
	}
	if evidence.XrayUsedBytes < cycle.xray || evidence.ObservedAt.Before(cycle.observedAt) {
		return Reservation{}, ErrReservationEvidence
	}
	cycle.xray = evidence.XrayUsedBytes
	cycle.observedAt = evidence.ObservedAt
	current, ok := checkedBudgetSum(cycle.xray, cycle.committed, cycle.held)
	cycle.overdrawn = cycle.overdrawn || !ok || current > cycle.limit
	if _, err := tx.ExecContext(ctx,
		`UPDATE bridge_budget_cycles SET confirmed_xray_bytes=$4, xray_observed_at=$5, overdrawn=$6
		 WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
		req.Subject.PanelInstance, req.Subject.UserID, evidence.CycleID,
		cycle.xray, cycle.observedAt, cycle.overdrawn,
	); err != nil {
		return Reservation{}, ErrUnavailable
	}
	// A valid newer high-water sample is durable even when it denies this
	// request. A later lower sample must not reopen the cycle's headroom.
	if cycle.overdrawn || !ok {
		if err := tx.Commit(); err != nil {
			return Reservation{}, ErrUnavailable
		}
		return Reservation{}, ErrReservationDenied
	}
	expiresAt := now.Add(limits.LeaseTTL)
	if evidence.SubscriptionExpiresAt.Before(expiresAt) {
		expiresAt = evidence.SubscriptionExpiresAt
	}
	if evidence.CycleEnd.Before(expiresAt) {
		expiresAt = evidence.CycleEnd
	}
	if !expiresAt.After(now) {
		return Reservation{}, ErrReservationEvidence
	}
	if found {
		if err := tx.Commit(); err != nil {
			return Reservation{}, ErrUnavailable
		}
		if previous.lease.ExpiresAt.After(expiresAt) {
			return Reservation{}, ErrReservationDenied
		}
		return previous.lease, nil
	}
	if req.RequestedBytes > cycle.limit-current {
		if err := tx.Commit(); err != nil {
			return Reservation{}, ErrUnavailable
		}
		return Reservation{}, ErrReservationDenied
	}
	if _, err := tx.ExecContext(ctx,
		`UPDATE bridge_budget_cycles SET held_bytes=held_bytes+$4
		 WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
		req.Subject.PanelInstance, req.Subject.UserID, evidence.CycleID, req.RequestedBytes,
	); err != nil {
		return Reservation{}, ErrUnavailable
	}
	var inserted string
	err = tx.QueryRowContext(ctx,
		`INSERT INTO bridge_meter_sessions (session_id, panel_instance, user_id)
		 VALUES ($1, $2, $3) ON CONFLICT (session_id) DO NOTHING RETURNING session_id`,
		req.SessionID, req.Subject.PanelInstance, req.Subject.UserID,
	).Scan(&inserted)
	if errors.Is(err, sql.ErrNoRows) {
		return Reservation{}, ErrReservationConflict
	}
	if err != nil {
		return Reservation{}, ErrUnavailable
	}
	err = tx.QueryRowContext(ctx,
		`INSERT INTO bridge_budget_leases
		 (jti, session_id, panel_instance, user_id, cycle_id, installation_id,
		  room_id, country, policy_revision, byte_limit, expires_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
		 ON CONFLICT DO NOTHING RETURNING jti`,
		req.JTI, req.SessionID, req.Subject.PanelInstance, req.Subject.UserID,
		evidence.CycleID, req.InstallationID, req.RoomID, req.Country,
		req.PolicyRevision, req.RequestedBytes, expiresAt,
	).Scan(&inserted)
	if errors.Is(err, sql.ErrNoRows) {
		return Reservation{}, ErrReservationConflict
	}
	if err != nil {
		return Reservation{}, ErrUnavailable
	}
	if err := tx.Commit(); err != nil {
		return Reservation{}, ErrUnavailable
	}
	return Reservation{JTI: req.JTI, SessionID: req.SessionID, ByteLimit: req.RequestedBytes, ExpiresAt: expiresAt}, nil
}

func validReservationRequest(req ReservationRequest, limits ReservationLimits) bool {
	if req.Subject.Validate() != nil || !validReservationID(req.InstallationID, 16) ||
		!validReservationID(req.RoomID, 1) || !validMeterSessionID(req.SessionID) ||
		!validReservationID(req.JTI, 16) || len(req.Country) != 2 ||
		req.Country[0] < 'A' || req.Country[0] > 'Z' ||
		req.Country[1] < 'A' || req.Country[1] > 'Z' ||
		req.PolicyRevision <= 0 || req.RequestedBytes <= 0 ||
		limits.MaxLeaseBytes <= 0 || req.RequestedBytes > limits.MaxLeaseBytes ||
		limits.LeaseTTL <= 0 || limits.MaxEvidenceAge <= 0 || limits.MaxXrayLag <= 0 {
		return false
	}
	return true
}

func validBudgetEvidence(subject access.Subject, evidence BudgetEvidence, limits ReservationLimits) bool {
	return evidence.Subject == subject && subject.Validate() == nil &&
		validReservationID(evidence.CycleID, 1) &&
		!evidence.CycleStart.IsZero() && !evidence.CycleEnd.IsZero() &&
		evidence.CycleStart.Before(evidence.CycleEnd) &&
		!evidence.ObservedAt.IsZero() && !evidence.ObservedAt.Before(evidence.CycleStart) &&
		!evidence.ObservedAt.After(evidence.CycleEnd) &&
		!evidence.SubscriptionExpiresAt.IsZero() &&
		evidence.Status == remnawave.StatusActive &&
		evidence.TotalLimitBytes > 0 && evidence.XrayUsedBytes >= 0 &&
		evidence.MeasuredXrayLag > 0 && evidence.MeasuredXrayLag <= limits.MaxXrayLag &&
		evidence.ActiveXrayEnforcementProven
}

func validReservationID(id string, min int) bool {
	if len(id) < min || len(id) > 128 {
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

func checkedBudgetSum(values ...int64) (int64, bool) {
	var total int64
	for _, value := range values {
		if value < 0 || value > math.MaxInt64-total {
			return 0, false
		}
		total += value
	}
	return total, true
}

func loadReservationByJTI(ctx context.Context, tx *sql.Tx, jti string) (storedReservation, bool, error) {
	var row storedReservation
	row.lease.JTI = jti
	err := tx.QueryRowContext(ctx,
		`SELECT session_id, panel_instance, user_id, cycle_id, installation_id, room_id,
		 country, policy_revision, byte_limit, expires_at, revoked_at IS NOT NULL
		 FROM bridge_budget_leases WHERE jti=$1`, jti,
	).Scan(&row.lease.SessionID, &row.subject.PanelInstance, &row.subject.UserID,
		&row.cycleID, &row.installationID, &row.roomID, &row.country,
		&row.revision, &row.lease.ByteLimit, &row.lease.ExpiresAt, &row.revoked)
	if errors.Is(err, sql.ErrNoRows) {
		return storedReservation{}, false, nil
	}
	if err != nil {
		return storedReservation{}, false, ErrUnavailable
	}
	return row, true, nil
}

func loadOrCreateBudgetCycle(ctx context.Context, tx *sql.Tx, subject access.Subject, evidence BudgetEvidence) (budgetCycle, error) {
	var cycle budgetCycle
	err := tx.QueryRowContext(ctx,
		`SELECT cycle_start, cycle_end, total_limit_bytes, confirmed_xray_bytes,
		 xray_observed_at, committed_olcrtc_bytes, held_bytes, overdrawn
		 FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3 FOR UPDATE`,
		subject.PanelInstance, subject.UserID, evidence.CycleID,
	).Scan(&cycle.start, &cycle.end, &cycle.limit, &cycle.xray,
		&cycle.observedAt, &cycle.committed, &cycle.held, &cycle.overdrawn)
	if err == nil {
		if !cycle.start.Equal(evidence.CycleStart) || !cycle.end.Equal(evidence.CycleEnd) ||
			cycle.limit != evidence.TotalLimitBytes {
			return budgetCycle{}, ErrReservationEvidence
		}
		return cycle, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return budgetCycle{}, ErrUnavailable
	}
	var latestEnd time.Time
	err = tx.QueryRowContext(ctx,
		`SELECT cycle_end FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2
		 ORDER BY cycle_end DESC LIMIT 1 FOR UPDATE`, subject.PanelInstance, subject.UserID,
	).Scan(&latestEnd)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return budgetCycle{}, ErrUnavailable
	}
	if err == nil && evidence.CycleStart.Before(latestEnd) {
		return budgetCycle{}, ErrReservationEvidence
	}
	cycle = budgetCycle{
		start: evidence.CycleStart, end: evidence.CycleEnd,
		limit: evidence.TotalLimitBytes, xray: evidence.XrayUsedBytes,
		observedAt: evidence.ObservedAt,
	}
	_, err = tx.ExecContext(ctx,
		`INSERT INTO bridge_budget_cycles
		 (panel_instance, user_id, cycle_id, cycle_start, cycle_end, total_limit_bytes,
		  confirmed_xray_bytes, xray_observed_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
		subject.PanelInstance, subject.UserID, evidence.CycleID,
		cycle.start, cycle.end, cycle.limit, cycle.xray, cycle.observedAt,
	)
	if err != nil {
		return budgetCycle{}, ErrUnavailable
	}
	return cycle, nil
}
