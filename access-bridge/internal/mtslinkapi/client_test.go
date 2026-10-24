package mtslinkapi

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const fixtureKey = "fixture-organizer-secret-not-a-real-key"

func testClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	s := httptest.NewServer(handler)
	t.Cleanup(s.Close)
	c, err := New(fixtureKey)
	if err != nil {
		t.Fatal(err)
	}
	c.base = s.URL
	return c
}

func TestCreateEventUsesMeetingContractWithoutCreatingSessionImplicitly(t *testing.T) {
	calls := 0
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Method != "POST" || r.URL.Path != "/events" || r.Header.Get("x-auth-token") != fixtureKey {
			t.Error("wrong operation or credential binding")
		}
		if err := r.ParseForm(); err != nil {
			t.Error(err)
		}
		want := map[string]string{"name": "Apollo.RGA QA fixture", "type": "meeting", "isEndless": "1", "accessSettings[isPasswordRequired]": "0", "accessSettings[isRegistrationRequired]": "0", "accessSettings[isModerationRequired]": "0", "defaultRemindersEnabled": "false"}
		for k, v := range want {
			if r.Form.Get(k) != v {
				t.Errorf("wrong field %s", k)
			}
		}
		if len(r.Form) != len(want) {
			t.Error("unexpected creation field")
		}
		w.WriteHeader(201)
		io.WriteString(w, `{"eventId":456,"link":"https://my.mts-link.ru/j/123/456"}`)
	})
	e, err := c.CreateEvent(context.Background(), "Apollo.RGA QA fixture")
	if err != nil || e.ID != 456 || e.Locator != "https://my.mts-link.ru/j/123/456" || calls != 1 {
		t.Fatalf("creation failed: %v", err)
	}
}

func TestSessionLifecycleUsesExactIDsAndSuppressesDeletionEmail(t *testing.T) {
	var operations []string
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		operations = append(operations, r.Method+" "+r.URL.RequestURI())
		if r.Method == "POST" {
			w.WriteHeader(201)
			io.WriteString(w, `{"eventSessionId":789,"link":"https://my.mts-link.ru/j/123/456/session/789"}`)
		} else {
			w.WriteHeader(204)
		}
	})
	s, err := c.CreateSession(context.Background(), 456)
	if err != nil || s.ID != 789 || s.EventID != 456 {
		t.Fatalf("session failed: %v", err)
	}
	if err = c.StopSession(context.Background(), s.ID); err != nil {
		t.Fatal(err)
	}
	if err = c.DeleteSession(context.Background(), s.ID); err != nil {
		t.Fatal(err)
	}
	want := "POST /events/456/sessions|PUT /eventsessions/789/stop|DELETE /eventsessions/789?sendEmail=false"
	if strings.Join(operations, "|") != want {
		t.Fatal("wrong lifecycle targets")
	}
}

// Partial provisioning must reclaim only the exact Event created by that attempt.
func TestDeleteEventUsesExactOwnedContainerAndRejectsInvalidID(t *testing.T) {
	calls := 0
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Method != "DELETE" || r.URL.RequestURI() != "/organization/events/456" || r.Header.Get("x-auth-token") != fixtureKey {
			t.Error("event cleanup targeted the wrong object")
		}
		w.WriteHeader(204)
	})
	for _, id := range []int64{0, -1} {
		if err := c.DeleteEvent(context.Background(), id); err == nil {
			t.Error("invalid container ID accepted")
		}
	}
	if err := c.DeleteEvent(context.Background(), 456); err != nil || calls != 1 {
		t.Fatalf("exact event cleanup failed: %v (requests=%d)", err, calls)
	}
}

func TestReturnedEventIDSurvivesMalformedLocatorForCallerCustody(t *testing.T) {
	for _, locator := range []string{"https://evil.invalid/j/123/456", "https://my.mts-link.ru/j/123/999", "http://my.mts-link.ru/j/123/456", "https://my.mts-link.ru/j/123/456?key=secret"} {
		c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(201)
			fmt.Fprintf(w, `{"eventId":456,"link":%q}`, locator)
		})
		e, err := c.CreateEvent(context.Background(), "QA")
		if err == nil || e.ID != 456 || e.Locator != "" {
			t.Error("lost partial creation ID or accepted unsafe locator")
		}
	}
}

func TestSessionLocatorCannotSubstituteAnotherEventOrSession(t *testing.T) {
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(201)
		io.WriteString(w, `{"eventSessionId":789,"link":"https://my.mts-link.ru/j/123/999/session/321"}`)
	})
	s, err := c.CreateSession(context.Background(), 456)
	if err == nil || s.ID != 789 || s.EventID != 456 || s.Locator != "" {
		t.Fatal("session binding or partial custody lost")
	}
}

