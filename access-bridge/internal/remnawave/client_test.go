package remnawave

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const testShortUUID = "short-uuid-secret"
const testToken = "admin-token-secret"
const canonicalUserJSON = `{"response":{"id":42,"shortUuid":"rotated-short-uuid","status":"ACTIVE","expireAt":"2026-10-01T00:00:00Z","trafficLimitBytes":1000,"trafficLimitStrategy":"MONTH","hwidDeviceLimit":2,"userTraffic":{"usedTrafficBytes":250}}}`

func testClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client, err := NewClient(server.URL, testToken, server.Client())
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	return client
}

func writeResolve(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, `{"response":{"id":42,"shortUuid":"short-uuid-secret","username":"test"}}`)
}

func writeUser(w http.ResponseWriter, status, traffic string) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, fmt.Sprintf(`{"response":{"id":42,"shortUuid":"short-uuid-secret","status":%q,"expireAt":"2026-10-01T00:00:00Z","trafficLimitBytes":1000,"trafficLimitStrategy":"MONTH","hwidDeviceLimit":2,%s}}`, status, traffic))
}

func TestLookupRejectsRotatedShortUUIDBetweenResolveAndUser(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/users/resolve" {
			writeResolve(w)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"response":{"id":42,"shortUuid":"rotated-short-uuid","status":"ACTIVE","expireAt":"2026-10-01T00:00:00Z","trafficLimitBytes":1000,"trafficLimitStrategy":"MONTH","userTraffic":{"usedTrafficBytes":250}}}`)
	})
	_, err := client.Lookup(context.Background(), testShortUUID)
	if !errors.Is(err, ErrInvalidResponse) {
		t.Fatalf("rotated subscription bearer should not link, got %v", err)
	}
}

func TestLookupResolvesThenReadsUserWithoutBearerInPath(t *testing.T) {
	var requests []string
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		requests = append(requests, r.Method+" "+r.URL.RequestURI())
		if r.Header.Get("Authorization") != "Bearer "+testToken {
			t.Error("authorization header missing")
		}
		switch r.URL.Path {
		case "/api/users/resolve":
			if r.Method != http.MethodPost {
				t.Errorf("resolve method: %s", r.Method)
			}
			body, err := io.ReadAll(r.Body)
			if err != nil {
				t.Fatal(err)
			}
			if string(body) != `{"shortUuid":"short-uuid-secret"}` {
				t.Errorf("resolve body: %q", body)
			}
			writeResolve(w)
		case "/api/users/42":
			if r.Method != http.MethodGet {
				t.Errorf("user method: %s", r.Method)
			}
			writeUser(w, "ACTIVE", `"userTraffic":{"usedTrafficBytes":250}`)
		default:
			t.Errorf("unexpected endpoint: %s", r.URL.Path)
			http.NotFound(w, r)
		}
	})

	snapshot, err := client.Lookup(context.Background(), testShortUUID)
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if snapshot.ID != 42 || snapshot.Status != StatusActive || snapshot.TrafficLimitBytes != 1000 || snapshot.UsedTrafficBytes != 250 || snapshot.TrafficLimitStrategy != StrategyMonth || snapshot.HWIDDeviceLimit == nil || *snapshot.HWIDDeviceLimit != 2 || !snapshot.ExpiresAt.Equal(time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)) {
		t.Errorf("unexpected redacted snapshot: %+v", snapshot)
	}
	if len(requests) != 2 || requests[0] != "POST /api/users/resolve" || requests[1] != "GET /api/users/42" {
		t.Errorf("request sequence: %v", requests)
	}
	for _, request := range requests {
		if strings.Contains(request, testShortUUID) || strings.Contains(request, testToken) {
			t.Errorf("bearer in request path: %q", request)
		}
	}
}

func TestLookupRejectsUnknownStatus(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/users/resolve" {
			writeResolve(w)
			return
		}
		writeUser(w, "FROZEN", `"userTraffic":{"usedTrafficBytes":250}`)
	})
	_, err := client.Lookup(context.Background(), testShortUUID)
	if !errors.Is(err, ErrInvalidResponse) {
		t.Fatalf("unknown status should fail closed, got %v", err)
	}
}

func TestLookupRejectsMissingUsage(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/users/resolve" {
			writeResolve(w)
			return
		}
		writeUser(w, "ACTIVE", `"otherField":true`)
	})
	_, err := client.Lookup(context.Background(), testShortUUID)
	if !errors.Is(err, ErrInvalidResponse) {
		t.Fatalf("missing usage should fail closed, got %v", err)
	}
}

func TestLookupRejectsNonSuccessWithoutLeakingResponse(t *testing.T) {
	for _, failedStep := range []string{"resolve", "user"} {
		t.Run(failedStep, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/users/resolve" && failedStep == "user" {
					writeResolve(w)
					return
				}
				w.WriteHeader(http.StatusBadGateway)
				_, _ = io.WriteString(w, testToken+" "+testShortUUID)
			})
			_, err := client.Lookup(context.Background(), testShortUUID)
			if !errors.Is(err, ErrUnavailable) {
				t.Fatalf("non-success should be unavailable, got %v", err)
			}
			if strings.Contains(err.Error(), testToken) || strings.Contains(err.Error(), testShortUUID) {
				t.Fatalf("error leaked credentials: %v", err)
			}
		})
	}
}

func TestLookupDistinguishesMissingUserAndPanelAuthenticationFromOutage(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		want   error
	}{
		{name: "missing user", status: http.StatusNotFound, want: ErrNotFound},
		{name: "invalid admin token", status: http.StatusUnauthorized, want: ErrPanelAuthentication},
		{name: "admin access forbidden", status: http.StatusForbidden, want: ErrPanelAuthentication},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, testToken+" "+testShortUUID)
			})
			_, err := client.Lookup(context.Background(), testShortUUID)
			if !errors.Is(err, tc.want) || strings.Contains(err.Error(), testToken) || strings.Contains(err.Error(), testShortUUID) {
				t.Fatalf("wrong redacted error for status %d: %v", tc.status, err)
			}
		})
	}
}

func TestLookupRejectsOversizedResponse(t *testing.T) {
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, strings.Repeat("x", maxResponseBytes+1))
	})
	_, err := client.Lookup(context.Background(), testShortUUID)
	if !errors.Is(err, ErrInvalidResponse) {
		t.Fatalf("oversized response should be invalid, got %v", err)
	}
}

func TestLookupHonorsCancellationBeforeNetwork(t *testing.T) {
	var calls int
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		writeResolve(w)
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := client.Lookup(ctx, testShortUUID)
	if !errors.Is(err, context.Canceled) || calls != 0 {
		t.Fatalf("canceled lookup should make no requests: err=%v calls=%d", err, calls)
	}
}

func TestLookupSanitizesTransportErrors(t *testing.T) {
	transport := roundTripper(func(*http.Request) (*http.Response, error) {
		return nil, errors.New(testToken + " " + testShortUUID)
	})
	client, err := NewClient("https://panel.example.test", testToken, &http.Client{Transport: transport})
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.Lookup(context.Background(), testShortUUID)
	if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), testToken) || strings.Contains(err.Error(), testShortUUID) {
		t.Fatalf("transport error should be sanitized: %v", err)
	}
}

func TestLookupByIDReadsCanonicalUserWithOneAuthenticatedGET(t *testing.T) {
	var calls atomic.Int32
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Method != http.MethodGet || r.URL.RequestURI() != "/api/users/42" || r.Header.Get("Authorization") != "Bearer "+testToken {
			t.Errorf("unexpected canonical request: %s %s", r.Method, r.URL.RequestURI())
		}
		body, err := io.ReadAll(r.Body)
		if err != nil || len(body) != 0 {
			t.Errorf("canonical refresh sent a bearer/body: length=%d err=%v", len(body), err)
		}
		_, _ = io.WriteString(w, canonicalUserJSON)
	})
	snapshot, err := client.LookupByID(context.Background(), 42)
	if err != nil {
		t.Fatalf("LookupByID: %v", err)
	}
	if calls.Load() != 1 || snapshot.ID != 42 || snapshot.Status != StatusActive ||
		snapshot.TrafficLimitBytes != 1000 || snapshot.UsedTrafficBytes != 250 ||
		snapshot.TrafficLimitStrategy != StrategyMonth || snapshot.HWIDDeviceLimit == nil ||
		*snapshot.HWIDDeviceLimit != 2 ||
		!snapshot.ExpiresAt.Equal(time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)) {
		t.Fatalf("canonical refresh mismatch: calls=%d snapshot=%+v", calls.Load(), snapshot)
	}
}

func TestLookupByIDRejectsInvalidIDBeforeNetwork(t *testing.T) {
	var calls atomic.Int32
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = io.WriteString(w, canonicalUserJSON)
	})
	for _, id := range []int64{0, -1} {
		if _, err := client.LookupByID(context.Background(), id); !errors.Is(err, ErrInvalidConfig) {
			t.Errorf("invalid ID %d returned %v", id, err)
		}
	}
	if calls.Load() != 0 {
		t.Fatalf("invalid ID reached panel: calls=%d", calls.Load())
	}
}

func TestLookupByIDRejectsMismatchedOrIncompleteUser(t *testing.T) {
	for _, tc := range []struct {
		name, body string
	}{
		{"mismatched ID", strings.Replace(canonicalUserJSON, `"id":42`, `"id":43`, 1)},
		{"missing ID", strings.Replace(canonicalUserJSON, `"id":42,`, "", 1)},
		{"unknown status", strings.Replace(canonicalUserJSON, `"status":"ACTIVE"`, `"status":"FROZEN"`, 1)},
		{"missing expiry", strings.Replace(canonicalUserJSON, `"expireAt":"2026-10-01T00:00:00Z",`, "", 1)},
		{"missing limit", strings.Replace(canonicalUserJSON, `"trafficLimitBytes":1000,`, "", 1)},
		{"unknown strategy", strings.Replace(canonicalUserJSON, `"trafficLimitStrategy":"MONTH"`, `"trafficLimitStrategy":"UNKNOWN"`, 1)},
		{"missing usage", strings.Replace(canonicalUserJSON, `"userTraffic":{"usedTrafficBytes":250}`, `"userTraffic":{}`, 1)},
		{"negative usage", strings.Replace(canonicalUserJSON, `"usedTrafficBytes":250`, `"usedTrafficBytes":-1`, 1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.WriteString(w, tc.body)
			})
			if _, err := client.LookupByID(context.Background(), 42); !errors.Is(err, ErrInvalidResponse) {
				t.Fatalf("invalid canonical user returned %v", err)
			}
		})
	}
}

func TestLookupByIDRedactsHTTPAndTransportFailures(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		want   error
	}{
		{"not found", http.StatusNotFound, ErrNotFound},
		{"unauthorized", http.StatusUnauthorized, ErrPanelAuthentication},
		{"forbidden", http.StatusForbidden, ErrPanelAuthentication},
		{"upstream failure", http.StatusBadGateway, ErrUnavailable},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, testToken+" "+testShortUUID)
			})
			_, err := client.LookupByID(context.Background(), 42)
			if !errors.Is(err, tc.want) || strings.Contains(err.Error(), testToken) || strings.Contains(err.Error(), testShortUUID) {
				t.Fatalf("HTTP failure was not redacted: %v", err)
			}
		})
	}
	transport := roundTripper(func(*http.Request) (*http.Response, error) {
		return nil, errors.New(testToken + " " + testShortUUID)
	})
	client, err := NewClient("https://panel.example.test", testToken, &http.Client{Transport: transport})
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.LookupByID(context.Background(), 42)
	if !errors.Is(err, ErrUnavailable) || strings.Contains(err.Error(), testToken) || strings.Contains(err.Error(), testShortUUID) {
		t.Fatalf("transport failure was not redacted: %v", err)
	}
}

func TestLookupByIDRejectsOversizedResponseAndRedirect(t *testing.T) {
	t.Run("oversized", func(t *testing.T) {
		client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
			_, _ = io.WriteString(w, strings.Repeat("x", maxResponseBytes+1))
		})
		if _, err := client.LookupByID(context.Background(), 42); !errors.Is(err, ErrInvalidResponse) {
			t.Fatalf("oversized canonical response returned %v", err)
		}
	})
	t.Run("redirect", func(t *testing.T) {
		var redirects atomic.Int32
		client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/redirect-target" {
				redirects.Add(1)
				_, _ = io.WriteString(w, canonicalUserJSON)
				return
			}
			http.Redirect(w, r, "/redirect-target", http.StatusFound)
		})
		if _, err := client.LookupByID(context.Background(), 42); !errors.Is(err, ErrUnavailable) || redirects.Load() != 0 {
			t.Fatalf("canonical refresh followed redirect: err=%v redirects=%d", err, redirects.Load())
		}
	})
}

func TestLookupByIDHonorsCancellationAndBoundedTimeout(t *testing.T) {
	var calls atomic.Int32
	client := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = io.WriteString(w, canonicalUserJSON)
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := client.LookupByID(ctx, 42); !errors.Is(err, context.Canceled) || calls.Load() != 0 {
		t.Fatalf("canceled refresh reached panel: err=%v calls=%d", err, calls.Load())
	}
	transport := roundTripper(func(r *http.Request) (*http.Response, error) {
		<-r.Context().Done()
		return nil, r.Context().Err()
	})
	client, err := NewClient("https://panel.example.test", testToken, &http.Client{Transport: transport, Timeout: 15 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	deadlineCtx, stop := context.WithTimeout(context.Background(), 250*time.Millisecond)
	defer stop()
	_, err = client.LookupByID(deadlineCtx, 42)
	if !errors.Is(err, ErrUnavailable) || deadlineCtx.Err() != nil {
		t.Fatalf("client timeout did not precede caller deadline: err=%v caller=%v", err, deadlineCtx.Err())
	}
}

type roundTripper func(*http.Request) (*http.Response, error)

func (f roundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestNewClientRequiresTLSOutsideLoopback(t *testing.T) {
	for _, origin := range []string{"http://panel.example.test", "https://user:password@panel.example.test", "https://panel.example.test/path", "https://panel.example.test?token=secret"} {
		if _, err := NewClient(origin, testToken, nil); !errors.Is(err, ErrInvalidConfig) {
			t.Errorf("unsafe origin accepted: %q, %v", origin, err)
		}
	}
	if _, err := NewClient("https://panel.example.test", testToken, nil); err != nil {
		t.Errorf("HTTPS origin rejected: %v", err)
	}
}

func TestNewClientBoundsNegativeTimeout(t *testing.T) {
	provided := &http.Client{Timeout: -time.Second}
	client, err := NewClient("https://panel.example.test", testToken, provided)
	if err != nil {
		t.Fatal(err)
	}
	if client.http.Timeout <= 0 || client.http.Timeout > requestTimeout {
		t.Fatalf("negative supplied timeout left requests unbounded: %v", client.http.Timeout)
	}
	if provided.Timeout != -time.Second {
		t.Fatalf("NewClient mutated caller's HTTP client: %v", provided.Timeout)
	}
}
