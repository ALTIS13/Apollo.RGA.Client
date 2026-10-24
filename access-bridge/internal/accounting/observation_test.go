package accounting

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

const observationJSON = `{"response":{"id":42,"shortUuid":"subscription-secret","subscriptionUrl":"https://secret.invalid/sub","trojanPassword":"protocol-secret","status":"EXPIRED","expireAt":"2026-09-01T00:00:00Z","createdAt":"2026-08-01T00:00:00Z","lastTrafficResetAt":null,"trafficLimitBytes":1000,"trafficLimitStrategy":"MONTH","hwidDeviceLimit":2,"userTraffic":{"usedTrafficBytes":250}}}`

var startTime = time.Date(2026, 10, 2, 0, 0, 0, 0, time.UTC)
var endTime = time.Date(2026, 10, 2, 0, 0, 1, 0, time.UTC)

func panelClient(t *testing.T, handler http.HandlerFunc) *remnawave.Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client, err := remnawave.NewClient(server.URL, "admin-secret", server.Client())
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func clockSequence(values ...time.Time) func() time.Time {
	index := 0
	return func() time.Time {
		if index >= len(values) {
			panic("unexpected clock read")
		}
		value := values[index]
		index++
		return value
	}
}

func provider(t *testing.T, client *remnawave.Client, clock func() time.Time) *ObservationProvider {
	t.Helper()
	p, err := NewObservationProvider(client, "rga-main", "3.0.0", clock)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func simplePanel(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/api/system/metadata" {
		_, _ = io.WriteString(w, `{"response":{"version":"3.0.0"}}`)
		return
	}
	_, _ = io.WriteString(w, observationJSON)
}

// A dropped reset marker, caller identity, or manufactured qualified state must
// change this externally observable result, not merely source text.
func TestObserveReturnsServerOwnedRawFactsWithoutGrantingAccounting(t *testing.T) {
	var requests []string
	client := panelClient(t, func(w http.ResponseWriter, r *http.Request) {
		requests = append(requests, r.Method+" "+r.URL.RequestURI())
		if r.Header.Get("Authorization") != "Bearer admin-secret" {
			t.Error("authentication missing")
		}
		simplePanel(w, r)
	})
	got, err := provider(t, client, clockSequence(startTime, endTime)).Observe(context.Background(), 42)
	if err != nil {
		t.Fatal(err)
	}
	if got.Subject() != (access.Subject{PanelInstance: "rga-main", UserID: 42}) || got.PanelVersion() != "3.0.0" || !got.StartedAt().Equal(startTime) || !got.ObservedAt().Equal(endTime) {
		t.Fatalf("server provenance lost: subject=%v version=%q start=%v end=%v", got.Subject(), got.PanelVersion(), got.StartedAt(), got.ObservedAt())
	}
	if len(requests) != 2 || requests[0] != "GET /api/system/metadata" || requests[1] != "GET /api/users/42" {
		t.Fatalf("wrong authenticated observation sequence: %v", requests)
	}
	facts := got.Snapshot()
	if facts.CreatedAt == nil || !facts.CreatedAt.Equal(time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)) || !facts.LastTrafficResetAtPresent || facts.LastTrafficResetAt != nil || facts.ID != 42 || facts.Status != remnawave.StatusExpired || facts.TrafficLimitStrategy != remnawave.StrategyMonth || facts.TrafficLimitBytes != 1000 || facts.UsedTrafficBytes != 250 || !facts.ExpiresAt.Equal(time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)) {
		t.Fatalf("raw accounting/status facts changed: %+v", facts)
	}
	q := got.Qualification()
	if q.State != "UNQUALIFIED" || q.Reason != "cycle, usage lag, and active enforcement not verified" {
		t.Fatalf("raw observation is not explicitly unqualified: %+v", q)
	}
	encoded, err := json.Marshal(struct {
		Subject       access.Subject
		Snapshot      remnawave.UserSnapshot
		Version       string
		Qualification Qualification
	}{got.Subject(), facts, got.PanelVersion(), q})
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{"subscription-secret", "protocol-secret", "admin-secret", "secret.invalid", "shortUuid", "subscriptionUrl", "trojanPassword"} {
		if strings.Contains(string(encoded), secret) || strings.Contains(fmt.Sprintf("%+v", got), secret) {
			t.Fatalf("result leaked sensitive material %q", secret)
		}
	}
}

