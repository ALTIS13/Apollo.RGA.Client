package store

import (
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"
)

func renewRequest(join QAJoinRequest) QARenewRequest {
	return QARenewRequest{
		Subject: join.Subject, InstallationID: join.InstallationID,
		PublicKeySHA256: join.PublicKeySHA256,
		RoomID:          join.RoomID, Country: join.Country, SessionID: join.SessionID,
		PolicyRevision: join.PolicyRevision,
		ObservedAt:     time.Now(), MaxSnapshotAge: join.MaxSnapshotAge,
		SubscriptionExpiresAt: join.SubscriptionExpiresAt,
		NewDeadline:           join.Deadline.Add(time.Minute),
	}
}

func TestQARenewAtSubscriptionCapIsIdempotentWithoutMeterReset(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	join.Deadline = time.Now().Add(4 * time.Minute).UTC().Truncate(time.Microsecond)
	join.SubscriptionExpiresAt = join.Deadline
	if err := s.RedeemQAJoin(ctx, join); err != nil {
		t.Fatal(err)
	}
	if err := s.RecordQACumulative(ctx, QAMeterReport{
		Subject: join.Subject, InstallationID: join.InstallationID, SessionID: join.SessionID,
		Sequence: 3, ClientToExitBytes: 7, ExitToClientBytes: 11,
	}); err != nil {
		t.Fatal(err)
	}
	var updatedBefore time.Time
	if err := s.db.QueryRowContext(ctx, `SELECT updated_at FROM bridge_qa_sessions WHERE session_id=$1`,
		join.SessionID).Scan(&updatedBefore); err != nil {
		t.Fatal(err)
	}
	req := renewRequest(join)
	req.NewDeadline = join.Deadline
	for attempt := 0; attempt < 2; attempt++ {
		deadline, err := s.RenewQADeadline(ctx, req)
		if err != nil || !deadline.Equal(join.Deadline) {
			t.Fatalf("PERSONAL_EXPIRY_CAP: healthy capped renewal denied or extended: %v %v", deadline, err)
		}
	}
	row, err := s.LoadQASession(ctx, join.Subject, join.InstallationID, join.SessionID)
	if err != nil || row.Status != QAActive || !row.Deadline.Equal(join.Deadline) ||
		row.LastSequence != 3 || row.ClientToExitBytes != 7 || row.ExitToClientBytes != 11 {
		t.Fatalf("PERSONAL_EXPIRY_CAP: capped renewal changed lease or cumulative meter: %+v %v", row, err)
	}
	var updatedAfter time.Time
	if err := s.db.QueryRowContext(ctx, `SELECT updated_at FROM bridge_qa_sessions WHERE session_id=$1`,
		join.SessionID).Scan(&updatedAfter); err != nil {
		t.Fatal(err)
	}
	if !updatedAfter.Equal(updatedBefore) {
		t.Fatal("PERSONAL_EXPIRY_CAP: identical capped renewal mutated its journal row")
	}
}

func TestQARenewAtSubscriptionCapStillHonorsAdmissionFences(t *testing.T) {
	for _, name := range []string{"not-current-cap", "stale-panel", "closed-session", "disabled-protocol"} {
		t.Run(name, func(t *testing.T) {
			s, join := qaJoinFixture(t)
			ctx := context.Background()
			join.Deadline = time.Now().Add(4 * time.Minute).UTC().Truncate(time.Microsecond)
			join.SubscriptionExpiresAt = join.Deadline
			if err := s.RedeemQAJoin(ctx, join); err != nil {
				t.Fatal(err)
			}
			req := renewRequest(join)
			req.NewDeadline = join.Deadline
			switch name {
			case "not-current-cap":
				req.SubscriptionExpiresAt = req.SubscriptionExpiresAt.Add(time.Minute)
			case "stale-panel":
				req.ObservedAt = time.Now().Add(-time.Minute)
			case "closed-session":
				if _, err := s.CloseQASession(ctx, join.Subject, join.InstallationID, join.SessionID, nil); err != nil {
					t.Fatal(err)
				}
			case "disabled-protocol":
				if _, err := s.SetProtocolEnabled(ctx, join.Subject, false); err != nil {
					t.Fatal(err)
				}
			}
			if deadline, err := s.RenewQADeadline(ctx, req); !errors.Is(err, ErrQARenewDenied) || !deadline.IsZero() {
				t.Fatalf("PERSONAL_EXPIRY_CAP: capped renewal bypassed %s: %v %v", name, deadline, err)
			}
			row, err := s.LoadQASession(ctx, join.Subject, join.InstallationID, join.SessionID)
			if err != nil || !row.Deadline.Equal(join.Deadline) {
				t.Fatalf("PERSONAL_EXPIRY_CAP: refusal changed original end: %+v %v", row, err)
			}
		})
	}
}

