package node

import (
	"encoding/json"
	"testing"
)

func TestReserveProviderPairRetainsRoleBoundaries(t *testing.T) {
	for _, role := range []string{"home-control-v1", "ru-carrier-v1"} {
		c := roleFixture(t, role)
		if err := json.Unmarshal([]byte(`{"provider":"salutejazz","provider_room_url":"abc123:abcd1234"}`), &c); err != nil {
			t.Fatal(err)
		}
		if c.validate() != nil {
			t.Fatal("explicit managed reserve provider pair rejected")
		}
		c.LeaseTTLSeconds = 300
		if c.validate() == nil {
			t.Fatal("reserve changed the finite lease boundary")
		}
	}
}

func TestReserveProviderCannotSmuggleAHostOrCrossProviderLocator(t *testing.T) {
	for _, data := range []string{
		`{"provider":"salutejazz","provider_room_url":"https://telemost.yandex.ru/j/12345678901234"}`,
		`{"provider":"telemost","provider_room_url":"abc123:abcd1234"}`,
		`{"provider":"jitsi","provider_room_url":"https://meet.example.invalid/room"}`,
		`{"provider":"salutejazz","provider_room_url":"https://salutejazz.ru/abc123?psw=abcd1234"}`,
		`{"provider":"salutejazz","provider_room_url":"abc123:abcd1234:extra"}`,
		`{"provider":"salutejazz","provider_room_url":"ABC123:abcd1234"}`,
		`{"provider":"salutejazz","provider_room_url":"abc123:abcd1234\n"}`,
	} {
		c := roleFixture(t, "ru-carrier-v1")
		if json.Unmarshal([]byte(data), &c) != nil {
			t.Fatal("invalid test fixture")
		}
		if c.validate() == nil {
			t.Fatal("cross-provider or arbitrary endpoint accepted")
		}
	}
}
