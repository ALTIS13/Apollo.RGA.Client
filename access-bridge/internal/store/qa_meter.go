package store

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var (
	ErrQAInvalid        = errors.New("store: invalid QA journal input")
	ErrQADisabled       = errors.New("store: QA journaling disabled")
	ErrQASessionUnknown = errors.New("store: QA session unknown")
	ErrQAConflict       = errors.New("store: QA session conflict")
	ErrQAClosed         = errors.New("store: QA session closed")
)

type QAStatus string

const (
	QAActive      QAStatus = "active"
	QAClosed      QAStatus = "closed"
	QAUnconfirmed QAStatus = "unconfirmed"
)

// QASession is a diagnostic high-water row, not admission or shared budget.
type QASession struct {
	Subject           access.Subject
	InstallationID    string
	SessionID         string
	Deadline          time.Time
	Status            QAStatus
	LastSequence      int64
	ClientToExitBytes int64
	ExitToClientBytes int64
	Terminal          *QAFinal
	ClosedAt          *time.Time
}

type QAMeterReport struct {
	Subject           access.Subject
	InstallationID    string
	SessionID         string
	Sequence          int64
	ClientToExitBytes int64
	ExitToClientBytes int64
}

// QAFinal is the node's terminal observation, not a durable acknowledgement.
// The row's cumulative counters remain the last confirmed high-water.
type QAFinal struct {
	ClientToExitBytes int64
	ExitToClientBytes int64
}

func validQAKey(subject access.Subject, installationID, sessionID string) bool {
	return subject.Validate() == nil && validMeterSessionID(installationID) && validMeterSessionID(sessionID)
}

