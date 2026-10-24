package node

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const validConfigJSON = `{
  "postgres_dsn":"postgres://bridge_test@127.0.0.1:55433/bridge_test?sslmode=disable",
  "panel_origin":"https://panel.example.org",
  "panel_admin_token":"private-panel-token",
  "panel_instance":"a063",
  "user_id":63,
  "installation_id":"installation-A063-0001",
  "installation_public_key_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "room_id":"room_A063_01",
  "country":"NL",
  "key_hex":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "lease_ttl_seconds":300,
  "max_snapshot_age_seconds":30
}`

func writeConfig(t *testing.T, contents string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "node.json")
	if err := os.WriteFile(path, []byte(contents), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestLoadConfigBindsOneRoomAndInstallation(t *testing.T) {
	cfg, err := LoadConfig(writeConfig(t, validConfigJSON))
	if err != nil {
		t.Fatalf("LoadConfig: %v", err)
	}
	personal := cfg.PersonalTestConfig()
	if personal.RoomID != "room_A063_01" || personal.Country != "NL" ||
		personal.Subject.PanelInstance != "a063" || personal.Subject.UserID != 63 ||
		personal.Installation.ID != "installation-A063-0001" ||
		personal.LeaseTTL != 5*time.Minute || personal.MaxSnapshotAge != 30*time.Second ||
		personal.SessionByteCap != 1<<40 || personal.Installation.PublicKeySHA256[0] != 0xaa {
		t.Fatalf("incorrect immutable personal binding: %+v", personal)
	}
	if cfg.RoomID != personal.RoomID {
		t.Fatal("native carrier room differs from signed proof room")
	}
}

func TestPublicBootstrapRequiresExplicitExactTrustedForwarder(t *testing.T) {
	base := strings.TrimSuffix(strings.TrimSpace(validConfigJSON), "}")
	suffix := `,"bootstrap_tls_cert_file":"cert.pem","bootstrap_tls_key_file":"key.pem",` +
		`"bootstrap_listen_address":"0.0.0.0:18443","require_egress_proxy":true,` +
		`"egress_socks_address":"gateway","egress_socks_port":1080,` +
		`"bootstrap_public_host":"rga.apollot.ru","bootstrap_trusted_forwarder":"10.0.27.1"}`
	if _, err := LoadConfig(writeConfig(t, base+suffix)); err != nil {
		t.Fatal("explicit scoped public ingress rejected")
	}
	for _, bad := range []string{
		strings.Replace(suffix, `"rga.apollot.ru"`, `"other.example"`, 1),
		strings.Replace(suffix, `"10.0.27.1"`, `"198.51.100.9"`, 1),
		strings.Replace(suffix, `"10.0.27.1"`, `"0.0.0.0"`, 1),
		strings.Replace(suffix, `"10.0.27.1"`, `""`, 1),
		strings.Replace(suffix, `"require_egress_proxy":true`, `"require_egress_proxy":false`, 1),
	} {
		if _, err := LoadConfig(writeConfig(t, base+bad)); err == nil {
			t.Fatal("unbounded public ingress accepted")
		}
	}
}

func TestLoadConfigRejectsUnknownAndInvalidWithoutEchoingSecrets(t *testing.T) {
	tests := []struct{ name, contents string }{
		{"unknown field", strings.Replace(validConfigJSON, `"room_id":`, `"room_url":"https://elsewhere/room","room_id":`, 1)},
		{"noncanonical room", strings.Replace(validConfigJSON, `"room_A063_01"`, `"https://telemost.yandex.ru/j/room_A063_01"`, 1)},
		{"expired lease window", strings.Replace(validConfigJSON, `"lease_ttl_seconds":300`, `"lease_ttl_seconds":301`, 1)},
		{"zero key", strings.Replace(validConfigJSON, strings.Repeat("b", 64), strings.Repeat("0", 64), 1)},
		{"bad subject", strings.Replace(validConfigJSON, `"user_id":63`, `"user_id":0`, 1)},
		{"short fingerprint", strings.Replace(validConfigJSON, strings.Repeat("a", 64), strings.Repeat("a", 62), 1)},
		{"duplicate room", strings.Replace(validConfigJSON, `"room_id":`, `"room_id":"other_room","room_id":`, 1)},
		{"configurable cap", strings.Replace(validConfigJSON, `"country":`, `"session_byte_cap":1,"country":`, 1)},
		{"trailing document", validConfigJSON + `{"panel_admin_token":"leak"}`},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := LoadConfig(writeConfig(t, tc.contents))
			if !errors.Is(err, ErrConfig) {
				t.Fatalf("LoadConfig error = %v, want ErrConfig", err)
			}
			if strings.Contains(err.Error(), "private-panel-token") || strings.Contains(err.Error(), "leak") || strings.Contains(err.Error(), "postgres://") {
				t.Fatal("config error echoed a secret")
			}
		})
	}
}

