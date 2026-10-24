package access

import (
	"context"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

// ExactPersonalInstallationEligibility is a deliberately narrow alternative
// to panel HWID mapping for one owner's test installation. It permits a link
// challenge, not a network session; CompleteLink still verifies possession of
// the installation key and re-reads subscription status and expiry.
type ExactPersonalInstallationEligibility struct {
	subject Subject
	binding InstallationBinding
}

func NewExactPersonalInstallationEligibility(subject Subject, binding InstallationBinding) (*ExactPersonalInstallationEligibility, error) {
	if subject.Validate() != nil || binding.Validate() != nil {
		return nil, ErrLinkConfig
	}
	return &ExactPersonalInstallationEligibility{subject: subject, binding: binding}, nil
}

func (e *ExactPersonalInstallationEligibility) PanelInstance() string {
	if e == nil {
		return ""
	}
	return e.subject.PanelInstance
}

func (e *ExactPersonalInstallationEligibility) Allowed(ctx context.Context, user remnawave.UserSnapshot, binding InstallationBinding) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	if e == nil || binding.Validate() != nil {
		return false, nil
	}
	return user.ID == e.subject.UserID && binding == e.binding, nil
}
