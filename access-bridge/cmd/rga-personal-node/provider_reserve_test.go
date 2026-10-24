package main

import (
	"testing"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
)

func TestReserveCompositionPreservesNativeAndControlProviderBinding(t *testing.T) {
	cfg := node.Config{Role: "ru-carrier-v1", Provider: "salutejazz", ProviderRoomURL: "abc123:abcd1234",
		RoomID: "authority-room-02", WorkerID: "worker-reserve-02", WorkerEpoch: "epoch-reserve-02", RouteID: "subscriber-nl"}
	native := newTunnelConfig(cfg, nil, nil)
	scope := controlScope(cfg)
	if native.Provider != "salutejazz" || scope.Provider != native.Provider || native.Transport != "datachannel" ||
		native.TransportOptions != nil ||
		native.RoomURL != cfg.ProviderRoomURL || native.ChannelID != cfg.RoomID ||
		!native.RequireAuthHook || !native.RequireLease || !native.RequireMeter || scope.Epoch != cfg.WorkerEpoch {
		t.Fatal("reserve provider lost its immutable composition or managed enforcement")
	}
}

func TestVKReserveKeepsManagedEnforcementAndVP8Options(t *testing.T) {
	cfg := node.Config{Role: "ru-carrier-v1", Provider: "vkcalls",
		ProviderRoomURL: "https://vk.ru/call/join/fixture-opaque_123", RoomID: "authority-room-vk",
		WorkerID: "worker-vk", WorkerEpoch: "epoch-vk", RouteID: "subscriber-nl"}
	native := newTunnelConfig(cfg, nil, nil)
	scope := controlScope(cfg)
	if native.Provider != "vkcalls" || scope.Provider != native.Provider || native.Transport != "vp8channel" ||
		native.TransportOptions == nil || native.RoomURL != cfg.ProviderRoomURL || native.ChannelID != cfg.RoomID ||
		!native.RequireAuthHook || !native.RequireLease || !native.RequireMeter || scope.Epoch != cfg.WorkerEpoch {
		t.Fatal("VK reserve changed managed authority or transport capability")
	}
}

func TestMTSReserveKeepsManagedEnforcementAndVP9CarrierOptions(t *testing.T) {
	cfg := node.Config{Role: "ru-carrier-v1", Provider: "mtslink",
		ProviderRoomURL: "https://my.mts-link.ru/j/123/456/session/789?ice=tcp", RoomID: "authority-room-mts",
		WorkerID: "worker-mts", WorkerEpoch: "epoch-mts", RouteID: "subscriber-nl"}
	native := newTunnelConfig(cfg, nil, nil)
	scope := controlScope(cfg)
	if native.Provider != "mtslink" || scope.Provider != native.Provider || native.Transport != "vp8channel" ||
		native.TransportOptions == nil || native.RoomURL != cfg.ProviderRoomURL || native.ChannelID != cfg.RoomID ||
		!native.RequireAuthHook || !native.RequireLease || !native.RequireMeter || scope.Epoch != cfg.WorkerEpoch {
		t.Fatal("MTS reserve changed managed authority or carrier options")
	}
}