func TestMalformedLocatorFieldNeverLosesCreatedID(t *testing.T) {
	for _, link := range []string{`7`, `{}`, `[]`, `false`, `null`} {
		c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(201)
			if r.URL.Path == "/events" {
				io.WriteString(w, `{"eventId":456,"link":`+link+`}`)
			} else {
				io.WriteString(w, `{"eventSessionId":789,"link":`+link+`}`)
			}
		})
		event, err := c.CreateEvent(context.Background(), "QA")
		if err == nil || event.ID != 456 || event.Locator != "" {
			t.Error("malformed link lost EventID or became publishable")
		}
		session, err := c.CreateSession(context.Background(), 456)
		if err == nil || session.ID != 789 || session.EventID != 456 || session.Locator != "" {
			t.Error("malformed link lost SessionID or became publishable")
		}
	}
}

func TestRedirectNeverTransmitsOrganizerCredentialToAnotherServer(t *testing.T) {
	forwarded := 0
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { forwarded++ }))
	defer other.Close()
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
	})
	if _, err := c.CreateEvent(context.Background(), "QA"); err == nil || forwarded != 0 {
		t.Fatal("redirect followed or accepted")
	}
}

func TestMutatingFailureIsNotRetriedAndReplySecretsAreNotInErrors(t *testing.T) {
	for _, status := range []int{400, 401, 403, 429, 500, 503} {
		calls := 0
		c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
			calls++
			w.WriteHeader(status)
			io.WriteString(w, fixtureKey)
		})
		_, err := c.CreateEvent(context.Background(), "QA")
		if err == nil || calls != 1 || strings.Contains(err.Error(), fixtureKey) {
			t.Error("mutation retried or secret exposed")
		}
	}
}

func TestMalformedOversizeAndTrailingJSONNeverBecomeCreatedRoom(t *testing.T) {
	for _, body := range []string{`{`, `{"eventId":0,"link":""}`, `{"eventId":456,"link":"https://my.mts-link.ru/j/123/456"} {}`, strings.Repeat("x", 65537)} {
		c := testClient(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(201); io.WriteString(w, body) })
		if _, err := c.CreateEvent(context.Background(), "QA"); err == nil {
			t.Error("invalid reply accepted")
		}
	}
}

func TestInvalidInputAndCancelledContextDoNotReachService(t *testing.T) {
	calls := 0
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) { calls++; w.WriteHeader(201) })
	for _, id := range []int64{0, -1} {
		if _, err := c.CreateSession(context.Background(), id); err == nil {
			t.Error("invalid ID accepted")
		}
		if err := c.StopSession(context.Background(), id); err == nil {
			t.Error("invalid ID accepted")
		}
		if err := c.DeleteSession(context.Background(), id); err == nil {
			t.Error("invalid ID accepted")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.CreateEvent(ctx, "QA"); err == nil {
		t.Error("cancel ignored")
	}
	if _, err := c.CreateEvent(context.Background(), ""); err == nil {
		t.Error("empty name accepted")
	}
	if calls != 0 {
		t.Error("invalid request reached service")
	}
}

func TestClientFormattingAndConfigurationDoNotExposeCredentials(t *testing.T) {
	for _, key := range []string{"", "a\r\nb", " secret ", strings.Repeat("x", 4097)} {
		if _, err := New(key); err == nil {
			t.Error("invalid credential accepted")
		}
	}
	c, err := New(fixtureKey)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(fmt.Sprintf("%v %#v", c, c), fixtureKey) {
		t.Fatal("client formatting exposed credential")
	}
}

func TestSharedClientSpacesRequestsAndCancelledWaitDoesNotSend(t *testing.T) {
	var starts []time.Time
	c := testClient(t, func(w http.ResponseWriter, r *http.Request) {
		starts = append(starts, time.Now())
		w.WriteHeader(201)
		io.WriteString(w, `{"eventId":456,"link":"https://my.mts-link.ru/j/123/456"}`)
	})
	if _, err := c.CreateEvent(context.Background(), "QA"); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := c.CreateEvent(ctx, "QA"); err == nil {
		t.Error("cancelled pacing wait reached service")
	}
	if _, err := c.CreateEvent(context.Background(), "QA"); err != nil {
		t.Fatal(err)
	}
	if len(starts) != 2 || starts[1].Sub(starts[0]) < 500*time.Millisecond {
		t.Fatal("requests exceeded documented two per second or cancelled wait was sent")
	}
}
