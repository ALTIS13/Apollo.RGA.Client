package remnawave

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testShortUUID = "short-uuid-secret"
const testToken = "admin-token-secret"

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