func TestQARenewPersistsFreshDeadlineOnlyForAdmittedSession(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	if err := s.RedeemQAJoin(ctx, join); err != nil {
		t.Fatal(err)
	}
	var originalRevision sql.NullInt64
	if err := s.db.QueryRowContext(ctx, `SELECT policy_revision FROM bridge_qa_sessions WHERE session_id=$1`, join.SessionID).Scan(&originalRevision); err != nil {
		t.Fatal(err)
	}
	if !originalRevision.Valid || originalRevision.Int64 != join.PolicyRevision {
		t.Fatal("admitted session did not retain its original policy revision")
	}
	req := renewRequest(join)
	deadline, err := s.RenewQADeadline(ctx, req)
	if err != nil || !deadline.Equal(req.NewDeadline.UTC().Truncate(time.Microsecond)) {
		t.Fatalf("valid renewal failed: deadline=%v err=%v", deadline, err)
	}
	row, err := s.LoadQASession(ctx, join.Subject, join.InstallationID, join.SessionID)
	if err != nil || row.Status != QAActive || !row.Deadline.Equal(deadline) {
		t.Fatalf("renewal did not durably update active row: %+v %v", row, err)
	}
	if repeated, err := s.RenewQADeadline(ctx, req); !errors.Is(err, ErrQARenewDenied) || !repeated.IsZero() {
		t.Fatalf("non-increasing renewal accepted: %v %v", repeated, err)
	}
}

func TestQARenewDeniesWrongScopeStaleFactsAndOversizedLease(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func(*QARenewRequest)
	}{
		{"wrong subject", func(r *QARenewRequest) { r.Subject.UserID++ }},
		{"wrong installation", func(r *QARenewRequest) { r.InstallationID = "other-installation-000001" }},
		{"wrong key", func(r *QARenewRequest) { r.PublicKeySHA256[0] ^= 1 }},
		{"wrong room", func(r *QARenewRequest) { r.RoomID = "other-room-000001" }},
		{"wrong country", func(r *QARenewRequest) { r.Country = "DE" }},
		{"wrong revision", func(r *QARenewRequest) { r.PolicyRevision++ }},
		{"stale panel facts", func(r *QARenewRequest) { r.ObservedAt = time.Now().Add(-time.Minute) }},
		{"expired subscription", func(r *QARenewRequest) { r.SubscriptionExpiresAt = time.Now().Add(-time.Second) }},
		{"past subscription expiry", func(r *QARenewRequest) { r.SubscriptionExpiresAt = r.NewDeadline.Add(-time.Second) }},
		{"over five minutes", func(r *QARenewRequest) { r.NewDeadline = time.Now().Add(6 * time.Minute) }},
		{"not increasing", func(r *QARenewRequest) { r.NewDeadline = r.NewDeadline.Add(-2 * time.Minute) }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, join := qaJoinFixture(t)
			if err := s.RedeemQAJoin(context.Background(), join); err != nil {
				t.Fatal(err)
			}
			req := renewRequest(join)
			tc.mutate(&req)
			deadline, err := s.RenewQADeadline(context.Background(), req)
			if !errors.Is(err, ErrQARenewDenied) || !deadline.IsZero() {
				t.Fatalf("invalid renewal accepted: %v %v", deadline, err)
			}
			row, err := s.LoadQASession(context.Background(), join.Subject, join.InstallationID, join.SessionID)
			if err != nil || !row.Deadline.Equal(join.Deadline.UTC().Truncate(time.Microsecond)) {
				t.Fatalf("denied renewal changed deadline: %+v %v", row, err)
			}
		})
	}
}

