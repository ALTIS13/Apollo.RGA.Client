package store

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/jackc/pgx/v5/pgconn"
)

func qaFixture(t *testing.T) (*Store, access.Subject, string, string) {
	t.Helper()
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	subject := testSubject(t)
	binding := testBinding(t, 67)
	if _, err := s.EnsureSubject(ctx, subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	if err := s.BindInstallation(ctx, subject, binding); err != nil {
		t.Fatalf("BindInstallation: %v", err)
	}
	if err := s.SetQAJournalingEnabled(ctx, subject, binding.ID, true); err != nil {
		t.Fatalf("SetQAJournalingEnabled: %v", err)
	}
	return s, subject, binding.ID, fmt.Sprintf("qa-session-%d-%d", time.Now().UnixNano(), caseNumber.Add(1))
}

func TestQAInvalidInputFailsBeforeDatabaseAccess(t *testing.T) {
	s := &Store{}
	subject := access.Subject{PanelInstance: "test", UserID: 42}
	if err := s.RegisterQASession(context.Background(), subject, "short", "qa-session-000001", time.Now().Add(time.Minute)); !errors.Is(err, ErrQAInvalid) {
		t.Fatalf("invalid installation: %v", err)
	}
	if err := s.RegisterQASession(context.Background(), subject, "installation-000001", "short", time.Now().Add(time.Minute)); !errors.Is(err, ErrQAInvalid) {
		t.Fatalf("invalid session: %v", err)
	}
	if err := s.RecordQACumulative(context.Background(), QAMeterReport{Subject: subject, InstallationID: "installation-000001", SessionID: "qa-session-000001", Sequence: 0}); !errors.Is(err, ErrQAInvalid) {
		t.Fatalf("zero sequence: %v", err)
	}
}

func TestQAJournalingRequiresBoundEnabledInstallationAndNeverCreatesBudget(t *testing.T) {
	s, subject, installation, id := qaFixture(t)
	ctx := context.Background()
	deadline := time.Now().Add(2 * time.Minute)
	if err := s.RegisterQASession(ctx, subject, "unknown-installation-000001", id, deadline); !errors.Is(err, ErrQADisabled) {
		t.Fatalf("unbound installation: %v", err)
	}
	if err := s.RegisterQASession(ctx, subject, installation, id, deadline); err != nil {
		t.Fatalf("RegisterQASession: %v", err)
	}
	if err := s.RegisterQASession(ctx, subject, installation, id, deadline); err != nil {
		t.Fatalf("idempotent registration: %v", err)
	}
	other := testSubject(t)
	if _, err := s.EnsureSubject(ctx, other); err != nil {
		t.Fatal(err)
	}
	if err := s.RegisterQASession(ctx, other, installation, id, deadline); !errors.Is(err, ErrQADisabled) {
		t.Fatalf("wrong subject registration: %v", err)
	}
	otherBinding := testBinding(t, 68)
	if err := s.BindInstallation(ctx, other, otherBinding); err != nil {
		t.Fatal(err)
	}
	if err := s.SetQAJournalingEnabled(ctx, other, otherBinding.ID, true); err != nil {
		t.Fatal(err)
	}
	if err := s.RegisterQASession(ctx, other, otherBinding.ID, id, deadline); !errors.Is(err, ErrQAConflict) {
		t.Fatalf("global session ID reused by another subject: %v", err)
	}
	row, err := s.LoadQASession(ctx, subject, installation, id)
	if err != nil || row.Status != QAActive || row.LastSequence != 0 || row.ClientToExitBytes != 0 || row.ExitToClientBytes != 0 {
		t.Fatalf("fresh journal: %+v, %v", row, err)
	}
	var cycles, leases, ordinaryMeters int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2`,
		subject.PanelInstance, subject.UserID).Scan(&cycles); err != nil {
		t.Fatal(err)
	}
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_budget_leases WHERE panel_instance=$1 AND user_id=$2`,
		subject.PanelInstance, subject.UserID).Scan(&leases); err != nil {
		t.Fatal(err)
	}
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_meter_sessions WHERE panel_instance=$1 AND user_id=$2`,
		subject.PanelInstance, subject.UserID).Scan(&ordinaryMeters); err != nil {
		t.Fatal(err)
	}
	if cycles != 0 || leases != 0 || ordinaryMeters != 0 {
		t.Fatalf("QA journal touched ordinary accounting: cycles=%d leases=%d meters=%d", cycles, leases, ordinaryMeters)
	}
}

func TestQASchemaRejectsInstallationFromAnotherSubject(t *testing.T) {
	s, _, installation, id := qaFixture(t)
	ctx := context.Background()
	other := testSubject(t)
	if _, err := s.EnsureSubject(ctx, other); err != nil {
		t.Fatal(err)
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `INSERT INTO bridge_qa_sessions
		(session_id, panel_instance, user_id, installation_id, deadline)
		VALUES ($1, $2, $3, $4, $5)`,
		id, other.PanelInstance, other.UserID, installation, time.Now().Add(time.Minute))
	var pgErr *pgconn.PgError
	if !errors.As(err, &pgErr) || pgErr.Code != "23503" || pgErr.ConstraintName != "bridge_qa_sessions_installation_subject_fkey" {
		t.Fatalf("mismatched installation/subject insert = %v, want composite foreign-key rejection", err)
	}
}

func TestQACumulativeHighWaterRejectsReplayConflictStaleAndRegression(t *testing.T) {
	s, subject, installation, id := qaFixture(t)
	ctx := context.Background()
	if err := s.RegisterQASession(ctx, subject, installation, id, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	first := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 2, ClientToExitBytes: 100, ExitToClientBytes: 40}
	if err := s.RecordQACumulative(ctx, first); err != nil {
		t.Fatalf("first high-water: %v", err)
	}
	db2, _ := testDatabase(t)
	s2, err := New(db2)
	if err != nil {
		t.Fatal(err)
	}
	if err := s2.RecordQACumulative(ctx, first); err != nil {
		t.Fatalf("ambiguous acknowledgement replay after new handle: %v", err)
	}
	if err := s.RegisterQASession(ctx, subject, installation, id, time.Now().Add(time.Minute)); !errors.Is(err, ErrQAConflict) {
		t.Fatalf("different deadline reused the ID: %v", err)
	}
	rowAfterReport, err := s.LoadQASession(ctx, subject, installation, id)
	if err != nil {
		t.Fatal(err)
	}
	if err := s2.RegisterQASession(ctx, subject, installation, id, rowAfterReport.Deadline); err != nil {
		t.Fatalf("same registration after traffic should remain idempotent: %v", err)
	}
	checks := []struct {
		name   string
		report QAMeterReport
		want   error
	}{
		{"conflict", QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 2, ClientToExitBytes: 101, ExitToClientBytes: 40}, ErrMeterConflict},
		{"stale", QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 1, ClientToExitBytes: 100, ExitToClientBytes: 40}, ErrMeterStale},
		{"regression", QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 3, ClientToExitBytes: 99, ExitToClientBytes: 41}, ErrMeterRegression},
	}
	for _, check := range checks {
		t.Run(check.name, func(t *testing.T) {
			if err := s.RecordQACumulative(ctx, check.report); !errors.Is(err, check.want) {
				t.Fatalf("RecordQACumulative = %v, want %v", err, check.want)
			}
		})
	}
	row, err := s.LoadQASession(ctx, subject, installation, id)
	if err != nil || row.LastSequence != 2 || row.ClientToExitBytes != 100 || row.ExitToClientBytes != 40 {
		t.Fatalf("high-water changed after rejected replay: %+v, %v", row, err)
	}
}

func TestQAClosePreservesConfirmedHighWaterAndIdentifiesUnconfirmedTail(t *testing.T) {
	s, subject, installation, id := qaFixture(t)
	ctx := context.Background()
	if err := s.RegisterQASession(ctx, subject, installation, id, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	first := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 1, ClientToExitBytes: 10, ExitToClientBytes: 5}
	if err := s.RecordQACumulative(ctx, first); err != nil {
		t.Fatal(err)
	}
	final := &QAFinal{ClientToExitBytes: 12, ExitToClientBytes: 7}
	row, err := s.CloseQASession(ctx, subject, installation, id, final)
	if err != nil || row.Status != QAUnconfirmed || row.ClientToExitBytes != 10 || row.ExitToClientBytes != 5 {
		t.Fatalf("unconfirmed close: %+v, %v", row, err)
	}
	late := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 2, ClientToExitBytes: 12, ExitToClientBytes: 7}
	if err := s.RecordQACumulative(ctx, late); err != nil {
		t.Fatalf("late durable acknowledgement: %v", err)
	}
	row, err = s.LoadQASession(ctx, subject, installation, id)
	if err != nil || row.Status != QAClosed || row.ClientToExitBytes != 12 || row.ExitToClientBytes != 7 {
		t.Fatalf("confirmed close: %+v, %v", row, err)
	}
	if err := s.RecordQACumulative(ctx, QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 3, ClientToExitBytes: 13, ExitToClientBytes: 7}); !errors.Is(err, ErrQAClosed) {
		t.Fatalf("post-terminal traffic: %v", err)
	}
}

func TestQACloseBeforeAsyncFlushKeepsLateDurableHighWaterUnconfirmed(t *testing.T) {
	s, subject, installation, id := qaFixture(t)
	ctx := context.Background()
	deadline := time.Now().Add(time.Minute)
	if err := s.RegisterQASession(ctx, subject, installation, id, deadline); err != nil {
		t.Fatal(err)
	}
	first := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 1, ClientToExitBytes: 10, ExitToClientBytes: 5}
	if err := s.RecordQACumulative(ctx, first); err != nil {
		t.Fatal(err)
	}
	closed, err := s.CloseQASession(ctx, subject, installation, id, nil)
	if err != nil || closed.Status != QAUnconfirmed || closed.Terminal != nil || closed.ClosedAt == nil {
		t.Fatalf("close before final async flush: %+v, %v", closed, err)
	}
	late := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 2, ClientToExitBytes: 12, ExitToClientBytes: 7}
	if err := s.RecordQACumulative(ctx, late); err != nil {
		t.Fatalf("late durable snapshot after close without terminal: %v", err)
	}
	db2, _ := testDatabase(t)
	s2, err := New(db2)
	if err != nil {
		t.Fatal(err)
	}
	if err := s2.RecordQACumulative(ctx, late); err != nil {
		t.Fatalf("late snapshot replay after a new store handle: %v", err)
	}
	for _, check := range []struct {
		name   string
		report QAMeterReport
		want   error
	}{
		{"conflicting replay", QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 2, ClientToExitBytes: 13, ExitToClientBytes: 7}, ErrMeterConflict},
		{"stale sequence", first, ErrMeterStale},
		{"counter regression", QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 3, ClientToExitBytes: 11, ExitToClientBytes: 8}, ErrMeterRegression},
	} {
		t.Run(check.name, func(t *testing.T) {
			if err := s2.RecordQACumulative(ctx, check.report); !errors.Is(err, check.want) {
				t.Fatalf("RecordQACumulative = %v, want %v", err, check.want)
			}
		})
	}
	newer := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 3, ClientToExitBytes: 14, ExitToClientBytes: 8}
	if err := s2.RecordQACumulative(ctx, newer); err != nil {
		t.Fatalf("second late durable snapshot: %v", err)
	}
	row, err := s.LoadQASession(ctx, subject, installation, id)
	if err != nil || row.Status != QAUnconfirmed || row.Terminal != nil || row.ClosedAt == nil ||
		row.LastSequence != 3 || row.ClientToExitBytes != 14 || row.ExitToClientBytes != 8 {
		t.Fatalf("late high-water must remain unconfirmed: %+v, %v", row, err)
	}
	if err := s.RegisterQASession(ctx, subject, installation, id, row.Deadline); !errors.Is(err, ErrQAConflict) {
		t.Fatalf("closed session reopened by registration: %v", err)
	}
	confirmed, err := s.CloseQASession(ctx, subject, installation, id, &QAFinal{ClientToExitBytes: 14, ExitToClientBytes: 8})
	if err != nil || confirmed.Status != QAClosed {
		t.Fatalf("later terminal observation: %+v, %v", confirmed, err)
	}
	if err := s.RecordQACumulative(ctx, QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id, Sequence: 4, ClientToExitBytes: 15, ExitToClientBytes: 8}); !errors.Is(err, ErrQAClosed) {
		t.Fatalf("post-terminal report: %v", err)
	}
}

func TestQADisableAndCloseRaceLeavesNoActiveSession(t *testing.T) {
	s, subject, installation, id := qaFixture(t)
	ctx := context.Background()
	if err := s.RegisterQASession(ctx, subject, installation, id, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	errCh := make(chan error, 2)
	final := &QAFinal{ClientToExitBytes: 2, ExitToClientBytes: 1}
	wg.Add(2)
	go func() {
		defer wg.Done()
		errCh <- s.SetQAJournalingEnabled(ctx, subject, installation, false)
	}()
	go func() {
		defer wg.Done()
		_, err := s.CloseQASession(ctx, subject, installation, id, final)
		errCh <- err
	}()
	wg.Wait()
	close(errCh)
	for err := range errCh {
		if err != nil {
			t.Fatalf("concurrent disable/close: %v", err)
		}
	}
	row, err := s.LoadQASession(ctx, subject, installation, id)
	if err != nil || row.Status != QAUnconfirmed || !equalQAFinal(row.Terminal, final) {
		t.Fatalf("disable/close lost terminal observation: %+v, %v", row, err)
	}
	if err := s.RegisterQASession(ctx, subject, installation, "qa-session-000002", time.Now().Add(time.Minute)); !errors.Is(err, ErrQADisabled) {
		t.Fatalf("new session after disable: %v", err)
	}
}

func TestQARegisterWaitingBehindDisableCannotLeaveActiveRow(t *testing.T) {
	s, subject, installation, id := qaFixture(t)
	ctx := context.Background()
	guard, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Rollback()
	var exists int
	if err := guard.QueryRowContext(ctx, `SELECT 1 FROM bridge_qa_installations
		WHERE installation_id=$1 FOR UPDATE`, installation).Scan(&exists); err != nil {
		t.Fatal(err)
	}
	disableResult := make(chan error, 1)
	go func() { disableResult <- s.SetQAJournalingEnabled(ctx, subject, installation, false) }()
	waitForQALockWaits(t, s, 1)
	registerResult := make(chan error, 1)
	go func() {
		registerResult <- s.RegisterQASession(ctx, subject, installation, id, time.Now().Add(time.Minute))
	}()
	waitForQALockWaits(t, s, 2)
	if err := guard.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := <-disableResult; err != nil {
		t.Fatalf("disable: %v", err)
	}
	if err := <-registerResult; !errors.Is(err, ErrQADisabled) {
		t.Fatalf("registration after disable committed: %v", err)
	}
	var active int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_qa_sessions
		WHERE installation_id=$1 AND status='active'`, installation).Scan(&active); err != nil {
		t.Fatal(err)
	}
	if active != 0 {
		t.Fatalf("disable committed with %d newly active rows", active)
	}
}

func waitForQALockWaits(t *testing.T, s *Store, want int) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var blocked int
		err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM pg_stat_activity
			WHERE datname=current_database() AND usename=current_user
				AND wait_event_type='Lock' AND query LIKE '%bridge_qa_installations%'`).Scan(&blocked)
		if err != nil {
			t.Fatalf("inspect QA lock waits: %v", err)
		}
		if blocked >= want {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatalf("saw %d QA lock waits, want at least %d", blocked, want)
		case <-ticker.C:
		}
	}
}
