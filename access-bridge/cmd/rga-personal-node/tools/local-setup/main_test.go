package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestOnlyDedicatedLoopbackDatabaseMayBeProvisioned(t *testing.T) {
	good := "postgres://bridge_test@127.0.0.1:55433/apollo_rga_bridge_test?sslmode=disable"
	if !validLocalDB(good) {
		t.Fatal("dedicated loopback DB rejected")
	}
	for _, value := range []string{
		"postgres://bridge_test@127.0.0.1:5432/apollo_rga_bridge_test?sslmode=disable",
		"postgres://bridge_test@db.internal:55433/apollo_rga_bridge_test?sslmode=disable",
		"postgres://bridge_test@127.0.0.1:55433/production?sslmode=disable",
		good + "&hostaddr=203.0.113.1",
		"postgres://bridge_test:password@127.0.0.1:55433/apollo_rga_bridge_test?sslmode=disable",
		"postgres://bridge_test@127.0.0.1:55433/apollo_rga_bridge_test?sslmode=require",
		"postgres://bridge_test@127.0.0.1:55433/apollo_rga_bridge_test?sslmode=disable&sslmode=disable",
	} {
		if validLocalDB(value) {
			t.Fatal("unsafe database URI accepted")
		}
	}
}

func TestPrivateInputRejectsUnknownDuplicateOrTrailingFields(t *testing.T) {
	for _, content := range []string{
		`{"installationId":"one","installationId":"two","publicKeySha256":"` + strings.Repeat("a", 64) + `"}`,
		`{"installationId":"one","publicKeySha256":"` + strings.Repeat("a", 64) + `","extra":true}`,
		`{"installationId":"one","publicKeySha256":"` + strings.Repeat("a", 64) + `"} trailing`,
	} {
		path := filepath.Join(t.TempDir(), "binding.json")
		if err := os.WriteFile(path, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
		var decoded binding
		if loadStrict(path, 1024, &decoded) == nil {
			t.Fatal("invalid private input accepted")
		}
	}
}

func TestSessionRequiresExactPanelAndSubscriptionOrigins(t *testing.T) {
	base := panelSession{
		PanelOrigin: panelOrigin, PanelBrowserJWT: "test-jwt", UserID: 42,
		SubscriptionURL: subOrigin + "/TestToken_123",
	}
	if !validSession(base) {
		t.Fatal("exact personal session rejected")
	}
	for _, link := range []string{
		"https://pan.gate-altas.tech.evil.invalid/TestToken_123",
		"https://pan.gate-altas.tech:443/TestToken_123",
		"https://pan.gate-altas.tech/TestToken_123?x=1",
		"https://pan.gate-altas.tech/TestToken_123/extra",
		"https://pan.gate-altas.tech/Test%2fToken_123",
	} {
		candidate := base
		candidate.SubscriptionURL = link
		if validSession(candidate) {
			t.Fatal("unexpected subscription authority accepted")
		}
	}
}
