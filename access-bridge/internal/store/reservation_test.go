package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
	_ "github.com/jackc/pgx/v5/stdlib"
)

func reservationFixture(t *testing.T) (*Store, *sql.DB, string, ReservationRequest, BudgetEvidence, ReservationLimits) {
	t.Helper()
	s, db, dsn := migratedStore(t)
	ctx := context.Background()
	subject := testSubject(t)
	if _, err := s.EnsureSubject(ctx, subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	binding := testBinding(t, 1)
	if err := s.BindInstallation(ctx, subject, binding); err != nil {
		t.Fatalf("BindInstallation: %v", err)
	}
	state, err := s.SetProtocolEnabled(ctx, subject, true)
	if err != nil {
		t.Fatalf("SetProtocolEnabled: %v", err)
	}
	now := time.Now().UTC()
	req := ReservationRequest{
		Subject: subject, InstallationID: binding.ID,
		RoomID: "test-room-nl", Country: "NL", SessionID: testMeterSessionID(t),
		JTI:            fmt.Sprintf("reservation-jti-%d-%d", now.UnixNano(), caseNumber.Add(1)),
		PolicyRevision: state.Revision, RequestedBytes: 40,
	}
	evidence := BudgetEvidence{
		Subject:    subject,
		CycleID:    fmt.Sprintf("cycle-%d-%d", now.UnixNano(), caseNumber.Add(1)),
		CycleStart: now.Add(-time.Minute), CycleEnd: now.Add(time.Hour),
		ObservedAt: now, SubscriptionExpiresAt: now.Add(time.Hour),
		Status: remnawave.StatusActive, TotalLimitBytes: 100, XrayUsedBytes: 60,
		MeasuredXrayLag: time.Second, ActiveXrayEnforcementProven: true,
	}
	limits := ReservationLimits{MaxLeaseBytes: 64, LeaseTTL: 5 * time.Minute,
		MaxEvidenceAge: time.Minute, MaxXrayLag: 5 * time.Second}
	return s, db, dsn, req, evidence, limits
}

func nextReservation(req ReservationRequest, t *testing.T) ReservationRequest {
	t.Helper()
	req.SessionID = testMeterSessionID(t)
	req.JTI = fmt.Sprintf("reservation-jti-%d-%d", time.Now().UnixNano(), caseNumber.Add(1))
	return req
}

func TestReservationRequiresVerifiedFiniteCycleAndBinding(t *testing.T) {
	ctx := context.Background()
	t.Run("verified finite cycle", func(t *testing.T) {
		s, db, _, req, evidence, limits := reservationFixture(t)
		before := time.Now()
		lease, err := s.ReserveLease(ctx, req, evidence, limits)
		if err != nil {
			t.Fatalf("ReserveLease: %v", err)
		}
		if lease.JTI != req.JTI || lease.SessionID != req.SessionID || lease.ByteLimit != 40 ||
			lease.ExpiresAt.Before(before.Add(4*time.Minute)) || lease.ExpiresAt.After(time.Now().Add(6*time.Minute)) {
			t.Fatalf("wrong immutable lease: %+v", lease)
		}
		var xray, committed, held int64
		if err := db.QueryRowContext(ctx,
			`SELECT confirmed_xray_bytes, committed_olcrtc_bytes, held_bytes FROM bridge_budget_cycles
			 WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
			req.Subject.PanelInstance, req.Subject.UserID, evidence.CycleID,
		).Scan(&xray, &committed, &held); err != nil || xray != 60 || committed != 0 || held != 40 {
			t.Fatalf("cycle budget = %d/%d/%d, %v", xray, committed, held, err)
		}
		var meterRows int
		if err := db.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM bridge_meter_sessions WHERE session_id=$1 AND panel_instance=$2 AND user_id=$3`,
			req.SessionID, req.Subject.PanelInstance, req.Subject.UserID,
		).Scan(&meterRows); err != nil || meterRows != 1 {
			t.Fatalf("reserved lease did not create exactly one meter session: %d, %v", meterRows, err)
		}
	})
	for _, tc := range []struct {
		name   string
		change func(t *testing.T, s *Store, req *ReservationRequest, ev *BudgetEvidence, limits *ReservationLimits)
	}{
		{"empty cycle", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.CycleID = ""
		}},
		{"zero cycle start", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.CycleStart = time.Time{}
		}},
		{"future cycle", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.CycleStart = time.Now().Add(time.Minute)
			ev.ObservedAt = ev.CycleStart
		}},
		{"ended cycle", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.CycleEnd = time.Now().Add(-time.Second)
			ev.ObservedAt = ev.CycleEnd.Add(-time.Second)
		}},
		{"swapped trusted subject", func(t *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.Subject = testSubject(t)
		}},
		{"unknown status", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.Status = "UNKNOWN"
		}},
		{"unknown lag", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.MeasuredXrayLag = 0
		}},
		{"lag exceeds bound", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, limits *ReservationLimits) {
			ev.MeasuredXrayLag = limits.MaxXrayLag + time.Second
		}},
		{"unproven Xray enforcement", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.ActiveXrayEnforcementProven = false
		}},
		{"stale observation", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.ObservedAt = time.Now().Add(-2 * time.Minute)
		}},
		{"future observation", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.ObservedAt = time.Now().Add(time.Minute)
		}},
		{"wrong installation", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.InstallationID = "installation-unbound-001"
		}},
		{"invalid country", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.Country = "nl"
		}},
		{"invalid room", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.RoomID = ""
		}},
		{"invalid JTI", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.JTI = "short"
		}},
		{"zero requested bytes", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.RequestedBytes = 0
		}},
		{"disabled flag", func(t *testing.T, s *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			if _, err := s.SetProtocolEnabled(ctx, req.Subject, false); err != nil {
				t.Fatal(err)
			}
		}},
		{"changed revision", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.PolicyRevision++
		}},
		{"expired subscription", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.SubscriptionExpiresAt = time.Now().Add(-time.Second)
		}},
		{"over budget by one", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, _ *ReservationLimits) {
			req.RequestedBytes = 41
		}},
		{"unlimited total", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.TotalLimitBytes = 0
		}},
		{"negative Xray counter", func(_ *testing.T, _ *Store, _ *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.XrayUsedBytes = -1
		}},
		{"absent lease cap", func(_ *testing.T, _ *Store, _ *ReservationRequest, _ *BudgetEvidence, limits *ReservationLimits) {
			limits.MaxLeaseBytes = 0
		}},
		{"absent lease TTL", func(_ *testing.T, _ *Store, _ *ReservationRequest, _ *BudgetEvidence, limits *ReservationLimits) {
			limits.LeaseTTL = 0
		}},
		{"oversized lease", func(_ *testing.T, _ *Store, req *ReservationRequest, _ *BudgetEvidence, limits *ReservationLimits) {
			req.RequestedBytes = limits.MaxLeaseBytes + 1
		}},
		{"counter overflow", func(_ *testing.T, _ *Store, req *ReservationRequest, ev *BudgetEvidence, _ *ReservationLimits) {
			ev.TotalLimitBytes = math.MaxInt64
			ev.XrayUsedBytes = math.MaxInt64
			req.RequestedBytes = 1
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s, db, _, req, evidence, limits := reservationFixture(t)
			tc.change(t, s, &req, &evidence, &limits)
			if _, err := s.ReserveLease(ctx, req, evidence, limits); err == nil ||
				strings.Contains(err.Error(), req.SessionID) || strings.Contains(err.Error(), req.JTI) {
				t.Fatalf("invalid reservation was accepted or leaked identity: %v", err)
			}
			var rows int
			if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM bridge_budget_leases WHERE jti=$1`, req.JTI).Scan(&rows); err != nil || rows != 0 {
				t.Fatalf("denied request created lease row: %d, %v", rows, err)
			}
		})
	}
}

func TestReservationRejectsUnattributedMeterRows(t *testing.T) {
	for _, bytes := range []int64{0, 1} {
		t.Run(fmt.Sprintf("legacy-bytes-%d", bytes), func(t *testing.T) {
			s, db, _, req, evidence, limits := reservationFixture(t)
			ctx := context.Background()
			legacyID := testMeterSessionID(t)
			if err := s.RegisterMeterSession(ctx, req.Subject, legacyID); err != nil {
				t.Fatalf("legacy registration: %v", err)
			}
			if bytes > 0 {
				if err := s.RecordCumulative(ctx, MeterReport{Subject: req.Subject, SessionID: legacyID,
					Sequence: 1, ClientToExitBytes: bytes}); err != nil {
					t.Fatalf("legacy high-water: %v", err)
				}
			}
			if _, err := s.ReserveLease(ctx, req, evidence, limits); err == nil {
				t.Fatal("unattributed meter session was omitted from cycle gate")
			}
			var rows int
			if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2`,
				req.Subject.PanelInstance, req.Subject.UserID).Scan(&rows); err != nil || rows != 0 {
				t.Fatalf("unattributed subject got a cycle: %d, %v", rows, err)
			}
		})
	}
}

