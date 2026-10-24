package remnawave

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestMetadataVersionUsesAuthenticatedExactGET(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.RequestURI() != "/api/system/metadata" || r.Header.Get("Authorization") != "Bearer "+testToken {
			t.Errorf("incorrect metadata request: %s %s", r.Method, r.URL.RequestURI())
		}
		body, err := io.ReadAll(r.Body)
		if err != nil || len(body) != 0 {
			t.Errorf("metadata body length=%d err=%v", len(body), err)
		}
		_, _ = io.WriteString(w, `{"response":{"version":"3.0.0","ignored":"admin-token-secret"}}`)
	})
	got, err := client.MetadataVersion(context.Background())
	if err != nil || got != "3.0.0" {
		t.Fatalf("version=%q err=%v", got, err)
	}
}

func TestMetadataVersionRejectsMalformedOrMissingVersion(t *testing.T) {
	for _, body := range []string{`{}`, `{"response":{}}`, `{"response":{"version":null}}`, `{"response":{"version":3}}`, `{"response":{"version":""}}`, `{"response":{"version":"v3.0.0"}}`, `{"response":{"version":"3.0"}}`, `{"response":{"version":"3.00.0"}}`, `{"response":{"version":"3.0.0-01"}}`, `{"response":{"version":"3.0.0 "}}`, `admin-token-secret`} {
		t.Run(body, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, body) })
			got, err := client.MetadataVersion(context.Background())
			if got != "" || !errors.Is(err, ErrInvalidResponse) || strings.Contains(err.Error(), testToken) {
				t.Fatalf("invalid metadata accepted or leaked: version=%q err=%v", got, err)
			}
		})
	}
}

func TestMetadataVersionAcceptsStrictSemverPrereleaseAndBuild(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"response":{"version":"3.0.0-rc.1+build.01"}}`)
	})
	got, err := client.MetadataVersion(context.Background())
	if err != nil || got != "3.0.0-rc.1+build.01" {
		t.Fatalf("valid semver rejected: %q %v", got, err)
	}
}

func TestMetadataVersionBoundsAndRedactsFailures(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		want   error
	}{
		{"outage", 502, testToken, ErrUnavailable}, {"authentication", 401, testToken, ErrPanelAuthentication},
		{"oversized", 200, strings.Repeat("x", maxResponseBytes+1), ErrInvalidResponse},
		{"redirect", 302, testToken, ErrUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", "/other")
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			})
			got, err := client.MetadataVersion(context.Background())
			if got != "" || !errors.Is(err, tc.want) || strings.Contains(err.Error(), testToken) {
				t.Fatalf("incorrect redacted failure: %q %v", got, err)
			}
		})
	}
}

func TestMetadataVersionCancellationBeforeNetwork(t *testing.T) {
	var calls atomic.Int32
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := client.MetadataVersion(ctx)
	if !errors.Is(err, context.Canceled) || calls.Load() != 0 {
		t.Fatalf("canceled metadata reached network: %v calls=%d", err, calls.Load())
	}
}
