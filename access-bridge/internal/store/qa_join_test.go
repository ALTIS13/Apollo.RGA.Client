package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

func qaJoinFixture(t *testing.T) (*Store, QAJoinRequest) {
	t.Helper()
	s, subject, installation, session := qaFixture(t)
	state, err := s.SetProtocolEnabled(context.Background(), subject, true)
	if err != nil {
		t.Fatal(err)
	}
	var fingerprint []byte
	if err := s.db.QueryRowContext(context.Background(),
		`SELECT public_key_sha256 FROM bridge_installations WHERE installation_id=$1`, installation,
	).Scan(&fingerprint); err != nil {
		t.Fatal(err)
	}
	var keyHash [32]byte
	copy(keyHash[:], fingerprint)
	var nonce [32]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	req := QAJoinRequest{
		Subject: subject, InstallationID: installation, PublicKeySHA256: keyHash,
		RoomID: "test-room-000001", Country: "NL", SessionID: session,
		Nonce: nonce, PolicyRevision: state.Revision,
		ObservedAt: time.Now(), MaxSnapshotAge: 15 * time.Second,
		SubscriptionExpiresAt: time.Now().Add(time.Hour),
		Deadline:              time.Now().Add(2 * time.Minute),
	}
	return s, qaJoinWithProofDeadline(t, req, time.Now().Add(3*time.Minute))
}

// Keep the regression executable against the pre-fix DTO: its decoder ignores
// this future field, so RED reaches the real atomic gate rather than failing
// compilation. The fixed DTO receives the same immutable timestamp.
func qaJoinWithProofDeadline(t *testing.T, req QAJoinRequest, end time.Time) QAJoinRequest {
	t.Helper()
	encoded, err := json.Marshal(map[string]time.Time{"ProofDeadline": end})
	if err != nil || json.Unmarshal(encoded, &req) != nil {
		t.Fatal("could not construct proof-deadline regression input")
	}
	return req
}