func TestObserveRequiresAccountingPresenceAndConsistentTimes(t *testing.T) {
	for _, tc := range []struct{ name, old, replacement string }{
		{"missing creation", `"createdAt":"2026-08-01T00:00:00Z",`, ""},
		{"missing reset marker", `"lastTrafficResetAt":null,`, ""},
		{"future creation", `"createdAt":"2026-08-01T00:00:00Z"`, `"createdAt":"2026-10-03T00:00:00Z"`},
		{"future reset", `"lastTrafficResetAt":null`, `"lastTrafficResetAt":"2026-10-03T00:00:00Z"`},
		{"reset before creation", `"lastTrafficResetAt":null`, `"lastTrafficResetAt":"2026-07-31T23:59:59Z"`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			body := strings.Replace(observationJSON, tc.old, tc.replacement, 1)
			client := panelClient(t, func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/api/system/metadata" {
					simplePanel(w, r)
					return
				}
				_, _ = io.WriteString(w, body)
			})
			_, err := provider(t, client, clockSequence(startTime, endTime)).Observe(context.Background(), 42)
			if !errors.Is(err, ErrInvalidObservation) {
				t.Fatalf("invalid accounting facts accepted: %v", err)
			}
		})
	}
}

func TestObserveDefensivelyCopiesSnapshotPointers(t *testing.T) {
	body := strings.Replace(observationJSON, `"lastTrafficResetAt":null`, `"lastTrafficResetAt":"2026-09-01T00:00:00Z"`, 1)
	client := panelClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/system/metadata" {
			simplePanel(w, r)
			return
		}
		_, _ = io.WriteString(w, body)
	})
	got, err := provider(t, client, clockSequence(startTime, endTime)).Observe(context.Background(), 42)
	if err != nil {
		t.Fatal(err)
	}
	copy := got.Snapshot()
	if copy.CreatedAt == nil || copy.LastTrafficResetAt == nil || copy.HWIDDeviceLimit == nil {
		t.Fatal("observation dropped snapshot pointers")
	}
	*copy.CreatedAt = endTime
	*copy.LastTrafficResetAt = endTime
	*copy.HWIDDeviceLimit = 999
	next := got.Snapshot()
	if !next.CreatedAt.Equal(time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)) || !next.LastTrafficResetAt.Equal(time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC)) || *next.HWIDDeviceLimit != 2 {
		t.Fatal("consumer mutation changed immutable observation")
	}
}

func TestObserveRejectsVersionDriftBeforeUserLookup(t *testing.T) {
	var calls atomic.Int32
	client := panelClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = io.WriteString(w, `{"response":{"version":"3.0.1"}}`)
	})
	_, err := provider(t, client, clockSequence(startTime)).Observe(context.Background(), 42)
	if !errors.Is(err, ErrVersionMismatch) || calls.Load() != 1 {
		t.Fatalf("version drift continued lookup: %v calls=%d", err, calls.Load())
	}
}

func TestObserveRejectsWrongNumericID(t *testing.T) {
	client := panelClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/system/metadata" {
			simplePanel(w, r)
			return
		}
		_, _ = io.WriteString(w, strings.Replace(observationJSON, `"id":42`, `"id":43`, 1))
	})
	_, err := provider(t, client, clockSequence(startTime)).Observe(context.Background(), 42)
	if !errors.Is(err, remnawave.ErrInvalidResponse) {
		t.Fatalf("mismatched canonical user accepted: %v", err)
	}
}

