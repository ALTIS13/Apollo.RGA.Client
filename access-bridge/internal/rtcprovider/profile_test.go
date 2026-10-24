package rtcprovider

import "testing"

// The pinned SaluteJazz engine supplies data channels, not video tracks. This
// catches accepting a profile that fails before Connect, not a selectable default.
func TestProfilesRejectUnsupportedEngineTransportPairs(t *testing.T) {
	for _, tc := range []struct {
		provider, transport, locator string
		want                         bool
	}{
		{"telemost", "vp8channel", "https://telemost.yandex.ru/j/12345678901234", true},
		{"salutejazz", "datachannel", "abc123:abcd1234", true},
		{"salutejazz", "vp8channel", "abc123:abcd1234", false},
		{"telemost", "datachannel", "https://telemost.yandex.ru/j/12345678901234", false},
		{"unknown", "datachannel", "abc123:abcd1234", false},
		{"salutejazz", "datachannel", "https://example.invalid/room", false},
		{"vkcalls", "vp8channel", "https://vk.ru/call/join/fixture-opaque_123", true},
		{"vkcalls", "vp8channel", "https://vk.com/call/join/fixture-opaque_123", true},
		{"vkcalls", "datachannel", "https://vk.ru/call/join/fixture-opaque_123", false},
		{"vkcalls", "vp8channel", "https://vk.ru.evil.invalid/call/join/fixture", false},
		{"vkcalls", "vp8channel", "https://vk.ru/call/join/fixture?token=secret", false},
		{"vkcalls", "vp8channel", "https://vk.ru/call/join/fixture#secret", false},
		{"vkcalls", "vp8channel", "https://user:secret@vk.ru/call/join/fixture", false},
		{"vkcalls", "vp8channel", "https://vk.ru:443/call/join/fixture", false},
		{"vkcalls", "vp8channel", "https://vk.ru/call/join/%2e%2e", false},
		{"telemost", "vp8channel", "https://vk.ru/call/join/fixture", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru/j/123/456/session/789?ice=tcp", true},
		{"mtslink", "datachannel", "https://my.mts-link.ru/j/123/456/session/789?ice=tcp", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru/j/123/456?ice=tcp", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru/j/123/456/session/789", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru/j/123/456/session/789?ice=udp", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru/j/123/456/session/789?ice=tcp&token=secret", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru.evil.invalid/j/123/456/session/789?ice=tcp", false},
		{"mtslink", "vp8channel", "https://my.mts-link.ru/j/123/456/session/0?ice=tcp", false},
	} {
		t.Run(tc.provider+"/"+tc.transport+"/"+tc.locator, func(t *testing.T) {
			if got := Valid(tc.provider, tc.transport, tc.locator); got != tc.want {
				t.Fatalf("profile accepted=%v, want %v", got, tc.want)
			}
		})
	}
}