func TestQAJoinProofAgesOutWhileWaitingForSubjectLock(t *testing.T) {
	s, req := qaJoinFixture(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	guard, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Rollback()
	var exists int
	if err := guard.QueryRowContext(ctx, `SELECT 1 FROM bridge_subjects
		WHERE panel_instance=$1 AND user_id=$2 FOR UPDATE`,
		req.Subject.PanelInstance, req.Subject.UserID).Scan(&exists); err != nil {
		t.Fatal(err)
	}
	var dbNow time.Time
	if err := s.db.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
		t.Fatal(err)
	}
	// Panel facts and lease remain valid; only the original proof expires.
	req.ObservedAt = dbNow
	proofEnd := dbNow.Add(500 * time.Millisecond)
	req = qaJoinWithProofDeadline(t, req, proofEnd)
	result := make(chan error, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		result <- s.RedeemQAJoin(ctx, req)
	}()
	t.Cleanup(func() {
		_ = guard.Rollback()
		cancel()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Error("proof-boundary transaction did not join")
		}
	})
	waitForRenewLockWaits(t, s, 1, "%bridge_subjects%")
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := s.db.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
			t.Fatal(err)
		}
		if dbNow.After(proofEnd) {
			break
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			t.Fatal("proof deadline did not elapse within the bounded fixture")
		}
	}
	if err := guard.Rollback(); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-result:
	case <-ctx.Done():
		t.Fatal("proof-boundary transaction did not return")
	}
	if !errors.Is(err, ErrQAJoinDenied) {
		t.Fatalf("PERSONAL_AGED_PROOF: expired proof consumed at atomic redemption: %v", err)
	}
	nonceHash := sha256.Sum256(req.Nonce[:])
	var sessions, uses int
	if err := s.db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM bridge_qa_sessions WHERE session_id=$1),
		(SELECT count(*) FROM bridge_qa_join_uses WHERE nonce_sha256=$2)`,
		req.SessionID, nonceHash[:]).Scan(&sessions, &uses); err != nil {
		t.Fatal(err)
	}
	if sessions != 0 || uses != 0 {
		t.Fatalf("PERSONAL_AGED_PROOF: denied proof left session=%d nonce=%d", sessions, uses)
	}
}

func TestQAJoinProofAgesOutWhileWaitingForNonceInsert(t *testing.T) {
	s, req := qaJoinFixture(t)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	guard, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer guard.Rollback()
	if _, err := guard.ExecContext(ctx, `LOCK TABLE bridge_qa_join_uses IN SHARE MODE`); err != nil {
		t.Fatal(err)
	}
	var dbNow time.Time
	if err := s.db.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
		t.Fatal(err)
	}
	req.ObservedAt = dbNow
	proofEnd := dbNow.Add(500 * time.Millisecond)
	req = qaJoinWithProofDeadline(t, req, proofEnd)
	result := make(chan error, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		result <- s.RedeemQAJoin(ctx, req)
	}()
	t.Cleanup(func() {
		_ = guard.Rollback()
		cancel()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Error("nonce-insert proof-boundary transaction did not join")
		}
	})
	// Reaching this INSERT proves the initial post-subject-lock clock gate
	// accepted the still-fresh proof; the table lock delays its final write.
	waitForRenewLockWaits(t, s, 1, "%INSERT INTO bridge_qa_join_uses%")
	ticker := time.NewTicker(10 * time.Millisecond)
	defer ticker.Stop()
	for {
		if err := s.db.QueryRowContext(ctx, `SELECT clock_timestamp()`).Scan(&dbNow); err != nil {
			t.Fatal(err)
		}
		if dbNow.After(proofEnd) {
			break
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			t.Fatal("proof deadline did not elapse within the bounded nonce-insert fixture")
		}
	}
	if err := guard.Rollback(); err != nil {
		t.Fatal(err)
	}
	select {
	case err = <-result:
	case <-ctx.Done():
		t.Fatal("nonce-insert proof-boundary transaction did not return")
	}
	if !errors.Is(err, ErrQAJoinDenied) {
		t.Errorf("PERSONAL_AGED_PROOF_COMMIT: proof aged during nonce INSERT but was accepted: %v", err)
	}
	nonceHash := sha256.Sum256(req.Nonce[:])
	var sessions, uses int
	if err := s.db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM bridge_qa_sessions WHERE session_id=$1),
		(SELECT count(*) FROM bridge_qa_join_uses WHERE nonce_sha256=$2)`,
		req.SessionID, nonceHash[:]).Scan(&sessions, &uses); err != nil {
		t.Fatal(err)
	}
	if sessions != 0 || uses != 0 {
		t.Fatalf("PERSONAL_AGED_PROOF_COMMIT: expired proof left session=%d nonce=%d", sessions, uses)
	}
}

func TestQAJoinMissingProofDeadlineCannotConsumeNonce(t *testing.T) {
	s, req := qaJoinFixture(t)
	req = qaJoinWithProofDeadline(t, req, time.Time{})
	if err := s.RedeemQAJoin(context.Background(), req); !errors.Is(err, ErrQAJoinInvalid) {
		t.Fatalf("PERSONAL_AGED_PROOF: missing immutable deadline accepted: %v", err)
	}
}