func TestLoadConfigIsBounded(t *testing.T) {
	_, err := LoadConfig(writeConfig(t, validConfigJSON+strings.Repeat(" ", 16<<10)))
	if !errors.Is(err, ErrConfig) {
		t.Fatalf("oversized file error = %v, want ErrConfig", err)
	}
}

func TestLinuxConfigRequiresOwnerOnlyRegularFile(t *testing.T) {
	tests := []struct {
		name string
		mode os.FileMode
		goos string
		want bool
	}{
		{"owner read", 0400, "linux", true},
		{"owner read write", 0600, "linux", true},
		{"group read", 0640, "linux", false},
		{"other read", 0604, "linux", false},
		{"owner execute only", 0100, "linux", false},
		{"directory", os.ModeDir | 0700, "linux", false},
		{"Windows ACL handled separately", 0644, "windows", true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := acceptableConfigMode(tc.mode, tc.goos); got != tc.want {
				t.Fatalf("acceptableConfigMode(%v, %q) = %v, want %v", tc.mode, tc.goos, got, tc.want)
			}
		})
	}
}

func TestLoadConfigRequiresCompleteBootstrapTLSFilePair(t *testing.T) {
	onlyCert := strings.Replace(validConfigJSON, `"room_id":`, `"bootstrap_tls_cert_file":"cert.pem","room_id":`, 1)
	if _, err := LoadConfig(writeConfig(t, onlyCert)); !errors.Is(err, ErrConfig) {
		t.Fatalf("single TLS file error = %v, want ErrConfig", err)
	}
	both := strings.Replace(onlyCert, `"bootstrap_tls_cert_file":"cert.pem",`, `"bootstrap_tls_cert_file":"cert.pem","bootstrap_tls_key_file":"key.pem",`, 1)
	cfg, err := LoadConfig(writeConfig(t, both))
	if err != nil || cfg.BootstrapTLSCertFile != "cert.pem" || cfg.BootstrapTLSKeyFile != "key.pem" {
		t.Fatalf("complete TLS file pair was not retained: err=%v", err)
	}
}

func TestPanelAuthKindDefaultsToAPITokenAndRejectsUnknownMode(t *testing.T) {
	defaultConfig, err := LoadConfig(writeConfig(t, validConfigJSON))
	if err != nil || defaultConfig.PanelAuthKind != "" {
		t.Fatalf("default auth config invalid: %v", err)
	}
	for _, mode := range []string{"api_token", "browser_session"} {
		input := strings.Replace(validConfigJSON, `"panel_admin_token":`, `"panel_auth_kind":"`+mode+`","panel_admin_token":`, 1)
		cfg, err := LoadConfig(writeConfig(t, input))
		if err != nil || cfg.PanelAuthKind != mode {
			t.Fatalf("auth mode %q rejected: %v", mode, err)
		}
	}
	invalid := strings.Replace(validConfigJSON, `"panel_admin_token":`, `"panel_auth_kind":"browser","panel_admin_token":`, 1)
	if _, err := LoadConfig(writeConfig(t, invalid)); !errors.Is(err, ErrConfig) {
		t.Fatalf("unknown auth mode error = %v, want ErrConfig", err)
	}
}

