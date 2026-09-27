package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	_ "github.com/jackc/pgx/v5/stdlib"
)

func testMeterSessionID(t *testing.T) string {
	t.Helper()
	return fmt.Sprintf("meter-session-%d-%d", time.Now().UnixNano(), caseNumber.Add(1))
}

func TestMeterRegistrationNeedsExistingSubjectAndMigrationIsIdempotent(t *testing.T) {
	s, db, _ := migratedStore(t)
	ctx := context.Background()
	if err := s.Migrate(ctx); err != nil {
		t.Fatalf("second migration: %v", err)
	}
	subject := testSubject(t)
	id := testMeterSessionID(t)
	if err := s.RegisterMeterSession(ctx, subject, id); !errors.Is(err, ErrNotFound) {
		t.Fatalf("registration created missing subject: %v", err)
	}
	if _, err := s.EnsureSubject(ctx, subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	if err := s.RegisterMeterSession(ctx, subject, id); err != nil {
		t.Fatalf("first registration: %v", err)
	}
	if err := s.RegisterMeterSession(ctx, subject, id); err != nil {
		t.Fatalf("same-subject retry: %v", err)
	}
	var panel string
	var userID, sequence, clientBytes, exitBytes int64
	if err := db.QueryRowContext(ctx,
		`SELECT panel_instance, user_id, last_sequence, client_to_exit_bytes, exit_to_client_bytes
		 FROM bridge_meter_sessions WHERE session_id=$1`, id,
	).Scan(&panel, &userID, &sequence, &clientBytes, &exitBytes); err != nil {
		t.Fatalf("read committed meter row: %v", err)
	}
	if panel != subject.PanelInstance || userID != subject.UserID || sequence != 0 || clientBytes != 0 || exitBytes != 0 {
		t.Fatal("registration did not preserve a zeroed, subject-bound meter row")
	}
}

func TestMeterRegistrationCannotReassignGlobalSessionID(t *testing.T) {
	s, db, _ := migratedStore(t)
	ctx := context.Background()
	a, b := testSubject(t), testSubject(t)
	for _, subject := range []access.Subject{a, b} {
		if _, err := s.EnsureSubject(ctx, subject); err != nil {
			t.Fatalf("EnsureSubject: %v", err)
		}
	}
	id := testMeterSessionID(t)
	if err := s.RegisterMeterSession(ctx, a, id); err != nil {
		t.Fatalf("first registration: %v", err)
	}
	if err := s.RegisterMeterSession(ctx, b, id); !errors.Is(err, ErrMeterSessionConflict) {
		t.Fatalf("cross-subject re-registration was accepted: %v", err)
	}
	var panel string
	if err := db.QueryRowContext(ctx,
		`SELECT panel_instance FROM bridge_meter_sessions WHERE session_id=$1`, id,
	).Scan(&panel); err != nil || panel != a.PanelInstance {
		t.Fatalf("session ownership changed: %v", err)
	}
}

func TestMeterRegistrationRejectsMalformedIDsBeforeDatabaseAccess(t *testing.T) {
	db, _ := testDatabase(t)
	s, err := New(db)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatal("could not close dedicated test connection")
	}
	ctx := context.Background()
	subject := testSubject(t)
	for _, id := range []string{"", "too-short", strings.Repeat("a", 129), strings.Repeat("a", 15) + "/", strings.Repeat("a", 15) + "="} {
		if err := s.RegisterMeterSession(ctx, subject, id); !errors.Is(err, ErrMeterInvalid) {
			t.Fatalf("invalid ID did not fail before SQL: %v", err)
		}
	}
	if err := s.RegisterMeterSession(ctx, access.Subject{PanelInstance: "https://secret.example.invalid", UserID: 1}, testMeterSessionID(t)); !errors.Is(err, access.ErrInvalidSubject) {
		t.Fatalf("invalid subject did not fail before SQL: %v", err)
	}
}

