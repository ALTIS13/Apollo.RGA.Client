package personal

import (
	"context"
	"errors"
	"regexp"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

var (
	ErrNodeConfig      = errors.New("personal: invalid node hook configuration")
	ErrNodeDenied      = errors.New("personal: node operation denied")
	ErrNodeUnavailable = errors.New("personal: node operation unavailable")
	nodeSessionID      = regexp.MustCompile(`^[A-Za-z0-9_-]{16,128}$`)
)

// NodeLease and NodeCumulativeReport mirror only the data needed by the
// embeddable managed OlcRTC callbacks. An adapter in the executable converts
// these to the native library's types; no client input is trusted as a lease.
type NodeLease struct {
	SessionID string
	NotAfter  time.Time
	ByteLimit int64
}

type NodeCumulativeReport struct {
	SessionID         string
	Sequence          int64
	ClientToExitBytes int64
	ExitToClientBytes int64
	Terminal          bool
}

type NodeFinalTotals struct {
	SessionID         string
	ClientToExitBytes int64
	ExitToClientBytes int64
}

type personalLeaseIssuer interface {
	Admit(context.Context, map[string]any) (SessionLease, error)
	Renew(context.Context, string) (SessionLease, error)
}

type personalDiagnosticJournal interface {
	RecordQACumulative(context.Context, store.QAMeterReport) error
	CloseQASession(context.Context, access.Subject, string, string, *store.QAFinal) (store.QASession, error)
}

// personalNativeControl must be the exact running managed server instance.
// An arbitrary client or ordinary OlcRTC CLI cannot supply this authority.
type personalNativeControl interface {
	RenewLease(string, time.Time) bool
	CloseSession(string) bool
}

// NodeHooks has one immutable server-owned subject and installation. It never
// touches the shared Remnawave quota; the journal is diagnostic only.
type NodeHooks struct {
	subject      access.Subject
	installation access.InstallationBinding
	issuer       personalLeaseIssuer
	journal      personalDiagnosticJournal
	node         personalNativeControl
}

func NewNodeHooks(subject access.Subject, installation access.InstallationBinding,
	issuer personalLeaseIssuer, journal personalDiagnosticJournal, node personalNativeControl,
) (*NodeHooks, error) {
	if subject.Validate() != nil || installation.Validate() != nil ||
		nilDependency(issuer) || nilDependency(journal) || nilDependency(node) {
		return nil, ErrNodeConfig
	}
	return &NodeHooks{subject: subject, installation: installation, issuer: issuer, journal: journal, node: node}, nil
}

// Admit is called only from ManagedLeaseAuthHook. The device ID is deliberately
// ignored: the signed proof and Bridge binding, not a client-chosen label,
// authorize a personal session.
func (h *NodeHooks) Admit(ctx context.Context, _ string, claims map[string]any) (NodeLease, error) {
	if h == nil || ctx == nil || ctx.Err() != nil {
		return NodeLease{}, ErrNodeUnavailable
	}
	lease, err := h.issuer.Admit(ctx, claims)
	if err != nil {
		if errors.Is(err, ErrDenied) {
			return NodeLease{}, ErrNodeDenied
		}
		return NodeLease{}, ErrNodeUnavailable
	}
	if !validNodeLease(lease, time.Now()) {
		return NodeLease{}, ErrNodeUnavailable
	}
	return NodeLease{SessionID: lease.SessionID, NotAfter: lease.Deadline, ByteLimit: lease.ByteCap}, nil
}

// Report returns an exact acknowledgement only after the scoped PostgreSQL
// high-water is durable. Ambiguous commits are errors so native code retries
// the same immutable snapshot.
func (h *NodeHooks) Report(ctx context.Context, report NodeCumulativeReport) (NodeCumulativeReport, error) {
	if h == nil || ctx == nil || ctx.Err() != nil {
		return NodeCumulativeReport{}, ErrNodeUnavailable
	}
	if !nodeSessionID.MatchString(report.SessionID) || report.Sequence < 1 ||
		report.ClientToExitBytes < 0 || report.ExitToClientBytes < 0 {
		return NodeCumulativeReport{}, ErrNodeDenied
	}
	err := h.journal.RecordQACumulative(ctx, store.QAMeterReport{
		Subject: h.subject, InstallationID: h.installation.ID,
		SessionID: report.SessionID, Sequence: report.Sequence,
		ClientToExitBytes: report.ClientToExitBytes, ExitToClientBytes: report.ExitToClientBytes,
	})
	if err != nil {
		return NodeCumulativeReport{}, ErrNodeUnavailable
	}
	if report.Terminal {
		row, closeErr := h.journal.CloseQASession(ctx, h.subject, h.installation.ID, report.SessionID,
			&store.QAFinal{ClientToExitBytes: report.ClientToExitBytes, ExitToClientBytes: report.ExitToClientBytes})
		if closeErr != nil || row.Status != store.QAClosed {
			return NodeCumulativeReport{}, ErrNodeUnavailable
		}
	}
	return report, nil
}

// ObserveFinal records an immutable terminal observation. It neither advances
// cumulative high-water nor acknowledges it; an ahead-of-ACK total may remain
// unconfirmed until the exact durable cumulative report arrives.
func (h *NodeHooks) ObserveFinal(ctx context.Context, final NodeFinalTotals) error {
	if h == nil || ctx == nil || ctx.Err() != nil || nilDependency(h.journal) {
		return ErrNodeUnavailable
	}
	if !nodeSessionID.MatchString(final.SessionID) || final.ClientToExitBytes < 0 || final.ExitToClientBytes < 0 {
		return ErrNodeDenied
	}
	_, err := h.journal.CloseQASession(ctx, h.subject, h.installation.ID, final.SessionID,
		&store.QAFinal{ClientToExitBytes: final.ClientToExitBytes, ExitToClientBytes: final.ExitToClientBytes})
	if err != nil {
		return ErrNodeUnavailable
	}
	return nil
}

// Renew never extends the native lease before the Bridge has durably checked
// a fresh Remnawave snapshot and fenced the exact QA row. A definitive refusal
// cuts the active native session. A transient outage leaves the prior deadline intact
// for a bounded retry; the native server still expires that lease on time.
func (h *NodeHooks) Renew(ctx context.Context, sessionID string) error {
	if h == nil || !nodeSessionID.MatchString(sessionID) {
		return ErrNodeDenied
	}
	if ctx == nil || ctx.Err() != nil {
		return ErrNodeUnavailable
	}
	lease, err := h.issuer.Renew(ctx, sessionID)
	if errors.Is(err, ErrDenied) {
		h.node.CloseSession(sessionID)
		return ErrNodeDenied
	}
	if err != nil {
		return ErrNodeUnavailable
	}
	if lease.SessionID != sessionID || !validNodeLease(lease, time.Now()) {
		h.node.CloseSession(sessionID)
		return ErrNodeDenied
	}
	if !h.node.RenewLease(sessionID, lease.Deadline) {
		h.node.CloseSession(sessionID)
		return ErrNodeDenied
	}
	return nil
}

// Close records that the native transport stopped. Its terminal byte total is
// unknown here: a late durable meter snapshot may still advance unconfirmed
// high-water, but the row cannot authorize another transport.
func (h *NodeHooks) Close(ctx context.Context, sessionID string) error {
	if h == nil || !nodeSessionID.MatchString(sessionID) {
		return ErrNodeDenied
	}
	if ctx == nil || ctx.Err() != nil {
		return ErrNodeUnavailable
	}
	_, err := h.journal.CloseQASession(ctx, h.subject, h.installation.ID, sessionID, nil)
	if err != nil {
		return ErrNodeUnavailable
	}
	return nil
}

func validNodeLease(lease SessionLease, now time.Time) bool {
	return nodeSessionID.MatchString(lease.SessionID) &&
		lease.Deadline.After(now) && !lease.Deadline.After(now.Add(5*time.Minute)) &&
		lease.ByteCap > 0 && lease.ByteCap <= 1<<40
}