func TestRemoteNodeRequiresConfiguredProxyAndPrivateTLSListener(t *testing.T) {
	input := strings.Replace(validConfigJSON, `"room_id":`, `"require_egress_proxy":true,"egress_socks_address":"rga-egress","egress_socks_port":1080,"bootstrap_listen_address":"0.0.0.0:18443","bootstrap_tls_cert_file":"cert.pem","bootstrap_tls_key_file":"key.pem","room_id":`, 1)
	cfg, err := LoadConfig(writeConfig(t, input))
	if err != nil || !cfg.RequireEgressProxy || cfg.EgressSOCKSAddress != "rga-egress" || cfg.EgressSOCKSPort != 1080 || cfg.BootstrapAddress() != "0.0.0.0:18443" {
		t.Fatalf("isolated remote configuration rejected: %v", err)
	}
	for _, invalid := range []string{
		strings.Replace(input, `"egress_socks_address":"rga-egress"`, `"egress_socks_address":""`, 1),
		strings.Replace(input, `"egress_socks_port":1080`, `"egress_socks_port":0`, 1),
		strings.Replace(input, `"egress_socks_address":"rga-egress"`, `"egress_socks_address":"socks://user:secret@relay"`, 1),
		strings.Replace(input, `"bootstrap_listen_address":"0.0.0.0:18443"`, `"bootstrap_listen_address":"8.8.8.8:18443"`, 1),
		strings.Replace(input, `"bootstrap_tls_cert_file":"cert.pem","bootstrap_tls_key_file":"key.pem",`, ``, 1),
	} {
		if _, err := LoadConfig(writeConfig(t, invalid)); !errors.Is(err, ErrConfig) {
			t.Fatalf("unsafe remote configuration accepted: %v", err)
		}
	}
}

func TestProxyConfigurationCannotSilentlyBecomeDirect(t *testing.T) {
	for _, fields := range []string{
		`"require_egress_proxy":true,`,
		`"egress_socks_port":1080,`,
		`"egress_socks_user":"private-user",`,
		`"egress_socks_address":"relay",`,
		`"egress_socks_address":"relay","egress_socks_port":1080,"egress_socks_user":"private-user",`,
	} {
		input := strings.Replace(validConfigJSON, `"room_id":`, fields+`"room_id":`, 1)
		if _, err := LoadConfig(writeConfig(t, input)); !errors.Is(err, ErrConfig) {
			t.Fatalf("partial egress configuration accepted: %v", err)
		}
	}
	legacy, err := LoadConfig(writeConfig(t, validConfigJSON))
	if err != nil || legacy.BootstrapAddress() != "127.0.0.1:18443" {
		t.Fatal("legacy local listener changed")
	}
}

