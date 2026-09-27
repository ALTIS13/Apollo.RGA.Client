package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	_ "github.com/jackc/pgx/v5/stdlib"
)

var caseNumber atomic.Uint64

func testDatabase(t *testing.T) (*sql.DB, string) {
	t.Helper()
	dsn := os.Getenv("APOLLO_RGA_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("dedicated PostgreSQL test database is not configured")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal("could not open dedicated test database")
	}
	t.Cleanup(func() { _ = db.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var name, role, address string
	var port int
	if err := db.QueryRowContext(ctx,
		"SELECT current_database(), current_user, host(inet_server_addr()), inet_server_port()",
	).Scan(&name, &role, &address, &port); err != nil {
		t.Fatal("could not verify dedicated test database")
	}
	serverIP := net.ParseIP(address)
	if name != "apollo_rga_bridge_test" || role != "bridge_test" || port <= 0 || serverIP == nil || !serverIP.IsLoopback() {
		t.Fatal("refusing schema writes outside dedicated local test database and role")
	}
	return db, dsn
}

func testSubject(t *testing.T) access.Subject {
	t.Helper()
	n := caseNumber.Add(1)
	return access.Subject{
		PanelInstance: fmt.Sprintf("test-%d-%d", time.Now().UnixNano(), n),
		UserID:        42,
	}
}

func testBinding(t *testing.T, fingerprint byte) access.InstallationBinding {
	t.Helper()
	return access.InstallationBinding{
		ID:              fmt.Sprintf("installation-%d-%d", time.Now().UnixNano(), caseNumber.Add(1)),
		PublicKeySHA256: [32]byte{fingerprint},
	}
}

func migratedStore(t *testing.T) (*Store, *sql.DB, string) {
	t.Helper()
	db, dsn := testDatabase(t)
	s, err := New(db)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := s.Migrate(context.Background()); err != nil {
		t.Fatalf("Migrate: %v", err)
	}
	return s, db, dsn
}

func TestMigrationIsIdempotentAndSubjectsAreIsolated(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	if err := s.Migrate(ctx); err != nil {
		t.Fatalf("second migration: %v", err)
	}
	a := testSubject(t)
	b := testSubject(t)
	for _, subject := range []access.Subject{a, b} {
		state, err := s.EnsureSubject(ctx, subject)
		if err != nil {
			t.Fatalf("EnsureSubject: %v", err)
		}
		if state.Subject != subject || state.OlcRTCEnabled || state.Revision != 1 {
			t.Fatalf("new subject is not isolated and default-off: %+v", state)
		}
	}
	changed, err := s.SetProtocolEnabled(ctx, a, true)
	if err != nil || !changed.OlcRTCEnabled || changed.Revision != 2 {
		t.Fatalf("first subject update: %+v, %v", changed, err)
	}
	other, err := s.Load(ctx, b)
	if err != nil || other.OlcRTCEnabled || other.Revision != 1 {
		t.Fatalf("same numeric ID on another panel changed: %+v, %v", other, err)
	}
	again, err := s.EnsureSubject(ctx, a)
	if err != nil || !again.OlcRTCEnabled || again.Revision != 2 {
		t.Fatalf("EnsureSubject reset existing state: %+v, %v", again, err)
	}
}

func TestStateSurvivesNewConnectionAndMissingSubjectFailsClosed(t *testing.T) {
	s, _, dsn := migratedStore(t)
	ctx := context.Background()
	subject := testSubject(t)
	if _, err := s.Load(ctx, subject); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing subject: %v", err)
	}
	if _, err := s.SetProtocolEnabled(ctx, subject, true); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing subject was implicitly enabled: %v", err)
	}
	if _, err := s.EnsureSubject(ctx, subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	if _, err := s.SetProtocolEnabled(ctx, subject, true); err != nil {
		t.Fatalf("SetProtocolEnabled: %v", err)
	}
	db2, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal("could not open second test connection")
	}
	t.Cleanup(func() { _ = db2.Close() })
	s2, err := New(db2)
	if err != nil {
		t.Fatalf("New after reconnect: %v", err)
	}
	state, err := s2.Load(ctx, subject)
	if err != nil || !state.OlcRTCEnabled || state.Revision != 2 {
		t.Fatalf("state did not survive reconnect: %+v, %v", state, err)
	}
}

