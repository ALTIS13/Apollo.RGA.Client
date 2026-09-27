package store

import (
	"context"
	"errors"
	"fmt"
	"strings"
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
