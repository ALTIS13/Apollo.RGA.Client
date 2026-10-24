package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

func testBrowserJWT(exp time.Time, suffix string) string {
	payload, _ := json.Marshal(map[string]any{"exp": exp.Unix()})
	return "eyJhbGciOiJub25lIn0." + base64.RawURLEncoding.EncodeToString(payload) + "." + suffix
}

func testPanelCredentials(t *testing.T, origin string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "credentials.json")
	entry, _ := json.Marshal(map[string]any{"Remnawave Panel": map[string]string{
		"Address": origin, "Login": "Alti", "Pass": "test-password",
	}})
	if err := os.WriteFile(path, append(entry, '\n'), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("RGA_PERSONAL_PANEL_CREDENTIALS_FILE", path)
}

func testPanelUser(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = fmt.Fprint(w, `{"response":{"id":42,"shortUuid":"local-bearer","status":"ACTIVE","expireAt":"2030-01-01T00:00:00Z","trafficLimitBytes":1000,"trafficLimitStrategy":"MONTH","userTraffic":{"usedTrafficBytes":0}}}`)
}

func TestBrowserPanelRefreshesExpiredJWTBeforeLookup(t *testing.T) {
	stale := testBrowserJWT(time.Now().Add(-time.Minute), "stale")
	fresh := testBrowserJWT(time.Now().Add(time.Hour), "fresh")
	var loginCalls, userCalls atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get(browserClientTypeHeader) != "browser" || len(r.Cookies()) != 0 {
			t.Error("browser request changed header or sent a cookie")
		}
		switch r.URL.Path {
		case "/api/auth/login":
			loginCalls.Add(1)
			if r.Method != http.MethodPost || r.Header.Get("Authorization") != "" {
				t.Error("login method or authorization header invalid")
			}
			var request map[string]string
			if json.NewDecoder(r.Body).Decode(&request) != nil || request["username"] != "Alti" || request["password"] != "test-password" {
				t.Error("login did not use protected credentials")
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]string{"accessToken": fresh}})
		case "/api/users/42":
			userCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer "+fresh {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			testPanelUser(w)
		default:
			t.Error("unexpected panel path")
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	testPanelCredentials(t, server.URL)
	client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: stale, PanelAuthKind: "browser_session"}, server.Client().Transport)
	if err != nil {
		t.Fatal(err)
	}
	user, err := client.LookupByID(context.Background(), 42)
	if err != nil || user.ID != 42 || loginCalls.Load() != 1 || userCalls.Load() != 1 {
		t.Fatalf("expired session was not refreshed before lookup: err=%v login=%d user=%d", err, loginCalls.Load(), userCalls.Load())
	}
}

func TestBrowserPanelRetriesOnceAfterUnauthorized(t *testing.T) {
	stale := testBrowserJWT(time.Now().Add(time.Hour), "stale")
	fresh := testBrowserJWT(time.Now().Add(time.Hour), "fresh")
	var loginCalls, userCalls atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/auth/login":
			loginCalls.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]string{"accessToken": fresh}})
		case "/api/users/42":
			userCalls.Add(1)
			if r.Header.Get("Authorization") != "Bearer "+fresh {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			testPanelUser(w)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	testPanelCredentials(t, server.URL)
	client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: stale, PanelAuthKind: "browser_session"}, server.Client().Transport)
	if err != nil {
		t.Fatal(err)
	}
	user, err := client.LookupByID(context.Background(), 42)
	if err != nil || user.ID != 42 || loginCalls.Load() != 1 || userCalls.Load() != 2 {
		t.Fatalf("401 did not get exactly one refreshed retry: err=%v login=%d user=%d", err, loginCalls.Load(), userCalls.Load())
	}
}

func TestBrowserPanelLoginRefusalFailsClosed(t *testing.T) {
	var userCalls atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		userCalls.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	testPanelCredentials(t, server.URL)
	client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: testBrowserJWT(time.Now().Add(-time.Minute), "stale"), PanelAuthKind: "browser_session"}, server.Client().Transport)
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.LookupByID(context.Background(), 42)
	if !errors.Is(err, remnawave.ErrPanelAuthentication) || userCalls.Load() != 0 {
		t.Fatalf("login refusal did not fail closed: err=%v userCalls=%d", err, userCalls.Load())
	}
}

func TestBrowserPanelLoginRedirectIsNotFollowed(t *testing.T) {
	var redirected atomic.Bool
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/redirect-target" {
			redirected.Store(true)
			return
		}
		w.Header().Set("Location", "/redirect-target")
		w.WriteHeader(http.StatusFound)
	}))
	defer server.Close()
	testPanelCredentials(t, server.URL)
	client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: testBrowserJWT(time.Now().Add(-time.Minute), "stale"), PanelAuthKind: "browser_session"}, server.Client().Transport)
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.LookupByID(context.Background(), 42)
	if !errors.Is(err, remnawave.ErrUnavailable) || redirected.Load() {
		t.Fatalf("login redirect escaped fail-closed boundary: err=%v redirected=%t", err, redirected.Load())
	}
}

func TestBrowserPanelConcurrentRefreshUsesOneLogin(t *testing.T) {
	stale := testBrowserJWT(time.Now().Add(-time.Minute), "stale")
	fresh := testBrowserJWT(time.Now().Add(time.Hour), "fresh")
	var loginCalls atomic.Int32
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/auth/login":
			loginCalls.Add(1)
			time.Sleep(40 * time.Millisecond)
			_ = json.NewEncoder(w).Encode(map[string]any{"response": map[string]string{"accessToken": fresh}})
		case "/api/users/42":
			if r.Header.Get("Authorization") != "Bearer "+fresh {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			testPanelUser(w)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()
	testPanelCredentials(t, server.URL)
	client, err := newPanelClient(node.Config{PanelOrigin: server.URL, PanelAdminToken: stale, PanelAuthKind: "browser_session"}, server.Client().Transport)
	if err != nil {
		t.Fatal(err)
	}
	const workers = 12
	var wg sync.WaitGroup
	bad := make(chan error, workers)
	for range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			user, lookupErr := client.LookupByID(context.Background(), 42)
			if lookupErr != nil || user.ID != 42 {
				bad <- fmt.Errorf("lookup failed: %v", lookupErr)
			}
		}()
	}
	wg.Wait()
	close(bad)
	for err := range bad {
		t.Error(err)
	}
	if loginCalls.Load() != 1 {
		t.Fatalf("concurrent refresh sent %d logins, want one", loginCalls.Load())
	}
}

func TestBrowserPanelCredentialOriginMismatchFailsBeforeNetwork(t *testing.T) {
	path := filepath.Join(t.TempDir(), "credentials.json")
	if err := os.WriteFile(path, []byte(`{"Remnawave Panel":{"Address":"https://other.example","Login":"Alti","Pass":"test-password"}}`), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("RGA_PERSONAL_PANEL_CREDENTIALS_FILE", path)
	client, err := newPanelClient(node.Config{PanelOrigin: "https://panel.example", PanelAdminToken: testBrowserJWT(time.Now().Add(-time.Minute), "stale"), PanelAuthKind: "browser_session"}, nil)
	if err == nil || client != nil || strings.Contains(err.Error(), "test-password") {
		t.Fatal("credential origin mismatch was accepted or leaked")
	}
}