func TestQARenewRejectsOriginalRevisionAfterDisableThenEnable(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	if err := s.RedeemQAJoin(ctx, join); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetProtocolEnabled(ctx, join.Subject, false); err != nil {
		t.Fatal(err)
	}
	current, err := s.SetProtocolEnabled(ctx, join.Subject, true)
	if err != nil || current.Revision <= join.PolicyRevision {
		t.Fatalf("flag did not advance revision: %+v %v", current, err)
	}
	req := renewRequest(join)
	req.PolicyRevision = current.Revision
	deadline, err := s.RenewQADeadline(ctx, req)
	if !errors.Is(err, ErrQARenewDenied) || !deadline.IsZero() {
		t.Fatalf("old session revived after off-on: %v %v", deadline, err)
	}
}

func TestQARenewDoesNotReviveClosedOrExpiredSession(t *testing.T) {
	for _, expired := range []bool{false, true} {
		s, join := qaJoinFixture(t)
		ctx := context.Background()
		if err := s.RedeemQAJoin(ctx, join); err != nil {
			t.Fatal(err)
		}
		if expired {
			if _, err := s.db.ExecContext(ctx,
				`UPDATE bridge_qa_sessions SET deadline=clock_timestamp()-interval '1 second' WHERE session_id=$1`, join.SessionID); err != nil {
				t.Fatal(err)
			}
		} else if _, err := s.CloseQASession(ctx, join.Subject, join.InstallationID, join.SessionID, nil); err != nil {
			t.Fatal(err)
		}
		deadline, err := s.RenewQADeadline(ctx, renewRequest(join))
		if !errors.Is(err, ErrQARenewDenied) || !deadline.IsZero() {
			t.Fatalf("closed=%t expired=%t revived: %v %v", !expired, expired, deadline, err)
		}
	}
}

func TestQARenewLeavesLegacyRevisionlessRowsIneligibleAfterRepeatedMigration(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	if err := s.RegisterQASession(ctx, join.Subject, join.InstallationID, join.SessionID, join.Deadline); err != nil {
		t.Fatal(err)
	}
	if err := s.Migrate(ctx); err != nil {
		t.Fatalf("first reapply: %v", err)
	}
	if err := s.Migrate(ctx); err != nil {
		t.Fatalf("second reapply: %v", err)
	}
	var revision sql.NullInt64
	if err := s.db.QueryRowContext(ctx, `SELECT policy_revision FROM bridge_qa_sessions WHERE session_id=$1`, join.SessionID).Scan(&revision); err != nil {
		t.Fatal(err)
	}
	if revision.Valid {
		t.Fatal("migration fabricated an admission revision for a legacy journal row")
	}
	deadline, err := s.RenewQADeadline(ctx, renewRequest(join))
	if !errors.Is(err, ErrQARenewDenied) || !deadline.IsZero() {
		t.Fatalf("legacy row renewed: %v %v", deadline, err)
	}
}

func TestQARenewDatabaseOutageReturnsNoDeadline(t *testing.T) {
	s, join := qaJoinFixture(t)
	if err := s.RedeemQAJoin(context.Background(), join); err != nil {
		t.Fatal(err)
	}
	if err := s.db.Close(); err != nil {
		t.Fatal(err)
	}
	deadline, err := s.RenewQADeadline(context.Background(), renewRequest(join))
	if !errors.Is(err, ErrUnavailable) || !deadline.IsZero() {
		t.Fatalf("database outage returned deadline: %v %v", deadline, err)
	}
}

