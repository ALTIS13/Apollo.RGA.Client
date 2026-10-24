package access

import (
	"errors"
	"regexp"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/policy"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

var (
	ErrPersonalTestDenied = errors.New("access: personal test facts denied")
	personalRoomPattern   = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)
)

// PersonalTestConfig is server-owned and exact to one linked installation and
// one existing room. It is never populated from a client request.
type PersonalTestConfig struct {
	Subject        Subject
	Installation   InstallationBinding
	RoomID         string
	Country        string
	LeaseTTL       time.Duration
	MaxSnapshotAge time.Duration
	SessionByteCap int64
}

// PersonalTestFacts must come from the Bridge store and a fresh canonical
// Remnawave LookupByID. Neither a client claim nor a cached subscription URL
// is a valid producer of these facts.
type PersonalTestFacts struct {
	State             SubjectState
	BoundInstallation InstallationBinding
	User              remnawave.UserSnapshot
	ObservedAt        time.Time
}

// PersonalTestPlan is an input to a future one-use grant issuer, NOT a grant.
// It cannot open the OlcRTC server without independent possession proof,
// replay storage and the server's managed lease hook.
type PersonalTestPlan struct {
	Subject        Subject
	Installation   InstallationBinding
	RoomID         string
	Country        string
	PolicyRevision int64
	Deadline       time.Time
	ByteCap        int64
}

func PlanPersonalTestSession(now time.Time, config PersonalTestConfig, facts PersonalTestFacts) (PersonalTestPlan, error) {
	if now.IsZero() || config.Subject.Validate() != nil || config.Installation.Validate() != nil ||
		!personalRoomPattern.MatchString(config.RoomID) || len(config.Country) != 2 ||
		config.Country[0] < 'A' || config.Country[0] > 'Z' ||
		config.Country[1] < 'A' || config.Country[1] > 'Z' ||
		config.LeaseTTL <= 0 || config.LeaseTTL > 5*time.Minute ||
		config.MaxSnapshotAge <= 0 || config.MaxSnapshotAge > 30*time.Second ||
		config.SessionByteCap <= 0 || config.SessionByteCap > 1<<40 {
		return PersonalTestPlan{}, ErrPersonalTestDenied
	}
	if facts.State.Subject != config.Subject || !facts.State.OlcRTCEnabled || facts.State.Revision <= 0 ||
		facts.BoundInstallation != config.Installation || facts.User.ID != config.Subject.UserID ||
		facts.ObservedAt.IsZero() || facts.ObservedAt.After(now) || now.Sub(facts.ObservedAt) > config.MaxSnapshotAge ||
		!policy.Evaluate(now, facts.User, policy.ProtocolFlag{Enabled: true}).OrdinaryAllowed {
		return PersonalTestPlan{}, ErrPersonalTestDenied
	}
	deadline := now.Add(config.LeaseTTL)
	if facts.User.ExpiresAt.Before(deadline) {
		deadline = facts.User.ExpiresAt
	}
	if !deadline.After(now) {
		return PersonalTestPlan{}, ErrPersonalTestDenied
	}
	return PersonalTestPlan{
		Subject: config.Subject, Installation: config.Installation,
		RoomID: config.RoomID, Country: config.Country,
		PolicyRevision: facts.State.Revision, Deadline: deadline,
		ByteCap: config.SessionByteCap,
	}, nil
}
