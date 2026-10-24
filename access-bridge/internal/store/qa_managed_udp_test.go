package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

func managedUDPRegisteredFixture(t *testing.T) (*Store, access.Subject, string, string) {
	t.Helper()
	s, subject, installation, id := qaFixture(t)
	if err := s.RegisterQASession(context.Background(), subject, installation, id, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	return s, subject, installation, id
}

func TestQAManagedUDPFinalObservationAndAmbiguousAck(t *testing.T) {
	// Break caught: final observation advances confirmed counters, or nil close erases known final.
	for _, unknownFirst := range []bool{false, true} {
		name := "final_then_unknown_close"
		if unknownFirst {
			name = "unknown_close_then_final"
		}
		t.Run(name, func(t *testing.T) {
			s, subject, installation, id := managedUDPRegisteredFixture(t)
			ctx := context.Background()
			first := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id,
				Sequence: 1, ClientToExitBytes: 10, ExitToClientBytes: 5}
			if err := s.RecordQACumulative(ctx, first); err != nil {
				t.Fatal(err)
			}
			if unknownFirst {
				if _, err := s.CloseQASession(ctx, subject, installation, id, nil); err != nil {
					t.Fatal(err)
				}
			}
			final := &QAFinal{ClientToExitBytes: 12, ExitToClientBytes: 7}
			if _, err := s.CloseQASession(ctx, subject, installation, id, final); err != nil {
				t.Fatal(err)
			}
			if !unknownFirst {
				if _, err := s.CloseQASession(ctx, subject, installation, id, nil); !errors.Is(err, ErrQAConflict) {
					t.Fatalf("unknown close replaced known final: %v", err)
				}
			}
			row, err := s.LoadQASession(ctx, subject, installation, id)
			if err != nil || row.Status != QAUnconfirmed || row.LastSequence != 1 || row.ClientToExitBytes != 10 ||
				row.ExitToClientBytes != 5 || row.Terminal == nil || *row.Terminal != *final {
				t.Fatalf("ahead-of-ACK observation: %+v, %v", row, err)
			}
			late := first
			late.Sequence, late.ClientToExitBytes, late.ExitToClientBytes = 2, 12, 7
			if err := s.RecordQACumulative(ctx, late); err != nil {
				t.Fatal(err)
			}
			if row, err := s.CloseQASession(ctx, subject, installation, id, final); err != nil || row.Status != QAClosed ||
				row.LastSequence != 2 || row.ClientToExitBytes != 12 || row.ExitToClientBytes != 7 {
				t.Fatalf("late exact final confirmation: %+v, %v", row, err)
			}
		})
	}
}

func TestQAManagedUDPLostAckReplayIsDurableAndDoesNotDuplicateBytes(t *testing.T) {
	// Break caught: retry after a real committed write adds counters or accepts conflicting/stale snapshots.
	s, subject, installation, id := managedUDPRegisteredFixture(t)
	ctx := context.Background()
	final := &QAFinal{ClientToExitBytes: 12, ExitToClientBytes: 7}
	if _, err := s.CloseQASession(ctx, subject, installation, id, final); err != nil {
		t.Fatal(err)
	}
	report := QAMeterReport{Subject: subject, InstallationID: installation, SessionID: id,
		Sequence: 2, ClientToExitBytes: 12, ExitToClientBytes: 7}
	commitThenLoseAck := func() error {
		if err := s.RecordQACumulative(ctx, report); err != nil {
			return err
		}
		return ErrUnavailable // deterministic transport loss AFTER actual durable Store commit
	}
	if err := commitThenLoseAck(); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("lost ACK simulation: %v", err)
	}
	db2, _ := testDatabase(t)
	s2, err := New(db2)
	if err != nil {
		t.Fatal(err)
	}
	if err := s2.RecordQACumulative(ctx, report); err != nil {
		t.Fatalf("identical replay through fresh Store handle: %v", err)
	}
	conflicting := report
	conflicting.ClientToExitBytes++
	if err := s2.RecordQACumulative(ctx, conflicting); !errors.Is(err, ErrMeterConflict) {
		t.Fatalf("conflicting same-sequence replay: %v", err)
	}
	stale := report
	stale.Sequence--
	if err := s2.RecordQACumulative(ctx, stale); !errors.Is(err, ErrMeterStale) {
		t.Fatalf("stale late report: %v", err)
	}
	row, err := s2.LoadQASession(ctx, subject, installation, id)
	if err != nil || row.Status != QAClosed || row.LastSequence != 2 || row.ClientToExitBytes != 12 || row.ExitToClientBytes != 7 ||
		row.Terminal == nil || *row.Terminal != *final {
		t.Fatalf("lost ACK/replay duplicated or changed durable counters: %+v, %v", row, err)
	}
}

