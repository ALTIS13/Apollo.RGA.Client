package personal

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"net"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
	_ "github.com/jackc/pgx/v5/stdlib"
)

type fakePersonalLeaseIssuer struct {
	admit      SessionLease
	admitErr   error
	renew      SessionLease
	renewErr   error
	admitCalls int
	renewCalls int
}

func (f *fakePersonalLeaseIssuer) Admit(context.Context, map[string]any) (SessionLease, error) {
	f.admitCalls++
	return f.admit, f.admitErr
}

func (f *fakePersonalLeaseIssuer) Renew(context.Context, string) (SessionLease, error) {
	f.renewCalls++
	return f.renew, f.renewErr
}

type fakePersonalJournal struct {
	reportCalls       int
	report            store.QAMeterReport
	reportErr         error
	closeID           string
	closeFinal        *store.QAFinal
	closeErr          error
	closeStatus       store.QAStatus
	closeCalls        int
	closeSubject      access.Subject
	closeInstallation string
}

func (f *fakePersonalJournal) RecordQACumulative(_ context.Context, report store.QAMeterReport) error {
	f.reportCalls++
	f.report = report
	return f.reportErr
}

func (f *fakePersonalJournal) CloseQASession(_ context.Context, subject access.Subject, installation string, sessionID string, final *store.QAFinal) (store.QASession, error) {
	f.closeCalls++
	f.closeSubject, f.closeInstallation = subject, installation
	f.closeID = sessionID
	f.closeFinal = final
	status := f.closeStatus
	if status == "" && final != nil && f.closeErr == nil {
		status = store.QAClosed
	}
	return store.QASession{Status: status}, f.closeErr
}

type fakePersonalNodeControl struct {
	renewCalls int
	renewID    string
	renewTo    time.Time
	renewOK    bool
	closedID   string
}

func (f *fakePersonalNodeControl) RenewLease(id string, deadline time.Time) bool {
	f.renewCalls++
	f.renewID, f.renewTo = id, deadline
	return f.renewOK
}

func (f *fakePersonalNodeControl) CloseSession(id string) bool {
	f.closedID = id
	return true
}

func nodeHooksFixture(t *testing.T) (*NodeHooks, *fakePersonalLeaseIssuer, *fakePersonalJournal, *fakePersonalNodeControl) {
	t.Helper()
	var fingerprint [32]byte
	fingerprint[0] = 1
	issuer := &fakePersonalLeaseIssuer{}
	journal := &fakePersonalJournal{}
	node := &fakePersonalNodeControl{renewOK: true}
	hooks, err := NewNodeHooks(
		access.Subject{PanelInstance: "rga-main", UserID: 42},
		access.InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: fingerprint},
		issuer, journal, node,
	)
	if err != nil {
		t.Fatal(err)
	}
	return hooks, issuer, journal, node
}

func TestNodeHooksRejectsTypedNilDependencies(t *testing.T) {
	var fingerprint [32]byte
	fingerprint[0] = 1
	subject := access.Subject{PanelInstance: "rga-main", UserID: 42}
	binding := access.InstallationBinding{ID: "test_installation_A063_01", PublicKeySHA256: fingerprint}
	var nilIssuer *fakePersonalLeaseIssuer
	if _, err := NewNodeHooks(subject, binding, nilIssuer, &fakePersonalJournal{}, &fakePersonalNodeControl{}); !errors.Is(err, ErrNodeConfig) {
		t.Fatalf("typed-nil issuer accepted: %v", err)
	}
	var nilJournal *fakePersonalJournal
	if _, err := NewNodeHooks(subject, binding, &fakePersonalLeaseIssuer{}, nilJournal, &fakePersonalNodeControl{}); !errors.Is(err, ErrNodeConfig) {
		t.Fatalf("typed-nil journal accepted: %v", err)
	}
	var nilNode *fakePersonalNodeControl
	if _, err := NewNodeHooks(subject, binding, &fakePersonalLeaseIssuer{}, &fakePersonalJournal{}, nilNode); !errors.Is(err, ErrNodeConfig) {
		t.Fatalf("typed-nil node accepted: %v", err)
	}
}

