package store

import (
	"bytes"
	"context"
	"database/sql"
	"errors"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

var ErrPersonalBootstrapDenied = errors.New("store: personal bootstrap denied")

// LoadPersonalBootstrapState reads one consistent database snapshot for the
// exact already-linked installation. This is a bootstrap eligibility check,
// not a network grant; the node must independently recheck each join.
func (s *Store) LoadPersonalBootstrapState(ctx context.Context, subject access.Subject, binding access.InstallationBinding) (access.SubjectState, error) {
	if subject.Validate() != nil || binding.Validate() != nil {
		return access.SubjectState{}, ErrPersonalBootstrapDenied
	}
	state := access.SubjectState{Subject: subject}
	var fingerprint []byte
	var qaEnabled bool
	err := s.db.QueryRowContext(ctx, `SELECT s.olcrtc_enabled, s.policy_revision,
		i.public_key_sha256, q.enabled
		FROM bridge_subjects s
		JOIN bridge_installations i ON i.panel_instance=s.panel_instance
			AND i.user_id=s.user_id AND i.installation_id=$3
		JOIN bridge_qa_installations q ON q.installation_id=i.installation_id
		WHERE s.panel_instance=$1 AND s.user_id=$2`,
		subject.PanelInstance, subject.UserID, binding.ID,
	).Scan(&state.OlcRTCEnabled, &state.Revision, &fingerprint, &qaEnabled)
	if errors.Is(err, sql.ErrNoRows) {
		return access.SubjectState{}, ErrPersonalBootstrapDenied
	}
	if err != nil {
		return access.SubjectState{}, ErrUnavailable
	}
	if !state.OlcRTCEnabled || state.Revision <= 0 || !qaEnabled || !bytes.Equal(fingerprint, binding.PublicKeySHA256[:]) {
		return access.SubjectState{}, ErrPersonalBootstrapDenied
	}
	return state, nil
}