// SetQAJournalingEnabled fences new diagnostic rows for a bound installation.
// It does not enable OlcRTC or authorize a client. Disabling closes live rows
// as unconfirmed because this store cannot know bytes still in flight.
func (s *Store) SetQAJournalingEnabled(ctx context.Context, subject access.Subject, installationID string, enabled bool) error {
	if subject.Validate() != nil || !validMeterSessionID(installationID) {
		return ErrQAInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	var exists int
	err = tx.QueryRowContext(ctx, `SELECT 1 FROM bridge_installations
		WHERE installation_id=$1 AND panel_instance=$2 AND user_id=$3 FOR UPDATE`,
		installationID, subject.PanelInstance, subject.UserID).Scan(&exists)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrQADisabled
	}
	if err != nil {
		return ErrUnavailable
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO bridge_qa_installations (installation_id, enabled)
		VALUES ($1, $2) ON CONFLICT (installation_id) DO UPDATE SET enabled=EXCLUDED.enabled`, installationID, enabled)
	if err != nil {
		return ErrUnavailable
	}
	if !enabled {
		_, err = tx.ExecContext(ctx, `UPDATE bridge_qa_sessions SET status='unconfirmed',
			closed_at=clock_timestamp(), updated_at=clock_timestamp()
			WHERE installation_id=$1 AND panel_instance=$2 AND user_id=$3 AND status='active'`,
			installationID, subject.PanelInstance, subject.UserID)
		if err != nil {
			return ErrUnavailable
		}
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}

// RegisterQASession records one short-lived, already admitted personal test
// session. The caller must separately prove identity and authorize admission.
func (s *Store) RegisterQASession(ctx context.Context, subject access.Subject, installationID, sessionID string, deadline time.Time) error {
	if !validQAKey(subject, installationID, sessionID) {
		return ErrQAInvalid
	}
	now := time.Now()
	deadline = deadline.UTC().Truncate(time.Microsecond)
	if !deadline.After(now) || deadline.After(now.Add(5*time.Minute)) {
		return ErrQAInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	// Lock the binding, as disable does, so registration cannot race past a
	// completed disable. The journal flag is not the admission decision.
	var enabled bool
	err = tx.QueryRowContext(ctx, `SELECT q.enabled FROM bridge_installations i
		JOIN bridge_qa_installations q ON q.installation_id=i.installation_id
		WHERE i.installation_id=$1 AND i.panel_instance=$2 AND i.user_id=$3 FOR UPDATE OF i, q`,
		installationID, subject.PanelInstance, subject.UserID).Scan(&enabled)
	if errors.Is(err, sql.ErrNoRows) || err == nil && !enabled {
		return ErrQADisabled
	}
	if err != nil {
		return ErrUnavailable
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO bridge_qa_sessions
		(session_id, panel_instance, user_id, installation_id, deadline)
		VALUES ($1, $2, $3, $4, $5) ON CONFLICT (session_id) DO NOTHING`,
		sessionID, subject.PanelInstance, subject.UserID, installationID, deadline)
	if err != nil {
		return ErrUnavailable
	}
	row, err := loadQARow(ctx, tx, subject, installationID, sessionID, true)
	if errors.Is(err, ErrQASessionUnknown) {
		return ErrQAConflict
	}
	if err != nil {
		return err
	}
	if !row.Deadline.Equal(deadline) || row.Status != QAActive {
		return ErrQAConflict
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}

// RecordQACumulative advances only this journal's directional high-water.
// Same-sequence exact replay is safe after an ambiguous commit.
func (s *Store) RecordQACumulative(ctx context.Context, report QAMeterReport) error {
	if !validQAKey(report.Subject, report.InstallationID, report.SessionID) || report.Sequence < 1 ||
		report.ClientToExitBytes < 0 || report.ExitToClientBytes < 0 {
		return ErrQAInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ErrUnavailable
	}
	defer tx.Rollback()
	row, err := loadQARow(ctx, tx, report.Subject, report.InstallationID, report.SessionID, true)
	if err != nil {
		return err
	}
	if report.Sequence < row.LastSequence {
		return ErrMeterStale
	}
	if report.Sequence == row.LastSequence {
		if report.ClientToExitBytes == row.ClientToExitBytes && report.ExitToClientBytes == row.ExitToClientBytes {
			return nil
		}
		return ErrMeterConflict
	}
	if report.ClientToExitBytes < row.ClientToExitBytes || report.ExitToClientBytes < row.ExitToClientBytes {
		return ErrMeterRegression
	}
	// A close without a terminal total may precede the async durable flush.
	// Keep accepting monotonic high-water there without settling the session.
	if row.Status == QAClosed {
		return ErrQAClosed
	}
	status := row.Status
	if row.Terminal != nil {
		if report.ClientToExitBytes > row.Terminal.ClientToExitBytes || report.ExitToClientBytes > row.Terminal.ExitToClientBytes {
			return ErrQAClosed
		}
		if report.ClientToExitBytes == row.Terminal.ClientToExitBytes && report.ExitToClientBytes == row.Terminal.ExitToClientBytes {
			status = QAClosed
		}
	}
	_, err = tx.ExecContext(ctx, `UPDATE bridge_qa_sessions SET last_sequence=$4,
		client_to_exit_bytes=$5, exit_to_client_bytes=$6, status=$7, updated_at=clock_timestamp()
		WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3`,
		report.SessionID, report.Subject.PanelInstance, report.Subject.UserID,
		report.Sequence, report.ClientToExitBytes, report.ExitToClientBytes, status)
	if err != nil {
		return ErrUnavailable
	}
	if err := tx.Commit(); err != nil {
		return ErrUnavailable
	}
	return nil
}

// CloseQASession records an observed terminal total without treating an
// unacknowledged tail as confirmed. A late durable report may settle it.
func (s *Store) CloseQASession(ctx context.Context, subject access.Subject, installationID, sessionID string, final *QAFinal) (QASession, error) {
	if !validQAKey(subject, installationID, sessionID) || final != nil && (final.ClientToExitBytes < 0 || final.ExitToClientBytes < 0) {
		return QASession{}, ErrQAInvalid
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return QASession{}, ErrUnavailable
	}
	defer tx.Rollback()
	row, err := loadQARow(ctx, tx, subject, installationID, sessionID, true)
	if err != nil {
		return QASession{}, err
	}
	if row.Status != QAActive {
		if equalQAFinal(row.Terminal, final) {
			return row, nil
		}
		if row.Status != QAUnconfirmed || row.Terminal != nil || final == nil {
			return QASession{}, ErrQAConflict
		}
	}
	if final != nil && (final.ClientToExitBytes < row.ClientToExitBytes || final.ExitToClientBytes < row.ExitToClientBytes) {
		return QASession{}, ErrQAInvalid
	}
	status := QAUnconfirmed
	var terminalClient, terminalExit any
	if final != nil {
		terminalClient, terminalExit = final.ClientToExitBytes, final.ExitToClientBytes
		if final.ClientToExitBytes == row.ClientToExitBytes && final.ExitToClientBytes == row.ExitToClientBytes {
			status = QAClosed
		}
	}
	_, err = tx.ExecContext(ctx, `UPDATE bridge_qa_sessions SET status=$4,
		terminal_client_to_exit_bytes=$5, terminal_exit_to_client_bytes=$6,
		closed_at=clock_timestamp(), updated_at=clock_timestamp()
		WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3`,
		sessionID, subject.PanelInstance, subject.UserID, status, terminalClient, terminalExit)
	if err != nil {
		return QASession{}, ErrUnavailable
	}
	if err := tx.Commit(); err != nil {
		return QASession{}, ErrUnavailable
	}
	return s.LoadQASession(ctx, subject, installationID, sessionID)
}

func equalQAFinal(a, b *QAFinal) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

func (s *Store) LoadQASession(ctx context.Context, subject access.Subject, installationID, sessionID string) (QASession, error) {
	if !validQAKey(subject, installationID, sessionID) {
		return QASession{}, ErrQAInvalid
	}
	return loadQARow(ctx, s.db, subject, installationID, sessionID, false)
}

func loadQARow(ctx context.Context, q rowQuerier, subject access.Subject, installationID, sessionID string, lock bool) (QASession, error) {
	query := `SELECT deadline, status, last_sequence, client_to_exit_bytes, exit_to_client_bytes,
		terminal_client_to_exit_bytes, terminal_exit_to_client_bytes, closed_at
		FROM bridge_qa_sessions WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3 AND installation_id=$4`
	if lock {
		query += ` FOR UPDATE`
	}
	row := QASession{Subject: subject, InstallationID: installationID, SessionID: sessionID}
	var terminalClient, terminalExit sql.NullInt64
	var closedAt sql.NullTime
	err := q.QueryRowContext(ctx, query, sessionID, subject.PanelInstance, subject.UserID, installationID).Scan(
		&row.Deadline, &row.Status, &row.LastSequence, &row.ClientToExitBytes, &row.ExitToClientBytes,
		&terminalClient, &terminalExit, &closedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return QASession{}, ErrQASessionUnknown
	}
	if err != nil {
		return QASession{}, ErrUnavailable
	}
	if terminalClient.Valid && terminalExit.Valid {
		row.Terminal = &QAFinal{ClientToExitBytes: terminalClient.Int64, ExitToClientBytes: terminalExit.Int64}
	}
	if closedAt.Valid {
		row.ClosedAt = &closedAt.Time
	}
	return row, nil
}
