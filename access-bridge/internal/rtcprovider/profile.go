// Package rtcprovider validates the operator-owned managed RTC profiles.
// A locator is secret rendezvous data, never admission or a selectable proxy.
package rtcprovider

import "regexp"

// Transport is fixed by the pinned provider engine's capabilities. In
// particular, SaluteJazz exposes data channels but no video track API.
// Unknown providers have no transport and cannot become an implicit default.
func Transport(provider string) string {
	switch provider {
	case "telemost", "vkcalls", "mtslink":
		return "vp8channel"
	case "salutejazz":
		return "datachannel"
	default:
		return ""
	}
}

var (
	telemostRoom = regexp.MustCompile(`^https://telemost\.yandex\.ru/j/[0-9]{14}$`)
	jazzRoom     = regexp.MustCompile(`^[a-z0-9]{4,16}:[a-z0-9]{4,16}$`)
	vkRoom       = regexp.MustCompile(`^https://(?:vk\.ru|vk\.com)/call/join/[A-Za-z0-9_-]{1,256}$`)
	// Explicit session + TCP is the first qualified MTS tuple; no implicit
	// provider defaults or additional query data may change the bound carrier.
	mtsRoom = regexp.MustCompile(`^https://my\.mts-link\.ru/j/[1-9][0-9]{0,18}/[1-9][0-9]{0,18}/session/[1-9][0-9]{0,18}\?ice=tcp$`)
)

func Supported(provider string) bool {
	return provider == "telemost" || provider == "salutejazz" || provider == "vkcalls" || provider == "mtslink"
}

func Valid(provider, transport, locator string) bool {
	if transport == "" || transport != Transport(provider) {
		return false
	}
	switch provider {
	case "telemost":
		return telemostRoom.MatchString(locator)
	case "salutejazz":
		return jazzRoom.MatchString(locator)
	case "vkcalls":
		return vkRoom.MatchString(locator)
	case "mtslink":
		return mtsRoom.MatchString(locator)
	default:
		return false
	}
}