func TestNodeHooksAdmitReturnsOnlyServerLease(t *testing.T) {
	hooks, issuer, _, _ := nodeHooksFixture(t)
	deadline := time.Now().Add(2 * time.Minute)
	issuer.admit = SessionLease{SessionID: "session-opaque-000001", Deadline: deadline, ByteCap: 1 << 30}
	claims := map[string]any{"signed": "proof"}
	lease, err := hooks.Admit(context.Background(), "untrusted-client-device-id", claims)
	if err != nil || lease.SessionID != issuer.admit.SessionID || !lease.NotAfter.Equal(deadline) || lease.ByteLimit != issuer.admit.ByteCap {
		t.Fatalf("admission result = %+v, %v", lease, err)
	}
	if issuer.admitCalls != 1 {
		t.Fatalf("admit calls = %d", issuer.admitCalls)
	}
	issuer.admitErr = ErrDenied
	if _, err := hooks.Admit(context.Background(), "anything", claims); !errors.Is(err, ErrNodeDenied) {
		t.Fatalf("denied personal admission = %v", err)
	}
}

func TestNodeHooksReporterAcknowledgesOnlyDurableExactSnapshot(t *testing.T) {
	hooks, _, journal, _ := nodeHooksFixture(t)
	report := NodeCumulativeReport{SessionID: "session-opaque-000001", Sequence: 3, ClientToExitBytes: 123, ExitToClientBytes: 456}
	journal.reportErr = store.ErrUnavailable
	if ack, err := hooks.Report(context.Background(), report); err == nil || ack != (NodeCumulativeReport{}) {
		t.Fatalf("unconfirmed reporter write acknowledged: %+v, %v", ack, err)
	}
	journal.reportErr = nil
	ack, err := hooks.Report(context.Background(), report)
	if err != nil || ack != report {
		t.Fatalf("durable reporter acknowledgement = %+v, %v", ack, err)
	}
	if journal.report.Subject.UserID != 42 || journal.report.InstallationID != "test_installation_A063_01" ||
		journal.report.ClientToExitBytes != 123 || journal.report.ExitToClientBytes != 456 {
		t.Fatalf("report was not scoped to bound installation: %+v", journal.report)
	}
}

func TestNodeHooksTerminalReportRequiresDurableJournalClose(t *testing.T) {
	hooks, _, journal, _ := nodeHooksFixture(t)
	report := NodeCumulativeReport{
		SessionID: "session-opaque-000001", Sequence: 3,
		ClientToExitBytes: 123, ExitToClientBytes: 456, Terminal: true,
	}
	journal.closeErr = store.ErrUnavailable
	if ack, err := hooks.Report(context.Background(), report); err == nil || ack != (NodeCumulativeReport{}) {
		t.Fatalf("terminal close failure acknowledged: %+v, %v", ack, err)
	}
	journal.closeErr = nil
	ack, err := hooks.Report(context.Background(), report)
	if err != nil || ack != report || journal.closeID != report.SessionID || journal.closeFinal == nil ||
		journal.closeFinal.ClientToExitBytes != 123 || journal.closeFinal.ExitToClientBytes != 456 {
		t.Fatalf("durable terminal acknowledgement = %+v, %v, final=%+v", ack, err, journal.closeFinal)
	}
}

func TestNodeHooksTerminalReportDoesNotAckUnconfirmedClose(t *testing.T) {
	hooks, _, journal, _ := nodeHooksFixture(t)
	journal.closeStatus = store.QAUnconfirmed
	report := NodeCumulativeReport{
		SessionID: "session-opaque-000001", Sequence: 3,
		ClientToExitBytes: 123, ExitToClientBytes: 456, Terminal: true,
	}
	if ack, err := hooks.Report(context.Background(), report); err == nil || ack != (NodeCumulativeReport{}) {
		t.Fatalf("unconfirmed terminal row was acknowledged: %+v, %v", ack, err)
	}
}

