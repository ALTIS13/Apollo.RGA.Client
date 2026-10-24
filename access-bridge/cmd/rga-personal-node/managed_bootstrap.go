package main

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/rtcprovider"
)

// This is finite discovery metadata, not a session lease or runtime grant.
// Admission still requires the independent fresh managed node-control exchange.
type managedBootstrapV2 struct {
	SchemaVersion    int    `json:"schemaVersion"`
	AdmissionMode    string `json:"admissionMode"`
	PanelInstance    string `json:"panelInstance"`
	UserID           int64  `json:"userId"`
	InstallationID   string `json:"installationId"`
	Provider         string `json:"provider"`
	Transport        string `json:"transport"`
	RoomID           string `json:"roomId"`
	RoomURL          string `json:"roomUrl"`
	Country          string `json:"country"`
	RoomKey          string `json:"roomKey"`
	PolicyRevision   int64  `json:"policyRevision"`
	ExpiresAtEpochMs int64  `json:"expiresAtEpochMs"`
}

func newManagedBootstrapHandlerWithBoundary(subject access.Subject, binding access.InstallationBinding, providerRoomURL string, link linkBeginner, complete bootstrapCompleter, guard *bootstrapRateGuard, boundary func(*http.Request) bool, now func() time.Time) http.Handler {
	return newManagedProviderBootstrapHandlerWithBoundary(subject, binding, "telemost", providerRoomURL, link, complete, guard, boundary, now)
}

func newManagedProviderBootstrapHandlerWithBoundary(subject access.Subject, binding access.InstallationBinding, provider, providerRoomURL string, link linkBeginner, complete bootstrapCompleter, guard *bootstrapRateGuard, boundary func(*http.Request) bool, now func() time.Time) http.Handler {
	// Immutable value copies bind the response to server configuration; neither
	// request fields nor completer metadata can substitute another identity.
	transport := rtcprovider.Transport(provider)
	if subject.Validate() != nil || binding.Validate() != nil || !rtcprovider.Valid(provider, transport, providerRoomURL) || now == nil {
		return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			guard.observeBootstrap(bootstrapConfig)
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("X-Content-Type-Options", "nosniff")
			bootstrapError(w, http.StatusServiceUnavailable, "unavailable")
		})
	}
	prefix := "/v1/managed"
	if provider == "salutejazz" || provider == "vkcalls" || provider == "mtslink" {
		prefix += "/reserve"
	}
	return newBootstrapHandlerForPaths(binding, link, complete, guard, boundary,
		prefix+"/link/begin", prefix+"/bootstrap",
		func(w http.ResponseWriter, result personal.BootstrapV1) {
			expires := result.ExpiresAtEpochMs
			if cap := now().Add(5 * time.Minute).UnixMilli(); cap < expires {
				expires = cap
			}
			_ = json.NewEncoder(w).Encode(managedBootstrapV2{
				SchemaVersion: 3, AdmissionMode: "managed-lease-v2",
				PanelInstance: subject.PanelInstance, UserID: subject.UserID, InstallationID: binding.ID,
				Provider: provider, Transport: transport, RoomID: result.RoomID, RoomURL: providerRoomURL,
				Country: result.Country, RoomKey: result.RoomKey, PolicyRevision: result.PolicyRevision,
				ExpiresAtEpochMs: expires,
			})
		})
}
