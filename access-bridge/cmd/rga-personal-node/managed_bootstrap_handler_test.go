package main

import (
	"crypto/sha256"
	"encoding/json"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
)

func managedBootstrapFixture(t *testing.T, expiry time.Time, global, perInstallation int) (http.Handler, *testLinkBeginner, *testBootstrapCompleter, []byte) {
	t.Helper()
	_, link, complete, der := testBootstrapFixture(t, 30, 20)
	complete.result.ExpiresAtEpochMs = expiry.UnixMilli()
	now := func() time.Time { return time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC) }
	binding := access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: sha256.Sum256(der)}
	subject := access.Subject{PanelInstance: "server-owned-panel", UserID: 631}
	h := newManagedBootstrapHandlerWithBoundary(subject, binding, "https://telemost.yandex.ru/j/12345678901234", link, complete,
		newBootstrapRateGuard(now, global, perInstallation), bootstrapRequestBoundary, now)
	return h, link, complete, der
}

func TestReserveBootstrapUsesServerProviderAndDistinctPath(t *testing.T) {
	_, link, complete, der := testBootstrapFixture(t, 30, 20)
	now := func() time.Time { return time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC) }
	complete.result.ExpiresAtEpochMs = now().Add(time.Minute).UnixMilli()
	// A legacy completer still emits Telemost; only immutable server composition
	// selects the reserve provider, never client fields or completer defaults.
	complete.result.Provider = "telemost"
	binding := access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: sha256.Sum256(der)}
	h := newManagedProviderBootstrapHandlerWithBoundary(access.Subject{PanelInstance: "server-owned-panel", UserID: 631},
		binding, "salutejazz", "abc123:abcd1234", link, complete, newBootstrapRateGuard(now, 30, 20), bootstrapRequestBoundary, now)
	w := postBootstrap(t, h, "/v1/managed/reserve/bootstrap", completeBody(der))
	var got managedBootstrapV2
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil ||
		got.Provider != "salutejazz" || got.Transport != "datachannel" || got.RoomURL != "abc123:abcd1234" || got.RoomID != complete.result.RoomID {
		t.Fatal("reserve metadata or its server-owned provider binding was lost")
	}
	if postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der)).Code != http.StatusNotFound {
		t.Fatal("reserve handler must not shadow primary discovery")
	}
}

func TestVKBootstrapUsesTheReserveEndpointWithoutShadowingPrimary(t *testing.T) {
	_, link, complete, der := testBootstrapFixture(t, 30, 20)
	now := func() time.Time { return time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC) }
	complete.result.ExpiresAtEpochMs = now().Add(time.Minute).UnixMilli()
	binding := access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: sha256.Sum256(der)}
	h := newManagedProviderBootstrapHandlerWithBoundary(access.Subject{PanelInstance: "server-owned-panel", UserID: 631},
		binding, "vkcalls", "https://vk.ru/call/join/fixture-opaque_123", link, complete, newBootstrapRateGuard(now, 30, 20), bootstrapRequestBoundary, now)
	w := postBootstrap(t, h, "/v1/managed/reserve/bootstrap", completeBody(der))
	var got managedBootstrapV2
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil || got.Provider != "vkcalls" || got.Transport != "vp8channel" || got.RoomID != complete.result.RoomID {
		t.Fatal("VK reserve bootstrap lost its provider/path/authority binding")
	}
	if postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der)).Code != http.StatusNotFound {
		t.Fatal("VK reserve shadowed primary discovery")
	}
}

