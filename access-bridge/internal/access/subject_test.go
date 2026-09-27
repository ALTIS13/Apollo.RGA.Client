package access

import (
	"errors"
	"strings"
	"testing"
)

func TestSubjectRequiresCanonicalConfiguredPanelAndPositiveUser(t *testing.T) {
	if err := (Subject{PanelInstance: "rga-main", UserID: 42}).Validate(); err != nil {
		t.Fatalf("valid canonical subject rejected: %v", err)
	}
	for _, subject := range []Subject{
		{PanelInstance: "", UserID: 42},
		{PanelInstance: "https://panel.example.invalid", UserID: 42},
		{PanelInstance: "RGA-main", UserID: 42},
		{PanelInstance: "rga-main", UserID: 0},
		{PanelInstance: "rga-main", UserID: -1},
	} {
		err := subject.Validate()
		if !errors.Is(err, ErrInvalidSubject) || strings.Contains(err.Error(), "panel.example.invalid") {
			t.Fatalf("invalid subject did not fail with redacted sentinel: %v", err)
		}
	}
}

func TestInstallationBindingRejectsBearerShapedAndUnkeyedInput(t *testing.T) {
	valid := InstallationBinding{ID: "installation_opaque_1234", PublicKeySHA256: [32]byte{1}}
	if err := valid.Validate(); err != nil {
		t.Fatalf("valid binding rejected: %v", err)
	}
	for _, binding := range []InstallationBinding{
		{ID: "short", PublicKeySHA256: [32]byte{1}},
		{ID: "https://panel.example.invalid/sub/secret", PublicKeySHA256: [32]byte{1}},
		{ID: "installation_opaque_1234"},
	} {
		err := binding.Validate()
		if !errors.Is(err, ErrInvalidInstallation) || strings.Contains(err.Error(), "panel.example.invalid") {
			t.Fatalf("invalid installation did not fail with redacted sentinel: %v", err)
		}
	}
}
