// Package mtslinkapi is the bounded organizer API, not subscription admission.
// Callers must durably record intent before each mutation and retain returned
// IDs before the next operation. No method retries a mutation or adopts a room.
package mtslinkapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	ErrConfig    = errors.New("mtslink organizer: invalid configuration")
	ErrInput     = errors.New("mtslink organizer: invalid input")
	ErrTransport = errors.New("mtslink organizer: transport result unknown; no retry")
	ErrReply     = errors.New("mtslink organizer: invalid reply; no retry")
	ErrStatus    = errors.New("mtslink organizer: unexpected status; no retry")
	joinPath     = regexp.MustCompile(`^/j/([1-9][0-9]{0,19})/([1-9][0-9]{0,19})(?:/session/([1-9][0-9]{0,19}))?$`)
)

type Client struct {
	base  string
	token string
	http  *http.Client
	gate  chan struct{}
	next  time.Time // protected by gate; share one Client per organizer credential.
}

func New(token string) (*Client, error) {
	if token == "" || token != strings.TrimSpace(token) || strings.ContainsAny(token, "\r\n") || len(token) > 4096 {
		return nil, ErrConfig
	}
	return &Client{base: "https://userapi.mts-link.ru/v3", token: token, gate: make(chan struct{}, 1),
		http: &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

func (Client) String() string   { return "mtslink organizer [private]" }
func (Client) GoString() string { return "mtslink organizer [private]" }

type Event struct {
	ID      int64
	Locator string
}
type Session struct {
	ID, EventID int64
	Locator     string
}

func (Event) String() string     { return "mtslink event [private]" }
func (Event) GoString() string   { return "mtslink event [private]" }
func (Session) String() string   { return "mtslink session [private]" }
func (Session) GoString() string { return "mtslink session [private]" }

func (c *Client) request(ctx context.Context, method, path string, form url.Values, want int) ([]byte, error) {
	if c == nil || c.http == nil || c.token == "" || c.gate == nil {
		return nil, ErrConfig
	}
	if ctx == nil || ctx.Err() != nil {
		return nil, ErrTransport
	}
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	if err := c.pace(ctx); err != nil {
		return nil, err
	}
	var body io.Reader = http.NoBody
	if form != nil {
		body = strings.NewReader(form.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, body)
	if err != nil {
		return nil, ErrConfig
	}
	req.Header.Set("x-auth-token", c.token)
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, ErrTransport
	} // Never wrap url.Error or provider text.
	defer resp.Body.Close()
	if resp.StatusCode != want {
		return nil, fmt.Errorf("%w: HTTP%d", ErrStatus, resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 65537))
	if err != nil || len(raw) > 65536 {
		return nil, ErrReply
	}
	return raw, nil
}

// MTS documents at most two UserAPI requests/second. No burst or replay on429;
// this credential-owned client spaces starts and bounds queued waits by ctx.
func (c *Client) pace(ctx context.Context) error {
	select {
	case c.gate <- struct{}{}:
	case <-ctx.Done():
		return ErrTransport
	}
	defer func() { <-c.gate }()
	if delay := time.Until(c.next); delay > 0 {
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-timer.C:
		case <-ctx.Done():
			return ErrTransport
		}
	}
	if ctx.Err() != nil {
		return ErrTransport
	}
	c.next = time.Now().Add(600 * time.Millisecond)
	return nil
}

func locator(raw string, eventID, sessionID int64) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host != "my.mts-link.ru" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.RawPath != "" {
		return false
	}
	p := joinPath.FindStringSubmatch(u.Path)
	if p == nil || p[2] != strconv.FormatInt(eventID, 10) {
		return false
	}
	if sessionID == 0 {
		return p[3] == ""
	}
	return p[3] == strconv.FormatInt(sessionID, 10)
}

func (c *Client) CreateEvent(ctx context.Context, name string) (Event, error) {
	if name == "" || name != strings.TrimSpace(name) || len(name) > 128 || strings.ContainsAny(name, "\r\n") {
		return Event{}, ErrInput
	}
	form := url.Values{"name": {name}, "type": {"meeting"}, "isEndless": {"1"},
		"accessSettings[isPasswordRequired]": {"0"}, "accessSettings[isRegistrationRequired]": {"0"},
		"accessSettings[isModerationRequired]": {"0"}, "defaultRemindersEnabled": {"false"}}
	raw, err := c.request(ctx, http.MethodPost, "/events", form, http.StatusCreated)
	if err != nil {
		return Event{}, err
	}
	var v struct {
		ID   int64           `json:"eventId"`
		Link json.RawMessage `json:"link"`
	}
	if json.Unmarshal(raw, &v) != nil || v.ID <= 0 {
		return Event{}, ErrReply
	}
	e := Event{ID: v.ID} // Retain the created ID even if publication is unsafe.
	var link string
	if json.Unmarshal(v.Link, &link) != nil || !locator(link, v.ID, 0) {
		return e, ErrReply
	}
	e.Locator = link
	return e, nil
}

func (c *Client) CreateSession(ctx context.Context, eventID int64) (Session, error) {
	if eventID <= 0 {
		return Session{}, ErrInput
	}
	raw, err := c.request(ctx, http.MethodPost, "/events/"+strconv.FormatInt(eventID, 10)+"/sessions", url.Values{}, http.StatusCreated)
	if err != nil {
		return Session{}, err
	}
	var v struct {
		ID   int64           `json:"eventSessionId"`
		Link json.RawMessage `json:"link"`
	}
	if json.Unmarshal(raw, &v) != nil || v.ID <= 0 {
		return Session{}, ErrReply
	}
	s := Session{ID: v.ID, EventID: eventID}
	var link string
	if json.Unmarshal(v.Link, &link) != nil || !locator(link, eventID, v.ID) {
		return s, ErrReply
	}
	s.Locator = link
	return s, nil
}

// StopSession is irreversible provider retirement, never local Close proof.
func (c *Client) StopSession(ctx context.Context, id int64) error {
	if id <= 0 {
		return ErrInput
	}
	_, err := c.request(ctx, http.MethodPut, "/eventsessions/"+strconv.FormatInt(id, 10)+"/stop", nil, http.StatusNoContent)
	return err
}

// DeleteSession requires caller-owned custody and explicit deletion authority.
// suppressing email is mandatory even though a QA meeting has no invitees.
func (c *Client) DeleteSession(ctx context.Context, id int64) error {
	if id <= 0 {
		return ErrInput
	}
	_, err := c.request(ctx, http.MethodDelete, "/eventsessions/"+strconv.FormatInt(id, 10)+"?sendEmail=false", nil, http.StatusNoContent)
	return err
}

// DeleteEvent retires the exact exclusively-owned container after partial
// provisioning. It must never target an account-wide list or adopted event.
func (c *Client) DeleteEvent(ctx context.Context, id int64) error {
	if id <= 0 {
		return ErrInput
	}
	_, err := c.request(ctx, http.MethodDelete, "/organization/events/"+strconv.FormatInt(id, 10), nil, http.StatusNoContent)
	return err
}