func TestMTSBootstrapUsesTheReserveEndpointWithoutShadowingPrimary(t *testing.T) {
	_, link, complete, der := testBootstrapFixture(t, 30, 20)
	now := func() time.Time { return time.Date(2026, 10, 24, 4, 0, 0, 0, time.UTC) }
	complete.result.ExpiresAtEpochMs = now().Add(time.Minute).UnixMilli()
	binding := access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: sha256.Sum256(der)}
	locator := "https://my.mts-link.ru/j/123/456/session/789?ice=tcp"
	h := newManagedProviderBootstrapHandlerWithBoundary(access.Subject{PanelInstance: "server-owned-panel", UserID: 631},
		binding, "mtslink", locator, link, complete, newBootstrapRateGuard(now, 30, 20), bootstrapRequestBoundary, now)
	w := postBootstrap(t, h, "/v1/managed/reserve/bootstrap", completeBody(der))
	var got managedBootstrapV2
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil || got.Provider != "mtslink" ||
		got.Transport != "vp8channel" || got.RoomURL != locator || got.RoomID != complete.result.RoomID ||
		got.AdmissionMode != "managed-lease-v2" {
		t.Fatal("MTS reserve bootstrap lost provider/path/authority binding")
	}
	if postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der)).Code != http.StatusNotFound {
		t.Fatal("MTS reserve shadowed primary discovery")
	}
}

// Missing managed routing or emitting the old personal DTO must fail here.
func TestManagedBootstrapExactServerMetadata(t *testing.T) {
	h, _, complete, der := managedBootstrapFixture(t, time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC), 30, 20)
	w := postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der))
	if w.Code != http.StatusOK {
		t.Fatalf("AR2_MANAGED_BOOTSTRAP_MISSING: status=%d", w.Code)
	}
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{
		"schemaVersion": float64(3), "admissionMode": "managed-lease-v2",
		"panelInstance": "server-owned-panel", "userId": float64(631), "installationId": "installation-A063-0001",
		"provider": "telemost", "transport": "vp8channel", "roomId": "room_A063_01", "country": "NL",
		"roomUrl": "https://telemost.yandex.ru/j/12345678901234",
		"roomKey": strings.Repeat("b", 64), "policyRevision": float64(7),
		"expiresAtEpochMs": float64(time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC).UnixMilli()),
	}
	if !reflect.DeepEqual(got, want) || complete.calls != 1 || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("AR2_MANAGED_SCHEMA3_LOCATOR: exact schema/server identity/completer metadata/room URL not preserved")
	}
}

// APK31 already interprets schema 2 as GENERAL. Managed metadata must not be
// silently accepted as that protocol even when admissionMode is present.
func TestManagedBootstrapNeverUsesLegacyGeneralSchema2(t *testing.T) {
	h, _, _, der := managedBootstrapFixture(t, time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC), 30, 20)
	w := postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der))
	var got struct {
		Schema  int    `json:"schemaVersion"`
		RoomURL string `json:"roomUrl"`
	}
	if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil {
		t.Fatal("managed discovery prerequisite failed")
	}
	if got.Schema != 3 || got.RoomURL != "https://telemost.yandex.ru/j/12345678901234" {
		t.Fatal("AR2_SCHEMA2_COLLISION: managed response is not distinct schema 3 with provider locator")
	}
}

func TestManagedBootstrapBeginPreservesChallenge(t *testing.T) {
	h, link, _, der := managedBootstrapFixture(t, time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC), 30, 20)
	w := postBootstrap(t, h, "/v1/managed/link/begin", beginBody(der))
	if w.Code != http.StatusOK {
		t.Fatalf("AR2_MANAGED_BEGIN_MISSING: status=%d", w.Code)
	}
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"challengeId": "challenge-A063-0001", "panelInstance": "a063",
		"nonce": "WgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "expiresAt": "2026-09-29T09:10:11Z"}
	if !reflect.DeepEqual(got, want) || link.calls != 1 {
		t.Fatal("managed begin changed the original link challenge")
	}
}

