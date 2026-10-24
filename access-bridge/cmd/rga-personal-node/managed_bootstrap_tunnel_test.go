package main

import (
	"encoding/json"
	"testing"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
)

// Exercise the real native configuration builder, not a DTO-only substitute.
// Provider meeting locator and authority/channel ID are deliberately different.
func TestManagedTunnelSeparatesProviderLocatorAndAuthorityChannel(t *testing.T) {
	for _, role := range []string{"home-control-v1", "ru-carrier-v1"} {
		t.Run(role, func(t *testing.T) {
			var cfg node.Config
			data := `{"role":"` + role + `","room_id":"authority_room_01","provider_room_url":"https://telemost.yandex.ru/j/12345678901234"}`
			if err := json.Unmarshal([]byte(data), &cfg); err != nil {
				t.Fatal(err)
			}
			adapter := &nativeAdapter{}
			got := newTunnelConfig(cfg, adapter, adapter)
			if got.RoomURL != "https://telemost.yandex.ru/j/12345678901234" || got.ChannelID != "authority_room_01" {
				t.Fatal("AR2_LOCATOR_CHANNEL_CONFLATED: native config lost explicit provider URL/channel binding")
			}
		})
	}
}

func TestManagedTunnelLegacyRoomBehaviorUnchanged(t *testing.T) {
	cfg := node.Config{RoomID: "legacy_room_01"}
	adapter := &nativeAdapter{}
	got := newTunnelConfig(cfg, adapter, adapter)
	if got.RoomURL != "legacy_room_01" || got.ChannelID != "" {
		t.Fatal("legacy room configuration changed without explicit role")
	}
}
