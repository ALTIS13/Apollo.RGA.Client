// Package access defines the local, non-authorizing identity for the Bridge.
package access

import (
	"errors"
	"regexp"
)

var (
	ErrInvalidSubject      = errors.New("access: invalid subject")
	ErrInvalidInstallation = errors.New("access: invalid installation")
	panelInstancePattern   = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)
	installationIDPattern  = regexp.MustCompile(`^[A-Za-z0-9_-]{16,128}$`)
)

// Subject is scoped to one configured panel instance, not a client-supplied URL.
type Subject struct {
	PanelInstance string
	UserID        int64
}

func (s Subject) Validate() error {
	if s.UserID <= 0 || !panelInstancePattern.MatchString(s.PanelInstance) {
		return ErrInvalidSubject
	}
	return nil
}

// InstallationBinding records only a fingerprint. Possession must be proven
// by a separate authenticated link flow before this value is ever persisted.
type InstallationBinding struct {
	ID              string
	PublicKeySHA256 [32]byte
}

func (b InstallationBinding) Validate() error {
	if !installationIDPattern.MatchString(b.ID) || b.PublicKeySHA256 == ([32]byte{}) {
		return ErrInvalidInstallation
	}
	return nil
}

// SubjectState is control-plane data, not a grant or ordinary-host switch.
type SubjectState struct {
	Subject
	OlcRTCEnabled bool
	Revision      int64
}