func TestInstallationBindingIsIdempotentAndCannotMove(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	a, b := testSubject(t), testSubject(t)
	for _, subject := range []access.Subject{a, b} {
		if _, err := s.EnsureSubject(ctx, subject); err != nil {
			t.Fatalf("EnsureSubject: %v", err)
		}
	}
	binding := testBinding(t, 1)
	if err := s.BindInstallation(ctx, a, binding); err != nil {
		t.Fatalf("first bind: %v", err)
	}
	if err := s.BindInstallation(ctx, a, binding); err != nil {
		t.Fatalf("idempotent bind: %v", err)
	}
	if err := s.BindInstallation(ctx, b, binding); !errors.Is(err, ErrInstallationConflict) {
		t.Fatalf("cross-subject rebind was allowed: %v", err)
	}
	changedKey := binding
	changedKey.PublicKeySHA256[0] = 2
	if err := s.BindInstallation(ctx, a, changedKey); !errors.Is(err, ErrInstallationConflict) {
		t.Fatalf("key replacement was allowed: %v", err)
	}
}

func TestConcurrentInstallationBindHasOneOwner(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	a, b := testSubject(t), testSubject(t)
	for _, subject := range []access.Subject{a, b} {
		if _, err := s.EnsureSubject(ctx, subject); err != nil {
			t.Fatalf("EnsureSubject: %v", err)
		}
	}
	binding := testBinding(t, 1)
	start := make(chan struct{})
	var wg sync.WaitGroup
	results := make(chan error, 2)
	for _, subject := range []access.Subject{a, b} {
		wg.Add(1)
		go func(subject access.Subject) {
			defer wg.Done()
			<-start
			results <- s.BindInstallation(ctx, subject, binding)
		}(subject)
	}
	close(start)
	wg.Wait()
	close(results)
	var successes, conflicts int
	for err := range results {
		switch {
		case err == nil:
			successes++
		case errors.Is(err, ErrInstallationConflict):
			conflicts++
		default:
			t.Fatalf("unexpected concurrent bind error: %v", err)
		}
	}
	if successes != 1 || conflicts != 1 {
		t.Fatalf("concurrent bind allowed multiple owners: successes=%d conflicts=%d", successes, conflicts)
	}
}

func TestSwitchRevisionChangesOnlyWithPolicy(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	subject := testSubject(t)
	if _, err := s.EnsureSubject(ctx, subject); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	state, err := s.SetProtocolEnabled(ctx, subject, false)
	if err != nil || state.Revision != 1 {
		t.Fatalf("repeat disabled changed revision: %+v, %v", state, err)
	}
	const workers = 20
	start := make(chan struct{})
	results := make(chan error, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			got, err := s.SetProtocolEnabled(ctx, subject, true)
			if err == nil && (!got.OlcRTCEnabled || got.Revision != 2) {
				err = errors.New("concurrent revision was not stable")
			}
			results <- err
		}()
	}
	close(start)
	wg.Wait()
	close(results)
	for err := range results {
		if err != nil {
			t.Fatalf("concurrent switch update: %v", err)
		}
	}
	state, err = s.SetProtocolEnabled(ctx, subject, false)
	if err != nil || state.OlcRTCEnabled || state.Revision != 3 {
		t.Fatalf("switch off did not advance revision: %+v, %v", state, err)
	}
}