func TestQARenewQueuedAfterInstallationDisableCannotExtendSession(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	if err := s.RedeemQAJoin(ctx, join); err != nil {
		t.Fatal(err)
	}
	guard, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Rollback()
	var exists int
	if err := guard.QueryRowContext(ctx, `SELECT 1 FROM bridge_installations
		WHERE installation_id=$1 FOR UPDATE`, join.InstallationID).Scan(&exists); err != nil {
		t.Fatal(err)
	}
	disabled := make(chan error, 1)
	go func() { disabled <- s.SetQAJournalingEnabled(ctx, join.Subject, join.InstallationID, false) }()
	waitForRenewLockWaits(t, s, 1, "%bridge_installations%")
	type result struct {
		deadline time.Time
		err      error
	}
	renewed := make(chan result, 1)
	go func() {
		deadline, err := s.RenewQADeadline(ctx, renewRequest(join))
		renewed <- result{deadline, err}
	}()
	waitForRenewLockWaits(t, s, 2, "%bridge_installations%")
	if err := guard.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := <-disabled; err != nil {
		t.Fatalf("disable failed: %v", err)
	}
	got := <-renewed
	if !errors.Is(got.err, ErrQARenewDenied) || !got.deadline.IsZero() {
		t.Fatalf("renewal passed committed disable: %v %v", got.deadline, got.err)
	}
	row, err := s.LoadQASession(ctx, join.Subject, join.InstallationID, join.SessionID)
	if err != nil || row.Status != QAUnconfirmed || !row.Deadline.Equal(join.Deadline.UTC().Truncate(time.Microsecond)) {
		t.Fatalf("disable/renew race left active or extended row: %+v %v", row, err)
	}
}

func TestQARenewQueuedAfterCloseCannotReviveSession(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	if err := s.RedeemQAJoin(ctx, join); err != nil {
		t.Fatal(err)
	}
	guard, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Rollback()
	var exists int
	if err := guard.QueryRowContext(ctx, `SELECT 1 FROM bridge_qa_sessions
		WHERE session_id=$1 FOR UPDATE`, join.SessionID).Scan(&exists); err != nil {
		t.Fatal(err)
	}
	closed := make(chan error, 1)
	go func() {
		_, err := s.CloseQASession(ctx, join.Subject, join.InstallationID, join.SessionID, nil)
		closed <- err
	}()
	waitForRenewLockWaits(t, s, 1, "%bridge_qa_sessions%")
	type result struct {
		deadline time.Time
		err      error
	}
	renewed := make(chan result, 1)
	go func() {
		deadline, err := s.RenewQADeadline(ctx, renewRequest(join))
		renewed <- result{deadline, err}
	}()
	waitForRenewLockWaits(t, s, 2, "%bridge_qa_sessions%")
	if err := guard.Rollback(); err != nil {
		t.Fatal(err)
	}
	if err := <-closed; err != nil {
		t.Fatalf("close failed: %v", err)
	}
	got := <-renewed
	if !errors.Is(got.err, ErrQARenewDenied) || !got.deadline.IsZero() {
		t.Fatalf("renewal passed committed close: %v %v", got.deadline, got.err)
	}
	row, err := s.LoadQASession(ctx, join.Subject, join.InstallationID, join.SessionID)
	if err != nil || row.Status != QAUnconfirmed || !row.Deadline.Equal(join.Deadline.UTC().Truncate(time.Microsecond)) {
		t.Fatalf("close/renew race revived or extended row: %+v %v", row, err)
	}
}

func waitForRenewLockWaits(t *testing.T, s *Store, want int, pattern string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		var blocked int
		err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM pg_stat_activity
			WHERE datname=current_database() AND usename=current_user
				AND wait_event_type='Lock' AND query LIKE $1`, pattern).Scan(&blocked)
		if err != nil {
			t.Fatalf("inspect session lock waits: %v", err)
		}
		if blocked >= want {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatalf("saw %d session lock waits, want at least %d", blocked, want)
		case <-ticker.C:
		}
	}
}