func TestQAManagedUDPConflictingFinalCannotReplaceKnownObservation(t *testing.T) {
	// Break caught: retry with different immutable final silently overwrites the first observation.
	s, subject, installation, id := managedUDPRegisteredFixture(t)
	ctx := context.Background()
	final := &QAFinal{ClientToExitBytes: 12, ExitToClientBytes: 7}
	if _, err := s.CloseQASession(ctx, subject, installation, id, final); err != nil {
		t.Fatal(err)
	}
	for _, conflicting := range []*QAFinal{{ClientToExitBytes: 13, ExitToClientBytes: 7}, {ClientToExitBytes: 12, ExitToClientBytes: 8}} {
		if _, err := s.CloseQASession(ctx, subject, installation, id, conflicting); !errors.Is(err, ErrQAConflict) {
			t.Fatalf("conflicting final accepted: %v", err)
		}
	}
	row, err := s.CloseQASession(ctx, subject, installation, id, final)
	if err != nil || row.Status != QAUnconfirmed || row.LastSequence != 0 || row.ClientToExitBytes != 0 || row.ExitToClientBytes != 0 ||
		row.Terminal == nil || *row.Terminal != *final {
		t.Fatalf("original observation was replaced or acknowledged: %+v, %v", row, err)
	}
}

func TestQAManagedUDPFinalAndLateReportRemainExactOwnerInstallationSessionScoped(t *testing.T) {
	// Break caught: observer/reporter names an existing neighbor under the wrong ownership tuple.
	s, subject, installation, id := managedUDPRegisteredFixture(t)
	ctx := context.Background()
	otherSubject := testSubject(t)
	otherBinding := testBinding(t, 68)
	if _, err := s.EnsureSubject(ctx, otherSubject); err != nil {
		t.Fatal(err)
	}
	if err := s.BindInstallation(ctx, otherSubject, otherBinding); err != nil {
		t.Fatal(err)
	}
	if err := s.SetQAJournalingEnabled(ctx, otherSubject, otherBinding.ID, true); err != nil {
		t.Fatal(err)
	}
	neighborID := id + "-neighbor"
	if err := s.RegisterQASession(ctx, otherSubject, otherBinding.ID, neighborID, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	for _, wrong := range []struct {
		subject               access.Subject
		installation, session string
	}{
		{otherSubject, installation, id},
		{subject, otherBinding.ID, id},
		{subject, installation, neighborID},
	} {
		if _, err := s.CloseQASession(ctx, wrong.subject, wrong.installation, wrong.session,
			&QAFinal{ClientToExitBytes: 12, ExitToClientBytes: 7}); !errors.Is(err, ErrQASessionUnknown) {
			t.Fatalf("wrong-owner final = %v", err)
		}
		if err := s.RecordQACumulative(ctx, QAMeterReport{Subject: wrong.subject, InstallationID: wrong.installation,
			SessionID: wrong.session, Sequence: 1, ClientToExitBytes: 12, ExitToClientBytes: 7}); !errors.Is(err, ErrQASessionUnknown) {
			t.Fatalf("wrong-owner late report = %v", err)
		}
	}
	for _, exact := range []struct {
		subject               access.Subject
		installation, session string
	}{{subject, installation, id}, {otherSubject, otherBinding.ID, neighborID}} {
		row, err := s.LoadQASession(ctx, exact.subject, exact.installation, exact.session)
		if err != nil || row.Status != QAActive || row.LastSequence != 0 || row.ClientToExitBytes != 0 || row.ExitToClientBytes != 0 || row.Terminal != nil {
			t.Fatalf("wrong-owner callback touched neighboring row: %+v, %v", row, err)
		}
	}
}