func TestReservationDuplicateAndParallelBudget(t *testing.T) {
	ctx := context.Background()
	t.Run("same JTI after reconnect consumes no new headroom", func(t *testing.T) {
		s, db, dsn, req, evidence, limits := reservationFixture(t)
		first, err := s.ReserveLease(ctx, req, evidence, limits)
		if err != nil {
			t.Fatalf("first ReserveLease: %v", err)
		}
		db2, err := sql.Open("pgx", dsn)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = db2.Close() })
		s2, err := New(db2)
		if err != nil {
			t.Fatal(err)
		}
		retry, err := s2.ReserveLease(ctx, req, evidence, limits)
		if err != nil || retry != first {
			t.Fatalf("ambiguous COMMIT retry expanded or lost lease: first=%+v retry=%+v err=%v", first, retry, err)
		}
		var held int64
		var leases, meters int
		if err := db.QueryRowContext(ctx, `SELECT held_bytes FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
			req.Subject.PanelInstance, req.Subject.UserID, evidence.CycleID).Scan(&held); err != nil {
			t.Fatal(err)
		}
		if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM bridge_budget_leases WHERE jti=$1`, req.JTI).Scan(&leases); err != nil {
			t.Fatal(err)
		}
		if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM bridge_meter_sessions WHERE session_id=$1`, req.SessionID).Scan(&meters); err != nil {
			t.Fatal(err)
		}
		if held != 40 || leases != 1 || meters != 1 {
			t.Fatalf("replay changed ledger: held=%d leases=%d meters=%d", held, leases, meters)
		}
		changed := req
		changed.RoomID = "other-test-room"
		if _, err := s2.ReserveLease(ctx, changed, evidence, limits); !errors.Is(err, ErrReservationConflict) {
			t.Fatalf("same JTI with changed room = %v", err)
		}
		changed = nextReservation(req, t)
		changed.SessionID = req.SessionID
		if _, err := s2.ReserveLease(ctx, changed, evidence, limits); !errors.Is(err, ErrReservationConflict) {
			t.Fatalf("same session with new JTI = %v", err)
		}
	})
	t.Run("disabled flag and revision deny same JTI retry", func(t *testing.T) {
		s, _, _, req, evidence, limits := reservationFixture(t)
		if _, err := s.ReserveLease(ctx, req, evidence, limits); err != nil {
			t.Fatal(err)
		}
		if _, err := s.SetProtocolEnabled(ctx, req.Subject, false); err != nil {
			t.Fatal(err)
		}
		if _, err := s.ReserveLease(ctx, req, evidence, limits); !errors.Is(err, ErrReservationDenied) {
			t.Fatalf("disabled subscription replayed JTI: %v", err)
		}
		if _, err := s.SetProtocolEnabled(ctx, req.Subject, true); err != nil {
			t.Fatal(err)
		}
		if _, err := s.ReserveLease(ctx, req, evidence, limits); !errors.Is(err, ErrReservationDenied) {
			t.Fatalf("changed revision replayed old JTI: %v", err)
		}
	})
	t.Run("shortened trusted subscription expiry denies same JTI retry", func(t *testing.T) {
		s, _, _, req, evidence, limits := reservationFixture(t)
		first, err := s.ReserveLease(ctx, req, evidence, limits)
		if err != nil {
			t.Fatal(err)
		}
		evidence.SubscriptionExpiresAt = time.Now().Add(time.Minute)
		if !first.ExpiresAt.After(evidence.SubscriptionExpiresAt) {
			t.Fatalf("fixture did not shorten subscription before lease expiry: lease=%v subscription=%v", first.ExpiresAt, evidence.SubscriptionExpiresAt)
		}
		if _, err := s.ReserveLease(ctx, req, evidence, limits); !errors.Is(err, ErrReservationDenied) {
			t.Fatalf("replay returned lease beyond current subscription expiry: %v", err)
		}
	})
	t.Run("expired or revoked row cannot be revived", func(t *testing.T) {
		for _, column := range []string{"expires_at", "revoked_at"} {
			t.Run(column, func(t *testing.T) {
				s, db, _, req, evidence, limits := reservationFixture(t)
				if _, err := s.ReserveLease(ctx, req, evidence, limits); err != nil {
					t.Fatal(err)
				}
				query := `UPDATE bridge_budget_leases SET ` + column + `=clock_timestamp()-interval '1 second' WHERE jti=$1`
				if _, err := db.ExecContext(ctx, query, req.JTI); err != nil {
					t.Fatal(err)
				}
				if _, err := s.ReserveLease(ctx, req, evidence, limits); !errors.Is(err, ErrReservationDenied) {
					t.Fatalf("expired/revoked lease was revived: %v", err)
				}
			})
		}
	})
	t.Run("parallel installations share one subject cap", func(t *testing.T) {
		s, db, _, first, evidence, limits := reservationFixture(t)
		binding := testBinding(t, 2)
		if err := s.BindInstallation(ctx, first.Subject, binding); err != nil {
			t.Fatal(err)
		}
		evidence.XrayUsedBytes = 0
		first.RequestedBytes = 60
		second := nextReservation(first, t)
		second.InstallationID = binding.ID
		start := make(chan struct{})
		results := make(chan error, 2)
		var workers sync.WaitGroup
		for _, req := range []ReservationRequest{first, second} {
			workers.Add(1)
			go func(req ReservationRequest) {
				defer workers.Done()
				<-start
				_, err := s.ReserveLease(ctx, req, evidence, limits)
				results <- err
			}(req)
		}
		close(start)
		workers.Wait()
		close(results)
		var accepted, denied int
		for err := range results {
			switch {
			case err == nil:
				accepted++
			case errors.Is(err, ErrReservationDenied):
				denied++
			default:
				t.Fatalf("parallel reservation result: %v", err)
			}
		}
		var held int64
		if err := db.QueryRowContext(ctx, `SELECT held_bytes FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
			first.Subject.PanelInstance, first.Subject.UserID, evidence.CycleID).Scan(&held); err != nil {
			t.Fatal(err)
		}
		if accepted != 1 || denied != 1 || held != 60 {
			t.Fatalf("parallel overdraw: accepted=%d denied=%d held=%d", accepted, denied, held)
		}
	})
}

