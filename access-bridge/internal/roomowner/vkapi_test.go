package roomowner

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type fixtureTokens struct {
	value OrganizerToken
	err   error
}

func (s fixtureTokens) Load(context.Context, string) (OrganizerToken, error) { return s.value, s.err }

func fixtureVK(t *testing.T, h http.HandlerFunc) (*VKProvider, *httptest.Server) {
	t.Helper()
	server := httptest.NewTLSServer(h)
	t.Cleanup(server.Close)
	p, err := NewVKProvider("12345", 99, fixtureTokens{value: OrganizerToken{
		Reference: "organizer-v1", AppID: "12345", OrganizerID: 99, AccessToken: "fixture-private-token", NotAfter: time.Now().Add(time.Hour)}})
	if err != nil {
		t.Fatal(err)
	}
	// Local TLS fixture only; public construction has no endpoint override.
	p.endpoint = server.URL + "/method"
	p.client.Transport = server.Client().Transport
	return p, server
}

func TestVKAPIRetainsCallIDAndUsesExactFinishBody(t *testing.T) {
	var count atomic.Int32
	p, _ := fixtureVK(t, func(w http.ResponseWriter, r *http.Request) {
		count.Add(1)
		if r.Method != "POST" || r.URL.RawQuery != "" || r.ParseForm() != nil || r.PostForm.Get("access_token") != "fixture-private-token" || r.PostForm.Get("v") != "5.199" {
			t.Error("credential not confined to expected POST")
		}
		switch r.URL.Path {
		case "/method/calls.start":
			fmt.Fprintf(w, `{"response":{"call_id":%q,"join_link":%q,"ok_join_link":"https://ok.invalid/fixture"}}`, fixtureCall, fixtureLocator)
		case "/method/calls.forceFinish":
			if r.PostForm.Get("call_id") != fixtureCall {
				t.Error("wrong provider call ID")
			}
			fmt.Fprint(w, `{"response":1}`)
		default:
			t.Error("unexpected method")
			http.NotFound(w, r)
		}
	})
	room, err := p.Create(context.Background(), "organizer-v1")
	if err != nil || room.CallID != fixtureCall || room.Locator != fixtureLocator {
		t.Fatalf("created room lost required identity: %v", err)
	}
	if err := p.Finish(context.Background(), "organizer-v1", room.CallID); err != nil {
		t.Fatal(err)
	}
	if count.Load() != 2 {
		t.Fatal("unexpected retry or missing operation")
	}
}

func TestVKAPIAmbiguousResponseNeverRetriesOrLeaksProviderText(t *testing.T) {
	for _, body := range []string{`{`, `{"response":{"join_link":"https://vk.ru/call/join/fixture"}}`, `{"error":{"error_code":5,"error_msg":"fixture-secret"}}`, `{"response":{},"error":{"error_code":5}}`} {
		t.Run(fmt.Sprint(len(body)), func(t *testing.T) {
			var calls atomic.Int32
			p, _ := fixtureVK(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); fmt.Fprint(w, body) })
			_, err := p.Create(context.Background(), "organizer-v1")
			if err == nil || strings.Contains(err.Error(), "fixture-secret") || calls.Load() != 1 {
				t.Fatal("ambiguous/rejected result retried or leaked")
			}
			if !errors.Is(err, ErrCreationUnknown) {
				t.Fatal("unqualified provider error misclassified as definitive no-room rejection")
			}
		})
	}
}

func TestVKAPIBlocksRedirectAndUnpinnedCredentials(t *testing.T) {
	var redirected atomic.Int32
	target := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected.Add(1) }))
	defer target.Close()
	p, _ := fixtureVK(t, func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusTemporaryRedirect)
	})
	if _, err := p.Create(context.Background(), "organizer-v1"); err == nil || redirected.Load() != 0 {
		t.Fatal("redirect received credential")
	}
	for _, change := range []func(*OrganizerToken){
		func(c *OrganizerToken) { c.AppID = "98765" }, func(c *OrganizerToken) { c.OrganizerID = 100 },
		func(c *OrganizerToken) { c.Reference = "organizer-v2" }, func(c *OrganizerToken) { c.NotAfter = time.Now().Add(-time.Second) },
	} {
		var requests atomic.Int32
		p, _ := fixtureVK(t, func(w http.ResponseWriter, r *http.Request) { requests.Add(1) })
		s := p.tokens.(fixtureTokens)
		change(&s.value)
		p.tokens = s
		if _, err := p.Create(context.Background(), "organizer-v1"); err == nil || requests.Load() != 0 {
			t.Fatal("unqualified token used")
		}
	}
}

func TestVKAPIFinishNeedsExplicitACKAndValidOwnedID(t *testing.T) {
	var requests atomic.Int32
	p, _ := fixtureVK(t, func(w http.ResponseWriter, r *http.Request) { requests.Add(1); fmt.Fprint(w, `{"response":0}`) })
	if err := p.Finish(context.Background(), "organizer-v1", "not-a-call-id"); err == nil || requests.Load() != 0 {
		t.Fatal("invalid ID sent")
	}
	if err := p.Finish(context.Background(), "organizer-v1", fixtureCall); err == nil || requests.Load() != 1 {
		t.Fatal("non-ACK reported finished")
	}
}
