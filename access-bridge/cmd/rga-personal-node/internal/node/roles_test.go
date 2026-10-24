package node

import (
	"encoding/json"
	"testing"
)

func roleFixture(t *testing.T, role string) Config {
	t.Helper()
	var c Config
	if json.Unmarshal([]byte(validConfigJSON), &c) != nil {
		t.Fatal("fixture")
	}
	c.Role = role
	// Use the actual config JSON contract so the regression compiles before the
	// new field exists; the old parser ignores it and the behavior tests fail.
	if json.Unmarshal([]byte(`{"provider_room_url":"https://telemost.yandex.ru/j/12345678901234"}`), &c) != nil {
		t.Fatal("provider locator fixture")
	}
	c.LeaseTTLSeconds = 30
	c.WorkerID, c.WorkerEpoch, c.RouteID = "ru-worker-01", "epoch-0000000001", "subscriber-nl"
	c.ControlCAFile, c.ControlCertFile, c.ControlKeyFile = "ca.pem", "cert.pem", "key.pem"
	c.ControlPeerSPKI = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
	if role == "ru-carrier-v1" {
		c.PostgresDSN, c.PanelOrigin, c.PanelAdminToken, c.PanelAuthKind = "", "", "", ""
		c.ControlOrigin = "https://rga.apollot.ru:28444"
		c.RequireEgressProxy, c.EgressSOCKSAddress, c.EgressSOCKSPort = true, "127.0.0.1", 1080
	} else {
		c.ControlListenAddress = "0.0.0.0:28444"
	}
	return c
}

func TestRelayRoleRequiresStrictProviderRoomURL(t *testing.T) {
	for _, role := range []string{"home-control-v1", "ru-carrier-v1"} {
		for _, locator := range []string{
			"", "room_A063_01", "http://telemost.yandex.ru/j/12345678901234",
			"https://telemost.yandex.ru.attacker.test/j/12345678901234",
			"https://user@telemost.yandex.ru/j/12345678901234", "https://telemost.yandex.ru:443/j/12345678901234",
			"https://telemost.yandex.ru/j/1234567890123", "https://telemost.yandex.ru/j/123456789012345",
			"https://telemost.yandex.ru/j/1234567890123x", "https://telemost.yandex.ru/j/12345678901234/",
			"https://telemost.yandex.ru/j/12345678901234?x=1", "https://telemost.yandex.ru/j/12345678901234#fragment",
		} {
			t.Run(role+"/"+locator, func(t *testing.T) {
				c := roleFixture(t, role)
				data, err := json.Marshal(map[string]string{"provider_room_url": locator})
				if err != nil || json.Unmarshal(data, &c) != nil {
					t.Fatal("locator mutation fixture")
				}
				if c.validate() == nil {
					t.Fatal("AR2_INVALID_PROVIDER_LOCATOR_ACCEPTED")
				}
			})
		}
	}
}

func TestLegacyRoleWithoutProviderLocatorUnchanged(t *testing.T) {
	var c Config
	if json.Unmarshal([]byte(validConfigJSON), &c) != nil || c.validate() != nil {
		t.Fatal("legacy config without provider locator regressed")
	}
}

func TestRoleConfigSeparatesSecretsAndPayload(t *testing.T) {
	ru := roleFixture(t, "ru-carrier-v1")
	if ru.validate() != nil {
		t.Fatal("scoped RU carrier rejected")
	}
	home := roleFixture(t, "home-control-v1")
	if home.validate() != nil {
		t.Fatal("control-only Home rejected")
	}
	checks := []struct {
		name string
		cfg  Config
	}{
		{"RU panel credential", func() Config { c := ru; c.PanelAdminToken = "private"; return c }()},
		{"RU database credential", func() Config { c := ru; c.PostgresDSN = "postgres://private"; return c }()},
		{"RU public bootstrap", func() Config { c := ru; c.BootstrapTLSCertFile = "public.pem"; return c }()},
		{"RU direct fallback", func() Config {
			c := ru
			c.EgressSOCKSAddress = ""
			c.EgressSOCKSPort = 0
			c.RequireEgressProxy = false
			return c
		}()},
		{"Home egress", func() Config { c := home; c.EgressSOCKSAddress = "gateway"; c.EgressSOCKSPort = 1080; return c }()},
		{"Home UDP egress", func() Config { c := home; c.EgressUDPSOCKSAddress = "gateway"; return c }()},
		{"long control lease", func() Config { c := home; c.LeaseTTLSeconds = 300; return c }()},
		{"unknown role", func() Config { c := home; c.Role = "unknown"; return c }()},
		{"missing epoch", func() Config { c := ru; c.WorkerEpoch = ""; return c }()},
		{"IP control origin", func() Config { c := ru; c.ControlOrigin = "https://192.0.2.1:28444"; return c }()},
		{"legacy control fallback", func() Config { c := home; c.Role = ""; return c }()},
	}
	for _, tc := range checks {
		t.Run(tc.name, func(t *testing.T) {
			if tc.cfg.validate() == nil {
				t.Fatal("role boundary accepted")
			}
		})
	}
}
