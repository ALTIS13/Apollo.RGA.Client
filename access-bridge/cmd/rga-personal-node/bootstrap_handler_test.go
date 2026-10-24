package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

type testLinkBeginner struct {
	challenge access.LinkChallenge
	err       error
	calls     int
	gotShort  string
	gotID     string
	gotDER    []byte
}

func (f *testLinkBeginner) BeginLink(_ context.Context, short, id string, der []byte) (access.LinkChallenge, error) {
	f.calls++
	f.gotShort, f.gotID, f.gotDER = short, id, append([]byte(nil), der...)
	return f.challenge, f.err
}

type testBootstrapCompleter struct {
	result personal.BootstrapV1
	err    error
	calls  int
}

func (f *testBootstrapCompleter) CompleteLinkAndPrefetch(context.Context, string, string, []byte, []byte) (personal.BootstrapV1, error) {
	f.calls++
	return f.result, f.err
}

func testBootstrapFixture(t *testing.T, global, perInstallation int) (http.Handler, *testLinkBeginner, *testBootstrapCompleter, []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	fingerprint := sha256.Sum256(der)
	binding := access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: fingerprint}
	var nonce [32]byte
	nonce[0] = 0x5a
	link := &testLinkBeginner{challenge: access.LinkChallenge{
		ID: "challenge-A063-0001", Subject: access.Subject{PanelInstance: "a063", UserID: 63},
		Installation: binding, Nonce: nonce, ExpiresAt: time.Date(2026, 9, 29, 9, 10, 11, 0, time.UTC),
	}}
	complete := &testBootstrapCompleter{result: personal.BootstrapV1{
		SchemaVersion: 1, Provider: "telemost", Transport: "vp8channel", RoomID: "room_A063_01",
		Country: "NL", RoomKey: strings.Repeat("b", 64), PolicyRevision: 7, ExpiresAtEpochMs: 1790685600000,
	}}
	guard := newBootstrapRateGuard(time.Now, global, perInstallation)
	return newBootstrapHandler(binding, link, complete, guard), link, complete, der
}

func postBootstrap(t *testing.T, handler http.Handler, path, body string) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(http.MethodPost, "https://127.0.0.1:18443"+path, strings.NewReader(body))
	r.TLS = &tls.ConnectionState{}
	r.RemoteAddr = "127.0.0.1:12345"
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	return w
}

func beginBody(der []byte) string {
	return `{"shortUuid":"existing-bearer","installationId":"installation-A063-0001","publicKeyDer":"` + base64.RawURLEncoding.EncodeToString(der) + `"}`
}

func completeBody(der []byte) string {
	return `{"shortUuid":"existing-bearer","challengeId":"challenge-A063-0001","publicKeyDer":"` +
		base64.RawURLEncoding.EncodeToString(der) + `","signature":"` + base64.RawURLEncoding.EncodeToString([]byte{1, 2, 3}) + `"}`
}