func TestNodeHooksRenewUsesDurableDecisionBeforeNativeExtension(t *testing.T) {
	hooks, issuer, _, node := nodeHooksFixture(t)
	id := "session-opaque-000001"
	deadline := time.Now().Add(3 * time.Minute)
	issuer.renew = SessionLease{SessionID: id, Deadline: deadline, ByteCap: 1 << 30}
	issuer.renewErr = ErrUnavailable
	if err := hooks.Renew(context.Background(), id); !errors.Is(err, ErrNodeUnavailable) || node.renewCalls != 0 || node.closedID != "" {
		t.Fatalf("transient renewal changed the active native lease: err=%v calls=%d closed=%q", err, node.renewCalls, node.closedID)
	}
	issuer.renewErr = ErrDenied
	if err := hooks.Renew(context.Background(), id); !errors.Is(err, ErrNodeDenied) || node.renewCalls != 0 || node.closedID != id {
		t.Fatalf("definitive denial failed to close active native lease: err=%v calls=%d closed=%q", err, node.renewCalls, node.closedID)
	}
	issuer.renewErr = nil
	node.closedID = ""
	if err := hooks.Renew(context.Background(), id); err != nil || node.renewCalls != 1 || node.renewID != id || !node.renewTo.Equal(deadline) || node.closedID != "" {
		t.Fatalf("valid renewal was not applied: err=%v native=%+v", err, node)
	}
	node.renewOK = false
	if err := hooks.Renew(context.Background(), id); err == nil || node.closedID != id {
		t.Fatalf("native refusal left session open: err=%v closed=%q", err, node.closedID)
	}
}

func TestNodeHooksCloseLeavesUnknownTailUnconfirmed(t *testing.T) {
	hooks, _, journal, _ := nodeHooksFixture(t)
	id := "session-opaque-000001"
	if err := hooks.Close(context.Background(), id); err != nil || journal.closeID != id {
		t.Fatalf("close = %v, journal id=%q", err, journal.closeID)
	}
	journal.closeErr = store.ErrUnavailable
	if err := hooks.Close(context.Background(), id); err == nil {
		t.Fatal("ambiguous journal close reported success")
	}
}

func TestNodeHooksObserveFinalUsesExactOwnedJournal(t *testing.T) {
	// Break caught: copying client ownership, narrowing int64 totals, or using Report to manufacture ACK.
	hooks, _, journal, _ := nodeHooksFixture(t)
	journal.closeStatus = store.QAUnconfirmed
	final := NodeFinalTotals{SessionID: "session-opaque-000001", ClientToExitBytes: math.MaxInt64, ExitToClientBytes: 7}
	if err := hooks.ObserveFinal(context.Background(), final); err != nil {
		t.Fatalf("unconfirmed durable observation should be accepted: %v", err)
	}
	if journal.closeCalls != 1 || journal.closeSubject != hooks.subject || journal.closeInstallation != "test_installation_A063_01" ||
		journal.closeID != "session-opaque-000001" || journal.closeFinal == nil ||
		journal.closeFinal.ClientToExitBytes != math.MaxInt64 || journal.closeFinal.ExitToClientBytes != 7 || journal.reportCalls != 0 {
		t.Fatal("final observation lost exact bound journal mapping or manufactured report ACK")
	}
	final.ClientToExitBytes = 1
	if journal.closeFinal.ClientToExitBytes != math.MaxInt64 {
		t.Fatal("observer retained mutable caller totals")
	}
}

