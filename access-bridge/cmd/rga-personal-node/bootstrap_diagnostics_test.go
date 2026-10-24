package main

import (
	"crypto/sha256"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

// Catches the actual missing discriminator: independent request/binding/policy
// refusals all produce the same public error, but must remain distinguishable to
// the operator without retaining the request, installation or upstream error.
func TestBootstrapClosedSummaryLocatesRejectionWithoutChangingPublicResponse(t *testing.T) {
	cases := []struct {
		name, body, path, outcome string
		status                    int
		complete                  bool
		change                    func(*http.Request, *testLinkBeginner, *testBootstrapCompleter, *bootstrapRateGuard)
	}{
		{name: "begin", outcome: "begin_created", status: 200},
		{name: "boundary", outcome: "boundary", status: 400, change: func(r *http.Request, _ *testLinkBeginner, _ *testBootstrapCompleter, _ *bootstrapRateGuard) {
			r.TLS = nil
		}},
		{name: "body", body: `{"shortUuid":"private-bearer"}`, outcome: "begin_request", status: 400},
		{name: "installation", outcome: "installation", status: 400},
		{name: "public-key", body: `{"shortUuid":"private-bearer","installationId":"installation-A063-0001","publicKeyDer":"AA"}`, outcome: "public_key", status: 400},
		{name: "global-rate", outcome: "global_rate", status: 429, change: func(_ *http.Request, _ *testLinkBeginner, _ *testBootstrapCompleter, g *bootstrapRateGuard) {
			g.global = 0
		}},
		{name: "device-rate", outcome: "device_rate", status: 429, change: func(_ *http.Request, _ *testLinkBeginner, _ *testBootstrapCompleter, g *bootstrapRateGuard) {
			g.perInstallation = 0
		}},
		{name: "link-denied", outcome: "link_denied", status: 403, change: func(_ *http.Request, l *testLinkBeginner, _ *testBootstrapCompleter, _ *bootstrapRateGuard) {
			l.err = access.ErrLinkDenied
		}},
		{name: "link-upstream", outcome: "link_unavailable", status: 503, change: func(_ *http.Request, l *testLinkBeginner, _ *testBootstrapCompleter, _ *bootstrapRateGuard) {
			l.err = errors.New("private-upstream-token")
		}},
		{name: "path", path: "/private-path", outcome: "path", status: 404},
		{name: "complete", complete: true, outcome: "complete_prepared", status: 200},
		{name: "complete-body", complete: true, body: `{"shortUuid":"private-bearer"}`, outcome: "complete_request", status: 400},
		{name: "complete-key", complete: true, outcome: "public_key", status: 400, change: func(r *http.Request, _ *testLinkBeginner, _ *testBootstrapCompleter, _ *bootstrapRateGuard) {
			r.Body = httpBodyForSummary(`{"shortUuid":"private-bearer","challengeId":"challenge-A063-0001","publicKeyDer":"AA","signature":"AQID"}`)
		}},
		{name: "complete-signature", complete: true, outcome: "signature", status: 400, change: func(r *http.Request, _ *testLinkBeginner, _ *testBootstrapCompleter, _ *bootstrapRateGuard) {
			body, _ := io.ReadAll(r.Body)
			r.Body = httpBodyForSummary(strings.ReplaceAll(string(body), `"signature":"AQID"`, `"signature":"!"`))
		}},
		{name: "complete-rate", complete: true, outcome: "device_rate", status: 429, change: func(_ *http.Request, _ *testLinkBeginner, _ *testBootstrapCompleter, g *bootstrapRateGuard) {
			g.perInstallation = 0
		}},
		{name: "complete-denied", complete: true, outcome: "complete_denied", status: 403, change: func(_ *http.Request, _ *testLinkBeginner, c *testBootstrapCompleter, _ *bootstrapRateGuard) {
			c.err = personal.ErrBootstrapDenied
		}},
		{name: "complete-upstream", complete: true, outcome: "complete_unavailable", status: 503, change: func(_ *http.Request, _ *testLinkBeginner, c *testBootstrapCompleter, _ *bootstrapRateGuard) {
			c.err = errors.New("private-token")
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, link, complete, der := testBootstrapFixture(t, 20, 20)
			binding := access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: sha256.Sum256(der)}
			guard := newBootstrapRateGuard(time.Now, 20, 20)
			handler := newBootstrapHandler(binding, link, complete, guard)
			body := beginBody(der)
			if tc.complete {
				body = completeBody(der)
			}
			if tc.body != "" {
				body = tc.body
			}
			path := tc.path
			if path == "" {
				path = "/v1/personal/link/begin"
				if tc.complete {
					path = "/v1/personal/bootstrap"
				}
			}
			r := httptest.NewRequest(http.MethodPost, "https://127.0.0.1:18443"+path, strings.NewReader(body))
			r.RemoteAddr = "127.0.0.1:12345"
			r.Header.Set("Content-Type", "application/json")
			if tc.name == "installation" {
				r.Body = httpBodyForSummary(strings.ReplaceAll(body, binding.ID, "private-other-installation"))
			} else if tc.change != nil {
				tc.change(r, link, complete, guard)
			}
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatalf("public status=%d, want%d", w.Code, tc.status)
			}
			if w.Header().Get("Cache-Control") != "no-store" {
				t.Fatal("cache boundary changed")
			}
			if tc.status != 200 && w.Body.String() != "{\"error\":\"denied\"}\n" && w.Body.String() != "{\"error\":\"unavailable\"}\n" {
				t.Fatal("private failure exposed in public response")
			}
			checkBootstrapSummary(t, guard, tc.outcome, 1)
		})
	}
}

