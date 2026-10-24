package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

func TestPersonalBootstrapStateRequiresExactEnabledBoundInstallation(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	binding := access.InstallationBinding{ID: join.InstallationID, PublicKeySHA256: join.PublicKeySHA256}
	state, err := s.LoadPersonalBootstrapState(ctx, join.Subject, binding)
	if err != nil || state.Subject != join.Subject || !state.OlcRTCEnabled || state.Revision != join.PolicyRevision {
		t.Fatalf("exact bound installation denied: %+v %v", state, err)
	}
	for _, tc := range []struct {
		name    string
		subject access.Subject
		binding access.InstallationBinding
	}{
		{"wrong user", access.Subject{PanelInstance: join.Subject.PanelInstance, UserID: join.Subject.UserID + 1}, binding},
		{"wrong panel", access.Subject{PanelInstance: "another-panel", UserID: join.Subject.UserID}, binding},
		{"wrong installation", join.Subject, access.InstallationBinding{ID: "other-installation-000001", PublicKeySHA256: binding.PublicKeySHA256}},
		{"wrong key", join.Subject, access.InstallationBinding{ID: binding.ID, PublicKeySHA256: [32]byte{9}}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, err := s.LoadPersonalBootstrapState(ctx, tc.subject, tc.binding)
			if !errors.Is(err, ErrPersonalBootstrapDenied) || got != (access.SubjectState{}) {
				t.Fatalf("foreign binding received state: %+v %v", got, err)
			}
		})
	}
	if _, err := s.SetProtocolEnabled(ctx, join.Subject, false); err != nil {
		t.Fatal(err)
	}
	if got, err := s.LoadPersonalBootstrapState(ctx, join.Subject, binding); !errors.Is(err, ErrPersonalBootstrapDenied) || got != (access.SubjectState{}) {
		t.Fatalf("disabled subject received state: %+v %v", got, err)
	}
	if _, err := s.SetProtocolEnabled(ctx, join.Subject, true); err != nil {
		t.Fatal(err)
	}
	if err := s.SetQAJournalingEnabled(ctx, join.Subject, join.InstallationID, false); err != nil {
		t.Fatal(err)
	}
	if got, err := s.LoadPersonalBootstrapState(ctx, join.Subject, binding); !errors.Is(err, ErrPersonalBootstrapDenied) || got != (access.SubjectState{}) {
		t.Fatalf("disabled QA installation received state: %+v %v", got, err)
	}
}

func TestPersonalBootstrapStateSurvivesNewChallengeForSameIdentityWithoutDuplicateBinding(t *testing.T) {
	s, join := qaJoinFixture(t)
	ctx := context.Background()
	binding := access.InstallationBinding{ID: join.InstallationID, PublicKeySHA256: join.PublicKeySHA256}
	for i := 0; i < 2; i++ {
		challenge, err := access.NewLinkChallenge(join.Subject, binding)
		if err != nil {
			t.Fatal(err)
		}
		challenge, err = s.CreateChallenge(ctx, challenge)
		if err != nil {
			t.Fatal(err)
		}
		if err := s.ConsumeChallengeAndBind(ctx, challenge.ID, join.Subject, binding, time.Now().Add(time.Hour)); err != nil {
			t.Fatalf("new challenge %d could not refresh same identity: %v", i, err)
		}
		if _, err := s.LoadPersonalBootstrapState(ctx, join.Subject, binding); err != nil {
			t.Fatalf("new challenge %d lost bound bootstrap: %v", i, err)
		}
	}
	var bindings int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM bridge_installations
		WHERE panel_instance=$1 AND user_id=$2 AND installation_id=$3`,
		join.Subject.PanelInstance, join.Subject.UserID, join.InstallationID).Scan(&bindings); err != nil {
		t.Fatal(err)
	}
	if bindings != 1 {
		t.Fatalf("repeat prefetch created %d identities", bindings)
	}
}

func TestPersonalBootstrapStateReturnsNoStateOnDatabaseOutage(t *testing.T) {
	s, join := qaJoinFixture(t)
	if err := s.db.Close(); err != nil {
		t.Fatal(err)
	}
	binding := access.InstallationBinding{ID: join.InstallationID, PublicKeySHA256: join.PublicKeySHA256}
	got, err := s.LoadPersonalBootstrapState(context.Background(), join.Subject, binding)
	if !errors.Is(err, ErrUnavailable) || got != (access.SubjectState{}) {
		t.Fatalf("outage returned state: %+v %v", got, err)
	}
}