func TestReservationCycleEvidenceRegressionAndRollover(t *testing.T) {
	ctx := context.Background()
	t.Run("distinct overlapping cycle IDs cannot mint a second pool", func(t *testing.T) {
		s, db, _, req, evidence, limits := reservationFixture(t)
		evidence.XrayUsedBytes = 0
		if _, err := s.ReserveLease(ctx, req, evidence, limits); err != nil {
			t.Fatal(err)
		}
		overlap := evidence
		overlap.CycleID += "-early-reset"
		overlap.CycleStart = overlap.CycleStart.Add(30 * time.Second)
		overlap.CycleEnd = overlap.CycleEnd.Add(time.Hour)
		if _, err := s.ReserveLease(ctx, nextReservation(req, t), overlap, limits); !errors.Is(err, ErrReservationEvidence) {
			t.Fatalf("early reset opened another budget pool: %v", err)
		}
		var cycles int
		if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2`,
			req.Subject.PanelInstance, req.Subject.UserID).Scan(&cycles); err != nil || cycles != 1 {
			t.Fatalf("early reset created cycle: %d, %v", cycles, err)
		}
	})
	t.Run("racing distinct overlapping cycle IDs serialize", func(t *testing.T) {
		s, db, _, req, evidence, limits := reservationFixture(t)
		evidence.XrayUsedBytes = 0
		other := nextReservation(req, t)
		otherEvidence := evidence
		otherEvidence.CycleID += "-rival"
		start := make(chan struct{})
		results := make(chan error, 2)
		var workers sync.WaitGroup
		for _, attempt := range []struct {
			req ReservationRequest
			ev  BudgetEvidence
		}{{req, evidence}, {other, otherEvidence}} {
			workers.Add(1)
			go func(req ReservationRequest, ev BudgetEvidence) {
				defer workers.Done()
				<-start
				_, err := s.ReserveLease(ctx, req, ev, limits)
				results <- err
			}(attempt.req, attempt.ev)
		}
		close(start)
		workers.Wait()
		close(results)
		var accepted, denied int
		for err := range results {
			switch {
			case err == nil:
				accepted++
			case errors.Is(err, ErrReservationEvidence):
				denied++
			default:
				t.Fatalf("racing cycle result: %v", err)
			}
		}
		var cycles int
		if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2`,
			req.Subject.PanelInstance, req.Subject.UserID).Scan(&cycles); err != nil || accepted != 1 || denied != 1 || cycles != 1 {
			t.Fatalf("parallel cycle pools: accepted=%d denied=%d cycles=%d err=%v", accepted, denied, cycles, err)
		}
	})
	t.Run("a completed cycle can roll forward without extending old lease", func(t *testing.T) {
		s, _, _, req, evidence, limits := reservationFixture(t)
		evidence.XrayUsedBytes = 0
		evidence.CycleEnd = time.Now().Add(250 * time.Millisecond)
		evidence.SubscriptionExpiresAt = evidence.CycleEnd.Add(time.Hour)
		first, err := s.ReserveLease(ctx, req, evidence, limits)
		if err != nil || first.ExpiresAt.After(evidence.CycleEnd.Add(time.Microsecond)) {
			t.Fatalf("lease outlived first cycle: %+v, %v", first, err)
		}
		time.Sleep(time.Until(evidence.CycleEnd) + 30*time.Millisecond)
		next := evidence
		next.CycleID += "-next"
		next.CycleStart = evidence.CycleEnd
		next.CycleEnd = evidence.CycleEnd.Add(time.Hour)
		next.ObservedAt = time.Now()
		next.SubscriptionExpiresAt = next.CycleEnd
		if _, err := s.ReserveLease(ctx, nextReservation(req, t), next, limits); err != nil {
			t.Fatalf("non-overlapping next cycle was denied: %v", err)
		}
	})
	t.Run("same-cycle boundary and limit drift reject", func(t *testing.T) {
		s, _, _, req, evidence, limits := reservationFixture(t)
		evidence.XrayUsedBytes = 0
		if _, err := s.ReserveLease(ctx, req, evidence, limits); err != nil {
			t.Fatal(err)
		}
		for _, change := range []func(*BudgetEvidence){
			func(ev *BudgetEvidence) { ev.CycleEnd = ev.CycleEnd.Add(time.Second) },
			func(ev *BudgetEvidence) { ev.TotalLimitBytes++ },
		} {
			drift := evidence
			change(&drift)
			if _, err := s.ReserveLease(ctx, nextReservation(req, t), drift, limits); !errors.Is(err, ErrReservationEvidence) {
				t.Fatalf("cycle identity drift accepted: %v", err)
			}
		}
	})
	t.Run("higher Xray sample persists denial and lower sample cannot reopen", func(t *testing.T) {
		s, db, _, req, evidence, limits := reservationFixture(t)
		if _, err := s.ReserveLease(ctx, req, evidence, limits); err != nil {
			t.Fatal(err)
		}
		higher := evidence
		higher.XrayUsedBytes = 65
		higher.ObservedAt = time.Now()
		if _, err := s.ReserveLease(ctx, nextReservation(req, t), higher, limits); !errors.Is(err, ErrReservationDenied) {
			t.Fatalf("overdrawn Xray sample admitted lease: %v", err)
		}
		var xray int64
		var overdrawn bool
		if err := db.QueryRowContext(ctx, `SELECT confirmed_xray_bytes, overdrawn FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
			req.Subject.PanelInstance, req.Subject.UserID, evidence.CycleID).Scan(&xray, &overdrawn); err != nil || xray != 65 || !overdrawn {
			t.Fatalf("denying high-water was not durable: %d/%v, %v", xray, overdrawn, err)
		}
		lower := higher
		lower.XrayUsedBytes = 60
		lower.ObservedAt = time.Now()
		if _, err := s.ReserveLease(ctx, nextReservation(req, t), lower, limits); !errors.Is(err, ErrReservationEvidence) {
			t.Fatalf("regressed Xray counter reopened budget: %v", err)
		}
	})
	t.Run("checked sum overflow marks cycle overdrawn", func(t *testing.T) {
		s, db, _, req, evidence, limits := reservationFixture(t)
		req.RequestedBytes = 1
		evidence.TotalLimitBytes = math.MaxInt64
		evidence.XrayUsedBytes = math.MaxInt64 - 1
		if _, err := s.ReserveLease(ctx, req, evidence, limits); err != nil {
			t.Fatal(err)
		}
		higher := evidence
		higher.XrayUsedBytes = math.MaxInt64
		higher.ObservedAt = time.Now()
		if _, err := s.ReserveLease(ctx, nextReservation(req, t), higher, limits); !errors.Is(err, ErrReservationDenied) {
			t.Fatalf("overflow admitted lease: %v", err)
		}
		var xray int64
		var overdrawn bool
		if err := db.QueryRowContext(ctx, `SELECT confirmed_xray_bytes, overdrawn FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2 AND cycle_id=$3`,
			req.Subject.PanelInstance, req.Subject.UserID, evidence.CycleID).Scan(&xray, &overdrawn); err != nil || xray != math.MaxInt64 || !overdrawn {
			t.Fatalf("overflow lost Xray high-water or marker: %d/%v, %v", xray, overdrawn, err)
		}
	})
}

func TestReservationFlagRevisionRaceFailsAfterDisable(t *testing.T) {
	s, db, _, req, evidence, limits := reservationFixture(t)
	evidence.XrayUsedBytes = 0
	req.RequestedBytes = 20
	ctx := context.Background()
	start := make(chan struct{})
	type result struct {
		revision int64
		err      error
	}
	disabled := make(chan result, 1)
	var workers sync.WaitGroup
	for i := 0; i < 2; i++ {
		attempt := nextReservation(req, t)
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			_, _ = s.ReserveLease(ctx, attempt, evidence, limits)
		}()
	}
	go func() {
		<-start
		state, err := s.SetProtocolEnabled(ctx, req.Subject, false)
		disabled <- result{state.Revision, err}
	}()
	close(start)
	changed := <-disabled
	if changed.err != nil {
		t.Fatal(changed.err)
	}
	workers.Wait()
	postDisable := nextReservation(req, t)
	postDisable.PolicyRevision = changed.revision
	if _, err := s.ReserveLease(ctx, postDisable, evidence, limits); !errors.Is(err, ErrReservationDenied) {
		t.Fatalf("new revision committed disabled but reservation succeeded: %v", err)
	}
	var held int64
	if err := db.QueryRowContext(ctx, `SELECT COALESCE(SUM(held_bytes),0) FROM bridge_budget_cycles WHERE panel_instance=$1 AND user_id=$2`,
		req.Subject.PanelInstance, req.Subject.UserID).Scan(&held); err != nil || held > 40 {
		t.Fatalf("flag race over-reserved budget: held=%d, %v", held, err)
	}
}

func TestReservationEvidenceSubjectMismatchPrecedesDatabaseAccess(t *testing.T) {
	s, db, _, req, evidence, limits := reservationFixture(t)
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	evidence.Subject = testSubject(t)
	if _, err := s.ReserveLease(context.Background(), req, evidence, limits); !errors.Is(err, ErrReservationEvidence) {
		t.Fatalf("swapped trusted subject reached database: %v", err)
	}
}