func TestLoadConfigManagedUDPDefaultOffAndStrictDependencies(t *testing.T) {
	// Break caught: silently enabling UDP, partial/unknown selector, invalid SOCKS endpoint or credentials.
	defaultCfg, err := LoadConfig(writeConfig(t, validConfigJSON))
	if err != nil || defaultCfg.ManagedUDPVersion != 0 || defaultCfg.EgressUDPSOCKSAddress != "" || defaultCfg.EgressUDPSOCKSPort != 0 {
		t.Fatal("legacy configuration must keep managed UDP default off")
	}
	base := strings.TrimSuffix(strings.TrimSpace(validConfigJSON), "}")
	valid := `,"managed_udp_version":2,"egress_udp_socks_address":"udp-gateway","egress_udp_socks_port":5080,"egress_udp_socks_user":"udp-user","egress_udp_socks_password":"udp-password"}`
	cfg, err := LoadConfig(writeConfig(t, base+valid))
	if err != nil || cfg.ManagedUDPVersion != 2 || cfg.EgressUDPSOCKSAddress != "udp-gateway" || cfg.EgressUDPSOCKSPort != 5080 ||
		cfg.EgressUDPSOCKSUser != "udp-user" || cfg.EgressUDPSOCKSPassword != "udp-password" || cfg.EgressSOCKSAddress != "" || cfg.EgressSOCKSPort != 0 {
		t.Fatal("explicit UDP selector was not retained independently of TCP")
	}
	for _, fields := range []string{
		`,"managed_udp_version":2,"egress_udp_socks_address":"127.0.0.1","egress_udp_socks_port":1}`,
		`,"managed_udp_version":2,"egress_udp_socks_address":"::1","egress_udp_socks_port":65535}`,
		`,"managed_udp_version":2,"egress_udp_socks_address":"udp-gateway","egress_udp_socks_port":1080,"egress_udp_socks_user":"` + strings.Repeat("é", 127) + `x","egress_udp_socks_password":"` + strings.Repeat("p", 255) + `"}`,
	} {
		if _, err := LoadConfig(writeConfig(t, base+fields)); err != nil {
			t.Fatal("valid UDP boundary rejected")
		}
	}
	for _, tc := range []struct{ name, fields string }{
		{"zero with address", `,"egress_udp_socks_address":"udp-gateway"}`},
		{"zero with port", `,"egress_udp_socks_port":1080}`},
		{"zero with user", `,"egress_udp_socks_user":"private-user"}`},
		{"zero with password", `,"egress_udp_socks_password":"private-password"}`},
		{"unknown version", strings.Replace(valid, `"managed_udp_version":2`, `"managed_udp_version":3`, 1)},
		{"overflow version", strings.Replace(valid, `"managed_udp_version":2`, `"managed_udp_version":256`, 1)},
		{"missing address", `,"managed_udp_version":2,"egress_udp_socks_port":1080}`},
		{"missing port", `,"managed_udp_version":2,"egress_udp_socks_address":"udp-gateway"}`},
		{"high port", strings.Replace(valid, `"egress_udp_socks_port":5080`, `"egress_udp_socks_port":65536`, 1)},
		{"negative port", strings.Replace(valid, `"egress_udp_socks_port":5080`, `"egress_udp_socks_port":-1`, 1)},
		{"whitespace address", strings.Replace(valid, `"udp-gateway"`, `" udp-gateway"`, 1)},
		{"only user", `,"managed_udp_version":2,"egress_udp_socks_address":"udp-gateway","egress_udp_socks_port":1080,"egress_udp_socks_user":"private-user"}`},
		{"only password", `,"managed_udp_version":2,"egress_udp_socks_address":"udp-gateway","egress_udp_socks_port":1080,"egress_udp_socks_password":"private-password"}`},
		{"long user bytes", strings.Replace(valid, `"udp-user"`, `"`+strings.Repeat("é", 128)+`"`, 1)},
		{"long password bytes", strings.Replace(valid, `"udp-password"`, `"`+strings.Repeat("p", 256)+`"`, 1)},
		{"uri instead of address", strings.Replace(valid, `"udp-gateway"`, `"socks://user:secret@relay"`, 1)},
		{"duplicate selector", strings.Replace(valid, `"managed_udp_version":2`, `"managed_udp_version":0,"managed_udp_version":2`, 1)},
		{"unknown selector field", strings.Replace(valid, `"managed_udp_version":2`, `"managed_udp_override":true,"managed_udp_version":2`, 1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := LoadConfig(writeConfig(t, base+tc.fields))
			if err != ErrConfig {
				t.Fatalf("invalid UDP selector accepted or raw error leaked: %v", err)
			}
		})
	}
}