func TestMeterRegistrationDatabaseOutageIsRedacted(t *testing.T) {
	db, _ := testDatabase(t)
	s, err := New(db)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatal("could not close dedicated test connection")
	}
	id := testMeterSessionID(t)
	err = s.RegisterMeterSession(context.Background(), testSubject(t), id)
	if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), id) {
		t.Fatalf("database error leaked report details: %v", err)
	}
}

func TestMeterRowConstraintsProtectCommittedBytesAndHistory(t *testing.T) {
	s, db, _ := migratedStore(t)
	ctx := context.Background()
	subject := testSubject(t)
	if _, err := s.EnsureSubject(ctx, subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	id := testMeterSessionID(t)
	if err := s.RegisterMeterSession(ctx, subject, id); err != nil {
		t.Fatalf("RegisterMeterSession: %v", err)
	}
	if _, err := db.ExecContext(ctx,
		`UPDATE bridge_meter_sessions SET client_to_exit_bytes=-1 WHERE session_id=$1`, id,
	); err == nil {
		t.Fatal("database accepted a negative meter counter")
	}
	if _, err := db.ExecContext(ctx,
		`DELETE FROM bridge_subjects WHERE panel_instance=$1 AND user_id=$2`,
		subject.PanelInstance, subject.UserID,
	); err == nil {
		t.Fatal("deleting a subject erased or orphaned its meter history")
	}
	var retained int
	if err := db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM bridge_meter_sessions WHERE session_id=$1`, id,
	).Scan(&retained); err != nil || retained != 1 {
		t.Fatalf("meter history was lost: %v", err)
	}
}

type meterSnapshot struct {
	sequence    int64
	clientBytes int64
	exitBytes   int64
}

func readMeterSnapshot(t *testing.T, db *sql.DB, id string) meterSnapshot {
	t.Helper()
	var got meterSnapshot
	if err := db.QueryRowContext(context.Background(),
		`SELECT last_sequence, client_to_exit_bytes, exit_to_client_bytes
		 FROM bridge_meter_sessions WHERE session_id=$1`, id,
	).Scan(&got.sequence, &got.clientBytes, &got.exitBytes); err != nil {
		t.Fatalf("read committed meter state: %v", err)
	}
	return got
}

func registeredMeter(t *testing.T) (*Store, *sql.DB, string, access.Subject, string) {
	t.Helper()
	s, db, dsn := migratedStore(t)
	subject := testSubject(t)
	if _, err := s.EnsureSubject(context.Background(), subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	id := testMeterSessionID(t)
	if err := s.RegisterMeterSession(context.Background(), subject, id); err != nil {
		t.Fatalf("RegisterMeterSession: %v", err)
	}
	return s, db, dsn, subject, id
}

func TestMeterReportAdvancesCommittedHighWaterAndReplaysWithoutDoubleCount(t *testing.T) {
	s, db, _, subject, id := registeredMeter(t)
	ctx := context.Background()
	first := MeterReport{Subject: subject, SessionID: id, Sequence: 1, ClientToExitBytes: 100, ExitToClientBytes: 200}
	if err := s.RecordCumulative(ctx, first); err != nil {
		t.Fatalf("first report: %v", err)
	}
	if got := readMeterSnapshot(t, db, id); got != (meterSnapshot{1, 100, 200}) {
		t.Fatalf("first high-water: %+v", got)
	}
	if err := s.RecordCumulative(ctx, first); err != nil {
		t.Fatalf("exact replay: %v", err)
	}
	if got := readMeterSnapshot(t, db, id); got != (meterSnapshot{1, 100, 200}) {
		t.Fatalf("replay altered high-water: %+v", got)
	}
	skipped := MeterReport{Subject: subject, SessionID: id, Sequence: 4, ClientToExitBytes: 250, ExitToClientBytes: 260}
	if err := s.RecordCumulative(ctx, skipped); err != nil {
		t.Fatalf("skipped sequence with cumulative bytes: %v", err)
	}
	if got := readMeterSnapshot(t, db, id); got != (meterSnapshot{4, 250, 260}) {
		t.Fatalf("skipped sequence lost bytes: %+v", got)
	}
	state, err := s.Load(ctx, subject)
	if err != nil || state.OlcRTCEnabled {
		t.Fatalf("meter report authorized OlcRTC: %+v, %v", state, err)
	}
}

func TestMeterReportRejectsConflictStaleAndCounterRegressionWithoutMutation(t *testing.T) {
	s, db, _, subject, id := registeredMeter(t)
	ctx := context.Background()
	for _, report := range []MeterReport{
		{Subject: subject, SessionID: id, Sequence: 1, ClientToExitBytes: 100, ExitToClientBytes: 200},
		{Subject: subject, SessionID: id, Sequence: 2, ClientToExitBytes: 150, ExitToClientBytes: 260},
	} {
		if err := s.RecordCumulative(ctx, report); err != nil {
			t.Fatalf("setup report: %v", err)
		}
	}
	for _, tc := range []struct {
		name   string
		report MeterReport
		want   error
	}{
		{"same-sequence changed bytes", MeterReport{Subject: subject, SessionID: id, Sequence: 2, ClientToExitBytes: 151, ExitToClientBytes: 260}, ErrMeterConflict},
		{"older sequence", MeterReport{Subject: subject, SessionID: id, Sequence: 1, ClientToExitBytes: 100, ExitToClientBytes: 200}, ErrMeterStale},
		{"client counter regression", MeterReport{Subject: subject, SessionID: id, Sequence: 3, ClientToExitBytes: 149, ExitToClientBytes: 270}, ErrMeterRegression},
		{"exit counter regression", MeterReport{Subject: subject, SessionID: id, Sequence: 3, ClientToExitBytes: 170, ExitToClientBytes: 259}, ErrMeterRegression},
		{"zero sequence", MeterReport{Subject: subject, SessionID: id, Sequence: 0, ClientToExitBytes: 170, ExitToClientBytes: 270}, ErrMeterInvalid},
		{"negative counter", MeterReport{Subject: subject, SessionID: id, Sequence: 3, ClientToExitBytes: -1, ExitToClientBytes: 270}, ErrMeterInvalid},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := s.RecordCumulative(ctx, tc.report); !errors.Is(err, tc.want) {
				t.Fatalf("wrong rejection: %v", err)
			}
			if got := readMeterSnapshot(t, db, id); got != (meterSnapshot{2, 150, 260}) {
				t.Fatalf("rejected report changed committed bytes: %+v", got)
			}
		})
	}
}

func TestMeterReportUnknownAndWrongSubjectAreIndistinguishable(t *testing.T) {
	s, db, _, subject, id := registeredMeter(t)
	other, missing := testSubject(t), testSubject(t)
	if _, err := s.EnsureSubject(context.Background(), other); err != nil {
		t.Fatalf("EnsureSubject other: %v", err)
	}
	unknownID := testMeterSessionID(t)
	unknown := MeterReport{Subject: subject, SessionID: unknownID, Sequence: 1, ClientToExitBytes: 1}
	wrong := MeterReport{Subject: other, SessionID: id, Sequence: 1, ClientToExitBytes: 1}
	missingSubject := MeterReport{Subject: missing, SessionID: id, Sequence: 1, ClientToExitBytes: 1}
	var firstError string
	for _, report := range []MeterReport{unknown, wrong, missingSubject} {
		err := s.RecordCumulative(context.Background(), report)
		if !errors.Is(err, ErrMeterSessionUnknown) {
			t.Fatalf("unknown/wrong-subject report was accepted: %v", err)
		}
		if firstError == "" {
			firstError = err.Error()
		} else if err.Error() != firstError {
			t.Fatal("report revealed whether a session belongs to another subject")
		}
	}
	var created int
	if err := db.QueryRowContext(context.Background(),
		`SELECT COUNT(*) FROM bridge_meter_sessions WHERE session_id=$1`, unknownID,
	).Scan(&created); err != nil || created != 0 {
		t.Fatalf("unknown report created a session: %v", err)
	}
	if _, err := s.Load(context.Background(), missing); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown report created a subject: %v", err)
	}
}

func TestMeterReportSurvivesNewHandleAndRegistrationRetryDoesNotReset(t *testing.T) {
	s, _, dsn, subject, id := registeredMeter(t)
	ctx := context.Background()
	first := MeterReport{Subject: subject, SessionID: id, Sequence: 1, ClientToExitBytes: 100, ExitToClientBytes: 200}
	if err := s.RecordCumulative(ctx, first); err != nil {
		t.Fatalf("first report: %v", err)
	}
	db2, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal("could not open second dedicated test connection")
	}
	t.Cleanup(func() { _ = db2.Close() })
	s2, err := New(db2)
	if err != nil {
		t.Fatalf("New after reconnect: %v", err)
	}
	if err := s2.RegisterMeterSession(ctx, subject, id); err != nil {
		t.Fatalf("retry registration after traffic: %v", err)
	}
	if got := readMeterSnapshot(t, db2, id); got != (meterSnapshot{1, 100, 200}) {
		t.Fatalf("retry reset committed meter: %+v", got)
	}
	if err := s2.RecordCumulative(ctx, first); err != nil {
		t.Fatalf("replay after reconnect: %v", err)
	}
	if err := s2.RecordCumulative(ctx, MeterReport{Subject: subject, SessionID: id, Sequence: 2, ClientToExitBytes: 175, ExitToClientBytes: 240}); err != nil {
		t.Fatalf("second report after reconnect: %v", err)
	}
	if got := readMeterSnapshot(t, db2, id); got != (meterSnapshot{2, 175, 240}) {
		t.Fatalf("restart lost or double-counted bytes: %+v", got)
	}
}

func TestMeterConcurrentConflictingReportsCannotOverwriteHighWater(t *testing.T) {
	s, db, _, subject, id := registeredMeter(t)
	start := make(chan struct{})
	results := make(chan error, 2)
	var wg sync.WaitGroup
	for _, clientBytes := range []int64{100, 200} {
		wg.Add(1)
		go func(clientBytes int64) {
			defer wg.Done()
			<-start
			results <- s.RecordCumulative(context.Background(), MeterReport{
				Subject: subject, SessionID: id, Sequence: 1,
				ClientToExitBytes: clientBytes, ExitToClientBytes: 300,
			})
		}(clientBytes)
	}
	close(start)
	wg.Wait()
	close(results)
	var accepted, conflicts int
	for err := range results {
		switch {
		case err == nil:
			accepted++
		case errors.Is(err, ErrMeterConflict):
			conflicts++
		default:
			t.Fatalf("unexpected concurrent report result: %v", err)
		}
	}
	if accepted != 1 || conflicts != 1 {
		t.Fatalf("conflicting reports both accepted: accepted=%d conflict=%d", accepted, conflicts)
	}
	got := readMeterSnapshot(t, db, id)
	if got.sequence != 1 || (got.clientBytes != 100 && got.clientBytes != 200) || got.exitBytes != 300 {
		t.Fatalf("concurrent report corrupted high-water: %+v", got)
	}
}

func TestMeterReportDatabaseOutageIsRedacted(t *testing.T) {
	db, _ := testDatabase(t)
	s, err := New(db)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatal("could not close dedicated test connection")
	}
	id := testMeterSessionID(t)
	report := MeterReport{Subject: testSubject(t), SessionID: id, Sequence: 1, ClientToExitBytes: 123, ExitToClientBytes: 456}
	err = s.RecordCumulative(context.Background(), report)
	if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), id) || strings.Contains(err.Error(), "123") {
		t.Fatalf("database outage leaked report details: %v", err)
	}
}
