package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

func TestPanelAuthModesPreserveBearerAndAddOnlyExplicitBrowserHeader(t *testing.T) {
	for _, tc := range []struct {
		name, mode, wantHeader string
	}{
		{"default API token", "", ""},
		{"explicit API token", "api_token", ""},
		{"browser session", "browser_session", "browser"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				if values := r.Header.Values("X-Remnawave-Client-Type"); len(values) != boolInt(tc.wantHeader != "") || tc.wantHeader != "" && values[0] != tc.wantHeader {
					t.Error("unexpected panel client-type header")
				}
				if r.Header.Get("Authorization") != "Bearer fake-local-token" || len(r.Cookies()) != 0 {
					t.Error("panel bearer or cookie boundary changed")
				}
				w.Header().Set("Content-Type", "application/json")
				switch r.URL.Path {
				case "/api/users/resolve":
					_, _ = io.WriteString(w, `{"response":{"id":42,"shortUuid":"local-bearer"}}`)
				case "/api/users/42":
					_, _ = io.WriteString(w, `{"response":{"id":42,"shortUuid":"local-bearer","status":"ACTIVE","expireAt":"2026-10-01T00:00:00Z","trafficLimitBytes":1000,"trafficLimitStrategy":"MONTH","userTraffic":{"usedTrafficBytes":0}}}`)
				default:
					t.Error("unexpected panel endpoint")
				}
			}))
			defer server.Close()
			client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: "fake-local-token", PanelAuthKind: tc.mode}, nil)
			if err != nil {
				t.Fatalf("newPanelClient: %v", err)
			}
			snapshot, err := client.Lookup(context.Background(), "local-bearer")
			if err != nil || snapshot.ID != 42 || calls != 2 {
				t.Fatalf("panel lookup failed or request count changed: err=%v calls=%d", err, calls)
			}
		})
	}
}

func TestBrowserSessionPanelClientNeverFollowsRedirect(t *testing.T) {
	redirected := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/redirect-target" {
			redirected = true
			return
		}
		w.Header().Set("Location", "/redirect-target")
		w.WriteHeader(http.StatusFound)
	}))
	defer server.Close()
	client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: "fake-local-token", PanelAuthKind: "browser_session"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.Lookup(context.Background(), "local-bearer")
	if !errors.Is(err, remnawave.ErrUnavailable) || redirected {
		t.Fatal("panel redirect was followed or was not fail-closed")
	}
}

func boolInt(value bool) int {
	if value {
		return 1
	}
	return 0
}