func TestInvalidInputsPrecedeDatabaseAccessAndOutageIsRedacted(t *testing.T) {
	db, _ := testDatabase(t)
	s, err := New(db)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatal("could not close test connection")
	}
	ctx := context.Background()
	invalid := access.Subject{PanelInstance: "https://secret.example.invalid", UserID: 42}
	if _, err := s.EnsureSubject(ctx, invalid); !errors.Is(err, access.ErrInvalidSubject) {
		t.Fatalf("invalid subject did not fail before SQL: %v", err)
	}
	if err := s.BindInstallation(ctx, invalid, testBinding(t, 1)); !errors.Is(err, access.ErrInvalidSubject) {
		t.Fatalf("invalid binding subject did not fail before SQL: %v", err)
	}
	valid := testSubject(t)
	if err := s.BindInstallation(ctx, valid, access.InstallationBinding{ID: "invalid"}); !errors.Is(err, access.ErrInvalidInstallation) {
		t.Fatalf("invalid binding did not fail before SQL: %v", err)
	}
	for _, operation := range []func() error{
		func() error { return s.Migrate(ctx) },
		func() error { _, err := s.EnsureSubject(ctx, valid); return err },
		func() error { _, err := s.Load(ctx, valid); return err },
		func() error { return s.BindInstallation(ctx, valid, testBinding(t, 1)) },
		func() error { _, err := s.SetProtocolEnabled(ctx, valid, true); return err },
	} {
		err := operation()
		if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), "secret") {
			t.Fatalf("database outage was not redacted: %v", err)
		}
	}
}

func testChallenge(t *testing.T) access.LinkChallenge {
	t.Helper()
	challenge, err := access.NewLinkChallenge(testSubject(t), testBinding(t, 1))
	if err != nil {
		t.Fatalf("NewLinkChallenge: %v", err)
	}
	return challenge
}

func TestChallengeMigrationCreateAndReadUsesDatabaseExpiry(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	if err := s.Migrate(ctx); err != nil {
		t.Fatalf("second migration: %v", err)
	}
	challenge := testChallenge(t)
	before := time.Now()
	created, err := s.CreateChallenge(ctx, challenge)
	if err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	if !created.ExpiresAt.After(before.Add(4*time.Minute)) || created.ExpiresAt.After(time.Now().Add(6*time.Minute)) {
		t.Fatal("challenge expiry was not set from a bounded database lease")
	}
	loaded, err := s.LoadChallenge(ctx, challenge.ID)
	if err != nil || loaded != created {
		t.Fatalf("loaded challenge differs: %+v, %v", loaded, err)
	}
	if _, err := s.CreateChallenge(ctx, challenge); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("duplicate challenge ID accepted: %v", err)
	}
}

func TestChallengeExpiryDeniesLoadAndConsumeWithoutCreatingSubject(t *testing.T) {
	s, db, _ := migratedStore(t)
	ctx := context.Background()
	challenge := testChallenge(t)
	if _, err := s.CreateChallenge(ctx, challenge); err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	if _, err := db.ExecContext(ctx,
		`UPDATE bridge_link_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE challenge_id=$1`,
		challenge.ID); err != nil {
		t.Fatal("could not expire dedicated test challenge")
	}
	if _, err := s.LoadChallenge(ctx, challenge.ID); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("expired challenge loaded: %v", err)
	}
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour)); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("expired challenge consumed: %v", err)
	}
	if _, err := s.Load(ctx, challenge.Subject); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired challenge created subject: %v", err)
	}
}

func TestChallengeConsumeIsSingleUseAndLeavesSubjectDefaultOff(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	challenge := testChallenge(t)
	if _, err := s.CreateChallenge(ctx, challenge); err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("first consume: %v", err)
	}
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour)); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("replay accepted: %v", err)
	}
	if _, err := s.LoadChallenge(ctx, challenge.ID); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("consumed challenge loaded: %v", err)
	}
	state, err := s.Load(ctx, challenge.Subject)
	if err != nil || state.OlcRTCEnabled || state.Revision != 1 {
		t.Fatalf("binding enabled OlcRTC or changed revision: %+v, %v", state, err)
	}
}

func TestChallengeContextMismatchDoesNotConsumeOrCreateAnotherSubject(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	challenge := testChallenge(t)
	if _, err := s.CreateChallenge(ctx, challenge); err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	wrongSubject := testSubject(t)
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, wrongSubject, challenge.Installation, time.Now().Add(time.Hour)); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("other subject consumed challenge: %v", err)
	}
	wrongKey := challenge.Installation
	wrongKey.PublicKeySHA256[0] ^= 1
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, wrongKey, time.Now().Add(time.Hour)); !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("other key consumed challenge: %v", err)
	}
	if _, err := s.Load(ctx, wrongSubject); !errors.Is(err, ErrNotFound) {
		t.Fatalf("mismatch created another subject: %v", err)
	}
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("correct context was burned by mismatch: %v", err)
	}
}