func TestNodeHooksObserveFinalRejectsInvalidBeforeJournal(t *testing.T) {
	// Break caught: malformed/cancelled observation reaches the durable journal.
	for _, tc := range []struct {
		name  string
		final NodeFinalTotals
	}{
		{"short session", NodeFinalTotals{SessionID: "short"}},
		{"unknown character", NodeFinalTotals{SessionID: "session/private/000001"}},
		{"negative upload", NodeFinalTotals{SessionID: "session-opaque-000001", ClientToExitBytes: -1}},
		{"negative download", NodeFinalTotals{SessionID: "session-opaque-000001", ExitToClientBytes: -1}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			hooks, _, journal, _ := nodeHooksFixture(t)
			if err := hooks.ObserveFinal(context.Background(), tc.final); !errors.Is(err, ErrNodeDenied) || journal.closeCalls != 0 {
				t.Fatalf("invalid final reached journal: err=%v calls=%d", err, journal.closeCalls)
			}
		})
	}
	hooks, _, journal, _ := nodeHooksFixture(t)
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	for _, ctx := range []context.Context{nil, cancelled} {
		if err := hooks.ObserveFinal(ctx, NodeFinalTotals{SessionID: "session-opaque-000001"}); !errors.Is(err, ErrNodeUnavailable) || journal.closeCalls != 0 {
			t.Fatalf("invalid context observation: err=%v calls=%d", err, journal.closeCalls)
		}
	}
}

func TestNodeHooksObserveFinalMissingJournalAndOutageFailClosed(t *testing.T) {
	// Break caught: nil dependency panic or raw database error crossing the adapter boundary.
	final := NodeFinalTotals{SessionID: "session-opaque-000001"}
	var absent *NodeHooks
	var nilJournal *fakePersonalJournal
	for _, hooks := range []*NodeHooks{absent, {}, {journal: nilJournal}} {
		if err := hooks.ObserveFinal(context.Background(), final); !errors.Is(err, ErrNodeUnavailable) {
			t.Fatalf("unavailable hook accepted: %v", err)
		}
	}
	hooks, _, journal, _ := nodeHooksFixture(t)
	journal.closeErr = errors.New("private database detail")
	if err := hooks.ObserveFinal(context.Background(), final); err != ErrNodeUnavailable {
		t.Fatalf("raw journal error escaped final observer: %v", err)
	}
	journal.closeErr = nil
	if err := hooks.ObserveFinal(context.Background(), final); err != nil || journal.closeCalls != 2 || journal.reportCalls != 0 {
		t.Fatal("identical final retry did not use journal-only observation")
	}
}

func TestNodeHooksObserveFinalDoesNotReplaceUnknownClose(t *testing.T) {
	// Break caught: final observer changes Close(nil) fallback semantics.
	hooks, _, journal, _ := nodeHooksFixture(t)
	final := NodeFinalTotals{SessionID: "session-opaque-000001", ClientToExitBytes: 12, ExitToClientBytes: 7}
	if err := hooks.ObserveFinal(context.Background(), final); err != nil {
		t.Fatal(err)
	}
	if err := hooks.Close(context.Background(), final.SessionID); err != nil {
		t.Fatal(err)
	}
	if journal.closeFinal != nil || journal.closeCalls != 2 || journal.reportCalls != 0 {
		t.Fatal("fallback Close no longer supplied unknown nil final")
	}
}

var nodeFinalFixtureNumber atomic.Uint64

