package store

import (
	"context"
	"database/sql"
	"errors"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrMeterInvalid             = errors.New("store: invalid meter input")
	ErrMeterSessionUnknown      = errors.New("store: meter session unknown")
	ErrMeterSessionConflict     = errors.New("store: meter session conflict")
	ErrMeterStale               = errors.New("store: stale meter report")
	ErrMeterRegression          = errors.New("store: regressed meter counter")
	ErrMeterConflict            = errors.New("store: conflicting meter report")
	ErrMeterBudgetExceeded      = errors.New("store: meter budget exceeded")
	ErrMeterRegistrationBlocked = errors.New("store: legacy meter registration blocked")
)

type MeterReport struct {
	Subject           access.Subject
	SessionID         string
	Sequence          int64
	ClientToExitBytes int64
	ExitToClientBytes int64
}

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
		`SELECT 1 FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
		subject.PanelInstance, subject.UserID,
	).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return ErrUnavailable
	}
	// ReserveLease takes the same subject lock before creating a cycle.
	// Existing un-attributed sessions block that transition; once a cycle
	// exists, this legacy entry point cannot create another one.
	var cycleExists int
	err = tx.QueryRowContext(ctx,
		`SELECT 1 FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2 LIMIT 1`,
		subject.PanelInstance, subject.UserID,
	).Scan(&cycleExists)
	if err == nil {
		return ErrMeterRegistrationBlocked
	}
	if !errors.Is(err, sql.ErrNoRows) {
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

// RecordCumulative advances durable per-session high-water. Reserved sessions
// move confirmed bytes from held to committed in the same transaction. Its
// acknowledgement is never a second traffic delta after an ambiguous COMMIT.
func (s *Store) RecordCumulative(ctx context.Context, report MeterReport) error {
	if err := report.Subject.Validate(); err != nil {
		return err
	}
	if !validMeterSessionID(report.SessionID) || report.Sequence < 1 ||
		report.ClientToExitBytes < 0 || report.ExitToClientBytes < 0 {
		return ErrMeterInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	var cycleID string
	var byteLimit int64
	err = tx.QueryRowContext(ctx,
		`SELECT cycle_id, byte_limit FROM bridge_budget_leases
		 WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3`,
		report.SessionID, report.Subject.PanelInstance, report.Subject.UserID,
	).Scan(&cycleID, &byteLimit)
	reserved := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return ErrUnavailable
	}
	var cycleLimit, cycleXray, cycleCommitted, cycleHeld int64
	var cycleOverdrawn bool
	if reserved {
		var exists int
		err = tx.QueryRowContext(ctx,
			`SELECT 1 FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
			report.Subject.PanelInstance, report.Subject.UserID,
		).Scan(&exists)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrMeterSessionUnknown
		}
		if err != nil {
			return ErrUnavailable
		}
		err = tx.QueryRowContext(ctx,
			`SELECT total_limit_bytes, confirmed_xray_bytes, committed_olcrtc_bytes,
			 held_bytes, overdrawn FROM bridge_budget_cycles
			 WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3 FOR UPDATE`,
			report.Subject.PanelInstance, report.Subject.UserID, cycleID,
		).Scan(&cycleLimit, &cycleXray, &cycleCommitted, &cycleHeld, &cycleOverdrawn)
		if err != nil {
			return ErrUnavailable
		}
	}
	var sequence, clientBytes, exitBytes int64
	err = tx.QueryRowContext(ctx,
		`SELECT last_sequence, client_to_exit_bytes, exit_to_client_bytes
		 FROM bridge_meter_sessions
		 WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3 FOR UPDATE`,
		report.SessionID, report.Subject.PanelInstance, report.Subject.UserID,
	).Scan(&sequence, &clientBytes, &exitBytes)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrMeterSessionUnknown
	}
	if err != nil {
		return ErrUnavailable
	}
	if report.Sequence < sequence {
		return ErrMeterStale
	}
	if report.Sequence == sequence {
		if report.ClientToExitBytes == clientBytes && report.ExitToClientBytes == exitBytes {
			if reserved && cycleOverdrawn {
				return ErrMeterBudgetExceeded
			}
			return nil
		}
		return ErrMeterConflict
	}
	if report.ClientToExitBytes < clientBytes || report.ExitToClientBytes < exitBytes {
		return ErrMeterRegression
	}
	budgetExceeded := false
	if reserved {
		previousTotal, previousOK := checkedBudgetSum(clientBytes, exitBytes)
		nextTotal, nextOK := checkedBudgetSum(report.ClientToExitBytes, report.ExitToClientBytes)
		delta, deltaOK := checkedBudgetSum(report.ClientToExitBytes-clientBytes, report.ExitToClientBytes-exitBytes)
		var committed, held int64
		var canAggregate bool
		if previousOK && nextOK && deltaOK {
			remaining := byteLimit - previousTotal
			if remaining < 0 {
				remaining = 0
			}
			converted := min(delta, remaining)
			if converted <= cycleHeld {
				committed, canAggregate = checkedBudgetSum(cycleCommitted, delta)
				held = cycleHeld - converted
			}
		}
		if canAggregate {
			combined, combinedOK := checkedBudgetSum(cycleXray, committed, held)
			budgetExceeded = cycleOverdrawn || nextTotal > byteLimit || !combinedOK || combined > cycleLimit
			_, err = tx.ExecContext(ctx,
				`UPDATE bridge_budget_cycles SET committed_olcrtc_bytes=$4, held_bytes=$5, overdrawn=$6
				 WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
				report.Subject.PanelInstance, report.Subject.UserID, cycleID,
				committed, held, budgetExceeded,
			)
		} else {
			// Directional high-water remains authoritative even when its sum
			// cannot fit in the aggregate. Keep the hold and close the cycle.
			budgetExceeded = true
			_, err = tx.ExecContext(ctx,
				`UPDATE bridge_budget_cycles SET overdrawn=TRUE
				 WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
				report.Subject.PanelInstance, report.Subject.UserID, cycleID,
			)
		}
		if err != nil {
			return ErrUnavailable
		}
	}
	_, err = tx.ExecContext(ctx,
		`UPDATE bridge_meter_sessions SET last_sequence=$4,
		 client_to_exit_bytes=$5, exit_to_client_bytes=$6, updated_at=clock_timestamp()
		 WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3`,
		report.SessionID, report.Subject.PanelInstance, report.Subject.UserID,
		report.Sequence, report.ClientToExitBytes, report.ExitToClientBytes,
	)
	if err != nil {
		return ErrUnavailable
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	if budgetExceeded {
		return ErrMeterBudgetExceeded
	}
	return nil
}