func TestConcurrentChallengeCompletionAllowsExactlyOne(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	challenge := testChallenge(t)
	if _, err := s.CreateChallenge(ctx, challenge); err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	start := make(chan struct{})
	results := make(chan error, 2)
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			results <- s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour))
		}()
	}
	close(start)
	wg.Wait()
	close(results)
	var successes, rejected int
	for err := range results {
		switch {
		case err == nil:
			successes++
		case errors.Is(err, ErrChallengeInvalid):
			rejected++
		default:
			t.Fatalf("unexpected completion result: %v", err)
		}
	}
	if successes != 1 || rejected != 1 {
		t.Fatalf("replay race: success=%d rejected=%d", successes, rejected)
	}
}

func TestBindingConflictRollsBackNewSubjectAndChallengeConsumption(t *testing.T) {
	s, _, _ := migratedStore(t)
	ctx := context.Background()
	challenge := testChallenge(t)
	previous := testSubject(t)
	if _, err := s.EnsureSubject(ctx, previous); err != nil {
		t.Fatalf("EnsureSubject: %v", err)
	}
	if err := s.BindInstallation(ctx, previous, challenge.Installation); err != nil {
		t.Fatalf("prior bind: %v", err)
	}
	if _, err := s.CreateChallenge(ctx, challenge); err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour)); !errors.Is(err, ErrInstallationConflict) {
		t.Fatalf("conflicting installation reassigned: %v", err)
	}
	if _, err := s.Load(ctx, challenge.Subject); !errors.Is(err, ErrNotFound) {
		t.Fatalf("failed consume left a subject: %v", err)
	}
	loaded, err := s.LoadChallenge(ctx, challenge.ID)
	if err != nil || loaded.Subject != challenge.Subject || loaded.Installation != challenge.Installation {
		t.Fatalf("failed consume corrupted challenge: %+v, %v", loaded, err)
	}
}

func TestChallengeDatabaseOutageIsRedacted(t *testing.T) {
	db, _ := testDatabase(t)
	s, err := New(db)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	challenge := testChallenge(t)
	if err := db.Close(); err != nil {
		t.Fatal("could not close dedicated test connection")
	}
	ctx := context.Background()
	for _, operation := range []func() error{
		func() error { _, err := s.CreateChallenge(ctx, challenge); return err },
		func() error { _, err := s.LoadChallenge(ctx, challenge.ID); return err },
		func() error {
			return s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, time.Now().Add(time.Hour))
		},
	} {
		err := operation()
		if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), challenge.ID) {
			t.Fatalf("challenge outage leaked details: %v", err)
		}
	}
}

func TestSubscriptionExpiryDuringChallengeLockRollsBackBinding(t *testing.T) {
	s, db, _ := migratedStore(t)
	ctx := context.Background()
	challenge := testChallenge(t)
	if _, err := s.CreateChallenge(ctx, challenge); err != nil {
		t.Fatalf("CreateChallenge: %v", err)
	}
	lock, err := db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal("could not open dedicated test lock")
	}
	defer lock.Rollback()
	var held int
	if err := lock.QueryRowContext(ctx,
		`SELECT 1 FROM bridge_link_challenges WHERE challenge_id=$1 FOR UPDATE`, challenge.ID).Scan(&held); err != nil {
		t.Fatal("could not lock dedicated test challenge")
	}
	subscriptionExpiry := time.Now().Add(500 * time.Millisecond)
	results := make(chan error, 1)
	go func() {
		results <- s.ConsumeChallengeAndBind(ctx, challenge.ID, challenge.Subject, challenge.Installation, subscriptionExpiry)
	}()
	time.Sleep(750 * time.Millisecond)
	if err := lock.Commit(); err != nil {
		t.Fatal("could not release dedicated test lock")
	}
	if err := <-results; !errors.Is(err, ErrChallengeInvalid) {
		t.Fatalf("subscription expired while locked but binding succeeded: %v", err)
	}
	if _, err := s.Load(ctx, challenge.Subject); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired binding left a subject: %v", err)
	}
	if _, err := s.LoadChallenge(ctx, challenge.ID); err != nil {
		t.Fatalf("failed binding consumed a still-valid challenge: %v", err)
	}
}