func TestQAJoinConsumesNonceAndRegistersOneSessionAtomically(t *testing.T) {
	s, req := qaJoinFixture(t)
	ctx := context.Background()
	if err := s.RedeemQAJoin(ctx, req); err != nil {
		t.Fatalf("first join: %v", err)
	}
	row, err := s.LoadQASession(ctx, req.Subject, req.InstallationID, req.SessionID)
	if err != nil || row.Status != QAActive || !row.Deadline.Equal(req.Deadline.UTC().Truncate(time.Microsecond)) {
		t.Fatalf("no registered session: %+v, %v", row, err)
	}
	otherSession := req
	otherSession.SessionID = fmt.Sprintf("qa-session-%d", time.Now().UnixNano())
	if err := s.RedeemQAJoin(ctx, otherSession); !errors.Is(err, ErrQAJoinReplay) {
		t.Fatalf("reused nonce accepted: %v", err)
	}
	if _, err := s.LoadQASession(ctx, req.Subject, req.InstallationID, otherSession.SessionID); !errors.Is(err, ErrQASessionUnknown) {
		t.Fatalf("replayed nonce left a session row: %v", err)
	}
	otherNonce := req
	if _, err := rand.Read(otherNonce.Nonce[:]); err != nil {
		t.Fatal(err)
	}
	if err := s.RedeemQAJoin(ctx, otherNonce); !errors.Is(err, ErrQAJoinReplay) {
		t.Fatalf("reused session ID accepted: %v", err)
	}
	var rawNonceMatches int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_qa_join_uses WHERE nonce_sha256=$1`, req.Nonce[:]).Scan(&rawNonceMatches); err != nil {
		t.Fatal(err)
	}
	if rawNonceMatches != 0 {
		t.Fatal("raw nonce persisted instead of its hash")
	}
}

func TestQAJoinDeniesWrongBindingDisabledFlagAndRevision(t *testing.T) {
	s, req := qaJoinFixture(t)
	ctx := context.Background()
	bad := req
	bad.InstallationID = "another-installation-000001"
	if err := s.RedeemQAJoin(ctx, bad); !errors.Is(err, ErrQAJoinDenied) {
		t.Fatalf("unbound installation accepted: %v", err)
	}
	bad = req
	bad.PolicyRevision--
	if err := s.RedeemQAJoin(ctx, bad); !errors.Is(err, ErrQAJoinDenied) {
		t.Fatalf("stale policy revision accepted: %v", err)
	}
	bad = req
	bad.ObservedAt = time.Now().Add(-time.Minute)
	if err := s.RedeemQAJoin(ctx, bad); !errors.Is(err, ErrQAJoinDenied) {
		t.Fatalf("stale panel observation accepted: %v", err)
	}
	bad = req
	bad.SubscriptionExpiresAt = time.Now().Add(time.Minute)
	if err := s.RedeemQAJoin(ctx, bad); !errors.Is(err, ErrQAJoinDenied) {
		t.Fatalf("lease beyond subscription expiry accepted: %v", err)
	}
	if _, err := s.SetProtocolEnabled(ctx, req.Subject, false); err != nil {
		t.Fatal(err)
	}
	if err := s.RedeemQAJoin(ctx, req); !errors.Is(err, ErrQAJoinDenied) {
		t.Fatalf("disabled protocol accepted: %v", err)
	}
	var count int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_qa_sessions WHERE session_id=$1`, req.SessionID).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("denied join left a session row")
	}
}

func TestQAJoinConcurrentOneNonceHasOneWinner(t *testing.T) {
	s, req := qaJoinFixture(t)
	ctx := context.Background()
	var wg sync.WaitGroup
	start := make(chan struct{})
	results := make(chan error, 2)
	for i := 0; i < 2; i++ {
		copyReq := req
		copyReq.SessionID = fmt.Sprintf("%s-%d", req.SessionID, i)
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			results <- s.RedeemQAJoin(ctx, copyReq)
		}()
	}
	close(start)
	wg.Wait()
	close(results)
	var accepted, replayed int
	for err := range results {
		switch {
		case err == nil:
			accepted++
		case errors.Is(err, ErrQAJoinReplay):
			replayed++
		default:
			t.Fatalf("unexpected concurrent result: %v", err)
		}
	}
	if accepted != 1 || replayed != 1 {
		t.Fatalf("accepted=%d replayed=%d", accepted, replayed)
	}
}

func TestQAJoinInvalidInputFailsBeforeDatabase(t *testing.T) {
	s := &Store{}
	err := s.RedeemQAJoin(context.Background(), QAJoinRequest{
		Subject:        access.Subject{PanelInstance: "rga-main", UserID: 42},
		InstallationID: "test_installation_A063_01", SessionID: "qa-session-000001",
		RoomID: "test-room-000001", Country: "NL", PolicyRevision: 2,
		Deadline: time.Now().Add(time.Minute),
	})
	if !errors.Is(err, ErrQAJoinInvalid) {
		t.Fatalf("zero nonce reached database: %v", err)
	}
}