func TestManagedBootstrapHasNoPersonalOrGeneralAliases(t *testing.T) {
	for _, path := range []string{"/v1/personal/link/begin", "/v1/personal/bootstrap", "/v1/general/link/begin", "/v1/general/bootstrap", "/v1/managed/bootstrap/"} {
		t.Run(path, func(t *testing.T) {
			h, link, complete, der := managedBootstrapFixture(t, time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC), 30, 20)
			body := completeBody(der)
			if strings.HasSuffix(path, "/begin") {
				body = beginBody(der)
			}
			w := postBootstrap(t, h, path, body)
			if w.Code != http.StatusNotFound || link.calls != 0 || complete.calls != 0 {
				t.Fatalf("AR2_ALIAS_ADMITTED: status=%d", w.Code)
			}
		})
	}
	legacy, _, _, der := testBootstrapFixture(t, 30, 20)
	if w := postBootstrap(t, legacy, "/v1/personal/bootstrap", completeBody(der)); w.Code != http.StatusOK {
		t.Fatal("legacy personal handler was removed")
	}
	if w := postBootstrap(t, legacy, "/v1/managed/bootstrap", completeBody(der)); w.Code != http.StatusNotFound {
		t.Fatal("legacy personal handler silently acquired managed mode")
	}
}

func TestManagedBootstrapStrictFieldsAndBoundKey(t *testing.T) {
	for _, mutation := range []string{"unknown-subject", "duplicate", "wrong-key", "query"} {
		t.Run(mutation, func(t *testing.T) {
			h, _, complete, der := managedBootstrapFixture(t, time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC), 30, 20)
			body, path := completeBody(der), "/v1/managed/bootstrap"
			switch mutation {
			case "unknown-subject":
				body = strings.Replace(body, "{", `{"userId":999,`, 1)
			case "duplicate":
				body = strings.Replace(body, "{", `{"shortUuid":"second",`, 1)
			case "wrong-key":
				_, _, _, otherDER := testBootstrapFixture(t, 30, 20)
				body = completeBody(otherDER)
			case "query":
				path += "?userId=999"
			}
			w := postBootstrap(t, h, path, body)
			if w.Code != http.StatusBadRequest || complete.calls != 0 {
				t.Fatalf("managed strict boundary failed: status=%d", w.Code)
			}
		})
	}
}

func TestManagedBootstrapRateLimitsPreserved(t *testing.T) {
	for _, limits := range [][2]int{{1, 20}, {30, 1}} {
		h, _, complete, der := managedBootstrapFixture(t, time.Date(2026, 10, 15, 12, 1, 0, 0, time.UTC), limits[0], limits[1])
		if w := postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der)); w.Code != http.StatusOK {
			t.Fatalf("first bounded managed request denied: %d", w.Code)
		}
		w := postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der))
		if w.Code != http.StatusTooManyRequests || complete.calls != 1 {
			t.Fatal("managed global/installation rate limit bypassed")
		}
	}
}

func TestManagedBootstrapDiscoveryExpiryClamp(t *testing.T) {
	for _, tc := range []struct {
		name         string
		expiry, want time.Time
	}{
		{"shorter-completer", time.Date(2026, 10, 15, 12, 2, 0, 0, time.UTC), time.Date(2026, 10, 15, 12, 2, 0, 0, time.UTC)},
		{"five-minute-cap", time.Date(2026, 10, 15, 13, 0, 0, 0, time.UTC), time.Date(2026, 10, 15, 12, 5, 0, 0, time.UTC)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h, _, _, der := managedBootstrapFixture(t, tc.expiry, 30, 20)
			w := postBootstrap(t, h, "/v1/managed/bootstrap", completeBody(der))
			var got struct {
				Expiry int64  `json:"expiresAtEpochMs"`
				Mode   string `json:"admissionMode"`
			}
			if w.Code != http.StatusOK || json.Unmarshal(w.Body.Bytes(), &got) != nil || got.Expiry != tc.want.UnixMilli() || got.Mode != "managed-lease-v2" {
				t.Fatal("AR2_DISCOVERY_EXPIRY: expiry was extended or discovery is unversioned")
			}
		})
	}
}