func TestObserveRejectsInvalidInputBeforeClockOrNetwork(t *testing.T) {
	var calls atomic.Int32
	client := panelClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	p := provider(t, client, func() time.Time { t.Error("invalid input reached server clock"); return startTime })
	for _, id := range []int64{0, -1} {
		if _, err := p.Observe(context.Background(), id); !errors.Is(err, ErrInvalidInput) {
			t.Errorf("invalid ID accepted: %v", err)
		}
	}
	if calls.Load() != 0 {
		t.Fatalf("invalid input reached network: %d", calls.Load())
	}
}

func TestObserveRejectsInvalidServerConfiguration(t *testing.T) {
	client := panelClient(t, simplePanel)
	for _, tc := range []struct {
		name, panel, version string
		client               *remnawave.Client
	}{
		{"nil client", "rga-main", "3.0.0", nil}, {"empty panel", "", "3.0.0", client}, {"URL panel", "https://secret.invalid", "3.0.0", client}, {"missing pin", "rga-main", "", client}, {"range pin", "rga-main", "^3.0.0", client}, {"malformed pin", "rga-main", "3.00.0", client},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := NewObservationProvider(tc.client, tc.panel, tc.version, nil); !errors.Is(err, ErrInvalidConfig) {
				t.Fatalf("invalid server configuration accepted: %v", err)
			}
		})
	}
}

func TestObserveRejectsBackwardsServerClock(t *testing.T) {
	_, err := provider(t, panelClient(t, simplePanel), clockSequence(endTime, startTime)).Observe(context.Background(), 42)
	if !errors.Is(err, ErrInvalidObservation) {
		t.Fatalf("backwards clock accepted: %v", err)
	}
}

func TestObserveDefaultsToServerClock(t *testing.T) {
	p := provider(t, panelClient(t, simplePanel), nil)
	before := time.Now()
	got, err := p.Observe(context.Background(), 42)
	after := time.Now()
	if err != nil || got.StartedAt().Before(before) || got.ObservedAt().After(after) || got.ObservedAt().Before(got.StartedAt()) {
		t.Fatalf("default server time provenance invalid: start=%v end=%v err=%v", got.StartedAt(), got.ObservedAt(), err)
	}
}

func TestObserveCancellationAndOutageHaveNoStaleFallback(t *testing.T) {
	t.Run("pre-canceled", func(t *testing.T) {
		var calls atomic.Int32
		p := provider(t, panelClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }), func() time.Time { t.Error("canceled request read clock"); return startTime })
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		if _, err := p.Observe(ctx, 42); !errors.Is(err, context.Canceled) || calls.Load() != 0 {
			t.Fatalf("canceled request reached panel: %v calls=%d", err, calls.Load())
		}
	})
	t.Run("canceled after metadata", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		p := provider(t, panelClient(t, func(w http.ResponseWriter, r *http.Request) {
			simplePanel(w, r)
			if r.URL.Path == "/api/system/metadata" {
				cancel()
			}
		}), clockSequence(startTime))
		if _, err := p.Observe(ctx, 42); !errors.Is(err, context.Canceled) {
			t.Fatalf("cancellation accepted: %v", err)
		}
	})
	t.Run("previous success cannot hide outage", func(t *testing.T) {
		var outage atomic.Bool
		p := provider(t, panelClient(t, func(w http.ResponseWriter, r *http.Request) {
			if outage.Load() {
				w.WriteHeader(502)
				_, _ = io.WriteString(w, "admin-secret subscription-secret")
				return
			}
			simplePanel(w, r)
		}), clockSequence(startTime, endTime, startTime))
		if _, err := p.Observe(context.Background(), 42); err != nil {
			t.Fatal(err)
		}
		outage.Store(true)
		got, err := p.Observe(context.Background(), 42)
		if !errors.Is(err, remnawave.ErrUnavailable) || got.Subject().UserID != 0 || strings.Contains(err.Error(), "secret") {
			t.Fatalf("outage leaked/fell back: subject=%v err=%v", got.Subject(), err)
		}
	})
}