func TestBootstrapClosedSummaryInvalidManagedConfigCannotYieldMetadata(t *testing.T) {
	_, link, complete, der := testBootstrapFixture(t, 20, 20)
	guard := newBootstrapRateGuard(time.Now, 20, 20)
	h := newManagedProviderBootstrapHandlerWithBoundary(access.Subject{}, access.InstallationBinding{},
		"vkcalls", "https://vk.ru/call/join/private-locator", link, complete, guard, bootstrapRequestBoundary, time.Now)
	w := postBootstrap(t, h, "/v1/managed/reserve/bootstrap", completeBody(der))
	if w.Code != 503 || w.Body.String() != "{\"error\":\"unavailable\"}\n" {
		t.Fatal("invalid composition became available")
	}
	checkBootstrapSummary(t, guard, "config", 1)
}

func TestBootstrapClosedSummaryCountsConcurrentRejectedRequests(t *testing.T) {
	_, link, complete, der := testBootstrapFixture(t, 1000, 1000)
	guard := newBootstrapRateGuard(time.Now, 1000, 1000)
	handler := newBootstrapHandler(access.InstallationBinding{ID: "installation-A063-0001", PublicKeySHA256: sha256.Sum256(der)}, link, complete, guard)
	var joined sync.WaitGroup
	for i := 0; i < 64; i++ {
		joined.Add(1)
		go func() {
			defer joined.Done()
			postBootstrap(t, handler, "/v1/personal/link/begin", `{"shortUuid":"private-bearer"}`)
		}()
	}
	joined.Wait()
	checkBootstrapSummary(t, guard, "begin_request", 64)
}

func checkBootstrapSummary(t *testing.T, guard *bootstrapRateGuard, want string, count int) {
	t.Helper()
	projection, ok := any(guard).(interface{ bootstrapSummary() string })
	if !ok {
		t.Fatal("real bootstrap handler loses the closed rejection outcome")
	}
	summary := projection.bootstrapSummary()
	fields := strings.Fields(summary)
	if len(fields) < 2 || fields[0] != "rga_bootstrap_summary" {
		t.Fatal("missing closed summary")
	}
	total, found := 0, false
	for _, field := range fields[1:] {
		parts := strings.Split(field, "=")
		if len(parts) != 2 {
			t.Fatal("non-counter detail in summary")
		}
		n, err := strconv.Atoi(parts[1])
		if err != nil || n < 0 {
			t.Fatal("private/non-numeric diagnostic value")
		}
		total += n
		if parts[0] == want {
			found = n == count
		}
	}
	if !found || total != count {
		t.Fatalf("wrong refusal boundary or double count: %s", summary)
	}
	for _, secret := range []string{"private-", "installation-A063", "127.0.0.1", "http", "publicKeyDer"} {
		if strings.Contains(summary, secret) {
			t.Fatal("private request material retained")
		}
	}
}

func httpBodyForSummary(body string) io.ReadCloser { return io.NopCloser(strings.NewReader(body)) }