func TestBeginBootstrapReturnsOnlyServerChallengeWithoutCaching(t *testing.T) {
	h, link, _, der := testBootstrapFixture(t, 30, 10)
	w := postBootstrap(t, h, "/v1/personal/link/begin", beginBody(der))
	if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("begin status/cache = %d/%q", w.Code, w.Header().Get("Cache-Control"))
	}
	var response struct {
		ChallengeID   string `json:"challengeId"`
		PanelInstance string `json:"panelInstance"`
		Nonce         string `json:"nonce"`
		ExpiresAt     string `json:"expiresAt"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.ChallengeID != "challenge-A063-0001" || response.PanelInstance != "a063" ||
		response.Nonce != "WgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" ||
		response.ExpiresAt != "2026-09-29T09:10:11Z" ||
		link.calls != 1 || link.gotShort != "existing-bearer" || link.gotID != "installation-A063-0001" || !bytes.Equal(link.gotDER, der) {
		t.Fatalf("begin challenge or service binding incorrect: %+v", response)
	}
	if bytes.Contains(w.Body.Bytes(), []byte("existing-bearer")) || bytes.Contains(w.Body.Bytes(), der) {
		t.Fatal("begin response leaked bearer or public key")
	}
}

func TestCompleteBootstrapReturnsNativePrefetchOnlyAfterTLSProof(t *testing.T) {
	h, _, complete, der := testBootstrapFixture(t, 30, 10)
	w := postBootstrap(t, h, "/v1/personal/bootstrap", completeBody(der))
	if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("bootstrap status/cache = %d/%q", w.Code, w.Header().Get("Cache-Control"))
	}
	var result personal.BootstrapV1
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if complete.calls != 1 || result.SchemaVersion != 1 || result.Provider != "telemost" ||
		result.Transport != "vp8channel" || result.RoomID != "room_A063_01" ||
		result.RoomKey != strings.Repeat("b", 64) || result.PolicyRevision != 7 {
		t.Fatal("bootstrap payload did not preserve the server result")
	}
	if bytes.Contains(w.Body.Bytes(), []byte("existing-bearer")) {
		t.Fatal("bootstrap response leaked subscription bearer")
	}
}

func TestBootstrapHTTPBoundaryRejectsPlaintextQueriesRedirectsAndUnboundedJSON(t *testing.T) {
	tests := []struct {
		name, path, body string
		changeReq        func(*http.Request)
	}{
		{"plaintext", "/v1/personal/link/begin", "{}", func(r *http.Request) { r.TLS = nil }},
		{"query", "/v1/personal/link/begin?shortUuid=leak", "{}", nil},
		{"trailing slash", "/v1/personal/link/begin/", "{}", nil},
		{"oversize", "/v1/personal/link/begin", strings.Repeat("x", 5000), nil},
		{"duplicate field", "/v1/personal/link/begin", "@duplicate", nil},
		{"unknown field", "/v1/personal/link/begin", "@unknown", nil},
		{"noncanonical base64", "/v1/personal/link/begin", `{"shortUuid":"x","installationId":"installation-A063-0001","publicKeyDer":"YQ=="}`, nil},
		{"foreign address", "/v1/personal/link/begin", "{}", func(r *http.Request) { r.RemoteAddr = "198.51.100.8:55" }},
		{"invalid content type", "/v1/personal/link/begin", "@valid", func(r *http.Request) { r.Header.Set("Content-Type", "application/json-attacker") }},
		{"foreign host", "/v1/personal/link/begin", "@valid", func(r *http.Request) { r.Host = "example.org" }},
		{"wrong method", "/v1/personal/link/begin", "@valid", func(r *http.Request) { r.Method = http.MethodGet }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			h, link, complete, der := testBootstrapFixture(t, 30, 10)
			body := tc.body
			switch body {
			case "@duplicate":
				body = strings.Replace(beginBody(der), `"shortUuid":`, `"shortUuid":"other", "shortUuid":`, 1)
			case "@unknown":
				body = strings.Replace(beginBody(der), `"shortUuid":`, `"roomId":"attacker-room", "shortUuid":`, 1)
			case "@valid":
				body = beginBody(der)
			}
			r := httptest.NewRequest(http.MethodPost, "https://127.0.0.1:18443"+tc.path, strings.NewReader(body))
			r.TLS = &tls.ConnectionState{}
			r.RemoteAddr = "127.0.0.1:12345"
			r.Header.Set("Content-Type", "application/json")
			if tc.changeReq != nil {
				tc.changeReq(r)
			}
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code >= 300 && w.Code < 400 || w.Code == http.StatusOK ||
				w.Header().Get("Cache-Control") != "no-store" || link.calls != 0 || complete.calls != 0 ||
				strings.Contains(w.Body.String(), "leak") || strings.Contains(w.Body.String(), "attacker-room") {
				t.Fatalf("unsafe boundary response: status=%d body=%q", w.Code, w.Body.String())
			}
		})
	}
}

func TestBootstrapRateGuardResetsWithoutLongLockout(t *testing.T) {
	now := time.Date(2026, 9, 29, 0, 0, 0, 0, time.UTC)
	guard := newBootstrapRateGuard(func() time.Time { return now }, 1, 1)
	if !guard.allow(false) || guard.allow(false) || !guard.allow(true) || guard.allow(true) {
		t.Fatal("initial fixed-window limits were not enforced")
	}
	now = now.Add(time.Minute)
	if !guard.allow(false) || !guard.allow(true) {
		t.Fatal("limiter did not reopen at the minute boundary")
	}
}

func TestBootstrapRateGuardConcurrentRequestsNeverExceedBudget(t *testing.T) {
	guard := newBootstrapRateGuard(time.Now, 10, 5)
	var allowed atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if guard.allow(false) {
				allowed.Add(1)
			}
		}()
	}
	wg.Wait()
	if got := allowed.Load(); got != 10 {
		t.Fatalf("concurrent global allowance = %d, want 10", got)
	}
}

func TestBootstrapErrorsAreGenericAndDoNotEchoServiceDetails(t *testing.T) {
	h, link, complete, der := testBootstrapFixture(t, 30, 10)
	link.err = access.ErrLinkDenied
	w := postBootstrap(t, h, "/v1/personal/link/begin", beginBody(der))
	if w.Code != http.StatusForbidden || w.Body.String() != "{\"error\":\"denied\"}\n" {
		t.Fatalf("denied response: %d %q", w.Code, w.Body.String())
	}
	link.err = errors.New("private-panel-token and private URL")
	w = postBootstrap(t, h, "/v1/personal/link/begin", beginBody(der))
	if w.Code != http.StatusServiceUnavailable || w.Body.String() != "{\"error\":\"unavailable\"}\n" {
		t.Fatalf("unavailable response: %d %q", w.Code, w.Body.String())
	}
	complete.err = personal.ErrBootstrapDenied
	w = postBootstrap(t, h, "/v1/personal/bootstrap", completeBody(der))
	if w.Code != http.StatusForbidden || w.Body.String() != "{\"error\":\"denied\"}\n" {
		t.Fatalf("bootstrap denied response: %d %q", w.Code, w.Body.String())
	}
}

func TestBootstrapRateGuardLimitsGlobalAndOneServerBoundInstallationWithoutXFF(t *testing.T) {
	h, link, _, der := testBootstrapFixture(t, 10, 1)
	w := postBootstrap(t, h, "/v1/personal/link/begin", beginBody(der))
	if w.Code != http.StatusOK {
		t.Fatalf("first attempt status = %d", w.Code)
	}
	r := httptest.NewRequest(http.MethodPost, "https://127.0.0.1:18443/v1/personal/link/begin", strings.NewReader(beginBody(der)))
	r.TLS = &tls.ConnectionState{}
	r.RemoteAddr = "127.0.0.1:12345"
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("X-Forwarded-For", "203.0.113.19")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusTooManyRequests || link.calls != 1 {
		t.Fatalf("per-installation rate guard ignored server-owned binding: %d calls=%d", w.Code, link.calls)
	}
	h, link, _, der = testBootstrapFixture(t, 1, 10)
	w = postBootstrap(t, h, "/v1/personal/link/begin", "invalid")
	if w.Code == http.StatusTooManyRequests {
		t.Fatal("first malformed request should consume but not exceed global budget")
	}
	w = postBootstrap(t, h, "/v1/personal/link/begin", beginBody(der))
	if w.Code != http.StatusTooManyRequests || link.calls != 0 {
		t.Fatalf("global rate guard did not bound malformed flood: %d calls=%d", w.Code, link.calls)
	}
}
