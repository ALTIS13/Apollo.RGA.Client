// Package accounting contains read-only, unqualified panel observations.
package accounting

import (
	"context"
	"errors"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

var (
	ErrInvalidConfig      = errors.New("accounting: invalid configuration")
	ErrInvalidInput       = errors.New("accounting: invalid input")
	ErrVersionMismatch    = errors.New("accounting: panel version mismatch")
	ErrInvalidObservation = errors.New("accounting: invalid observation")
)

// Qualification deliberately expresses missing evidence, never permission.
type Qualification struct{ State, Reason string }

// Observation is immutable by value. Snapshot returns defensive pointer copies.
// Expiry/status and reset markers are raw facts, not cycle identifiers or ends.
type Observation struct {
	subject               access.Subject
	snapshot              remnawave.UserSnapshot
	panelVersion          string
	startedAt, observedAt time.Time
}

func (o Observation) Subject() access.Subject          { return o.subject }
func (o Observation) Snapshot() remnawave.UserSnapshot { return copySnapshot(o.snapshot) }
func (o Observation) PanelVersion() string             { return o.panelVersion }
func (o Observation) StartedAt() time.Time             { return o.startedAt }
func (o Observation) ObservedAt() time.Time            { return o.observedAt }
func (Observation) Qualification() Qualification {
	return Qualification{State: "UNQUALIFIED", Reason: "cycle, usage lag, and active enforcement not verified"}
}

// ObservationProvider owns its configured panel identity, exact version pin,
// and clock. Observe accepts only the canonical numeric user ID.
type ObservationProvider struct {
	client                         *remnawave.Client
	panelInstance, expectedVersion string
	now                            func() time.Time
}

func NewObservationProvider(client *remnawave.Client, panelInstance, expectedVersion string, now func() time.Time) (*ObservationProvider, error) {
	if client == nil || (access.Subject{PanelInstance: panelInstance, UserID: 1}).Validate() != nil || remnawave.ValidateVersion(expectedVersion) != nil {
		return nil, ErrInvalidConfig
	}
	if now == nil {
		now = time.Now
	}
	return &ObservationProvider{client: client, panelInstance: panelInstance, expectedVersion: expectedVersion, now: now}, nil
}

func (p *ObservationProvider) Observe(ctx context.Context, id int64) (Observation, error) {
	if id <= 0 {
		return Observation{}, ErrInvalidInput
	}
	if err := ctx.Err(); err != nil {
		return Observation{}, err
	}
	if p == nil || p.client == nil || p.now == nil {
		return Observation{}, ErrInvalidConfig
	}
	started := p.now()
	version, err := p.client.MetadataVersion(ctx)
	if err != nil {
		return Observation{}, redactCancellation(ctx, err)
	}
	if err := ctx.Err(); err != nil {
		return Observation{}, err
	}
	if version != p.expectedVersion {
		return Observation{}, ErrVersionMismatch
	}
	snapshot, err := p.client.LookupByID(ctx, id)
	if err != nil {
		return Observation{}, redactCancellation(ctx, err)
	}
	if err := ctx.Err(); err != nil {
		return Observation{}, err
	}
	observed := p.now()
	if observed.Before(started) || snapshot.CreatedAt == nil || !snapshot.LastTrafficResetAtPresent || snapshot.CreatedAt.After(observed) {
		return Observation{}, ErrInvalidObservation
	}
	if reset := snapshot.LastTrafficResetAt; reset != nil && (reset.Before(*snapshot.CreatedAt) || reset.After(observed)) {
		return Observation{}, ErrInvalidObservation
	}
	if err := ctx.Err(); err != nil {
		return Observation{}, err
	}
	return Observation{subject: access.Subject{PanelInstance: p.panelInstance, UserID: snapshot.ID}, snapshot: copySnapshot(snapshot), panelVersion: version, startedAt: started, observedAt: observed}, nil
}

func redactCancellation(ctx context.Context, err error) error {
	if canceled := ctx.Err(); canceled != nil {
		return canceled
	}
	return err // Concrete client returns only fixed, redacted errors.
}

func copySnapshot(snapshot remnawave.UserSnapshot) remnawave.UserSnapshot {
	if value := snapshot.CreatedAt; value != nil {
		copy := *value
		snapshot.CreatedAt = &copy
	}
	if value := snapshot.LastTrafficResetAt; value != nil {
		copy := *value
		snapshot.LastTrafficResetAt = &copy
	}
	if value := snapshot.HWIDDeviceLimit; value != nil {
		copy := *value
		snapshot.HWIDDeviceLimit = &copy
	}
	return snapshot
}