// This fixture is deliberately real Store composition, not a fake ACK oracle.
// The coordinator alone supplies the dedicated local test DSN and executes it.
func nodeHooksFinalStoreFixture(t *testing.T) (*NodeHooks, *store.Store, context.Context, string) {
	t.Helper()
	dsn := os.Getenv("APOLLO_RGA_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("dedicated PostgreSQL test database is not configured; durable final evidence pending")
	}
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		t.Fatal("could not open dedicated test database")
	}
	t.Cleanup(func() { _ = db.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	t.Cleanup(cancel)
	var database, role, address string
	var port int
	if err := db.QueryRowContext(ctx,
		"SELECT current_database(), current_user, host(inet_server_addr()), inet_server_port()",
	).Scan(&database, &role, &address, &port); err != nil {
		t.Fatal("could not verify dedicated test database")
	}
	ip := net.ParseIP(address)
	if database != "apollo_rga_bridge_test" || role != "bridge_test" || port <= 0 || ip == nil || !ip.IsLoopback() {
		t.Fatal("refusing writes outside dedicated local test database and role")
	}
	journal, err := store.New(db)
	if err != nil {
		t.Fatal("could not create dedicated test journal")
	}
	if err := journal.Migrate(ctx); err != nil {
		t.Fatal("could not migrate dedicated test journal")
	}
	n := nodeFinalFixtureNumber.Add(1)
	unique := fmt.Sprintf("%d-%d", time.Now().UnixNano(), n)
	subject := access.Subject{PanelInstance: "node-final-" + unique, UserID: 42}
	binding := access.InstallationBinding{ID: "node-install-" + unique, PublicKeySHA256: [32]byte{67}}
	id := "node-session-" + unique
	if _, err := journal.EnsureSubject(ctx, subject); err != nil {
		t.Fatal(err)
	}
	if err := journal.BindInstallation(ctx, subject, binding); err != nil {
		t.Fatal(err)
	}
	if err := journal.SetQAJournalingEnabled(ctx, subject, binding.ID, true); err != nil {
		t.Fatal(err)
	}
	if err := journal.RegisterQASession(ctx, subject, binding.ID, id, time.Now().Add(2*time.Minute)); err != nil {
		t.Fatal(err)
	}
	hooks, err := NewNodeHooks(subject, binding, &fakePersonalLeaseIssuer{}, journal, &fakePersonalNodeControl{})
	if err != nil {
		t.Fatal(err)
	}
	return hooks, journal, ctx, id
}

func TestNodeHooksFinalObservationAheadOfAckIsUnconfirmed(t *testing.T) {
	// Break caught: observer fabricates durable ACK/Closed or lifecycle nil-close erases known final.
	for _, unknownFirst := range []bool{false, true} {
		t.Run(fmt.Sprintf("unknown_close_first_%t", unknownFirst), func(t *testing.T) {
			hooks, journal, ctx, id := nodeHooksFinalStoreFixture(t)
			first := NodeCumulativeReport{SessionID: id, Sequence: 1, ClientToExitBytes: 10, ExitToClientBytes: 5}
			if ack, err := hooks.Report(ctx, first); err != nil || ack != first {
				t.Fatalf("initial durable report: ack=%+v err=%v", ack, err)
			}
			if unknownFirst {
				if err := hooks.Close(ctx, id); err != nil {
					t.Fatal(err)
				}
			}
			final := NodeFinalTotals{SessionID: id, ClientToExitBytes: 12, ExitToClientBytes: 7}
			if err := hooks.ObserveFinal(ctx, final); err != nil {
				t.Fatal(err)
			}
			if !unknownFirst {
				// An unknown close cannot replace an already-known final; lifecycle ignores this fixed refusal.
				if err := hooks.Close(ctx, id); err != ErrNodeUnavailable {
					t.Fatalf("known-final nil-close = %v, want fixed refusal", err)
				}
			}
			row, err := journal.LoadQASession(ctx, hooks.subject, hooks.installation.ID, id)
			if err != nil || row.Status != store.QAUnconfirmed || row.LastSequence != 1 ||
				row.ClientToExitBytes != 10 || row.ExitToClientBytes != 5 || row.Terminal == nil ||
				*row.Terminal != (store.QAFinal{ClientToExitBytes: 12, ExitToClientBytes: 7}) {
				t.Fatalf("observation falsely confirmed or lost exact final: %+v, %v", row, err)
			}
			late := NodeCumulativeReport{SessionID: id, Sequence: 2, ClientToExitBytes: 12, ExitToClientBytes: 7, Terminal: true}
			for attempt := 0; attempt < 2; attempt++ {
				if ack, err := hooks.Report(ctx, late); err != nil || ack != late {
					t.Fatalf("late exact durable ACK/replay: %+v, %v", ack, err)
				}
			}
			row, err = journal.LoadQASession(ctx, hooks.subject, hooks.installation.ID, id)
			if err != nil || row.Status != store.QAClosed || row.LastSequence != 2 ||
				row.ClientToExitBytes != 12 || row.ExitToClientBytes != 7 {
				t.Fatalf("late exact report did not close without duplication: %+v, %v", row, err)
			}
		})
	}
}
