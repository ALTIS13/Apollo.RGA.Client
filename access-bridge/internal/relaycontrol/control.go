// Package relaycontrol separates fixed-binding QA admission/journaling on Home
// from the managed RTC carrier on RU. It is not a public multiuser entitlement
// service, a payload proxy, or shared Remnawave billing.
package relaycontrol

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/rtcprovider"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

var ErrConfig = errors.New("relay control: invalid configuration")

const (
	pathPrefix  = "/node-control/v1/"
	maxMessage  = 16 << 10
	maxSessions = 64 // one finite worker epoch, never an unbounded user registry
	maxLease    = 30 * time.Second
	rpcTimeout  = 5 * time.Second
)

var opaque = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)

// Scope is server-owned composition, not a device-supplied route selector.
// A fresh worker epoch cannot renew an earlier worker's sessions.
type Scope struct {
	Worker   string `json:"worker"`
	Epoch    string `json:"epoch"`
	Room     string `json:"room"`
	Route    string `json:"route"`
	Provider string `json:"provider"`
}

func (s Scope) valid() bool {
	return opaque.MatchString(s.Worker) && opaque.MatchString(s.Epoch) &&
		opaque.MatchString(s.Room) && opaque.MatchString(s.Route) && rtcprovider.Supported(s.Provider)
}

type Binding struct {
	Scope        Scope
	Subject      access.Subject
	Installation access.InstallationBinding
	ClientSPKI   [32]byte
}

type Issuer interface {
	Admit(context.Context, map[string]any) (personal.SessionLease, error)
	Renew(context.Context, string) (personal.SessionLease, error)
}
type Journal interface {
	RecordQACumulative(context.Context, store.QAMeterReport) error
	CloseQASession(context.Context, access.Subject, string, string, *store.QAFinal) (store.QASession, error)
}

type request struct {
	Scope   Scope          `json:"scope"`
	Claims  map[string]any `json:"claims,omitempty"`
	Session string         `json:"session,omitempty"`
	Report  *meter         `json:"report,omitempty"`
	Final   *store.QAFinal `json:"final,omitempty"`
}
type meter struct {
	Sequence int64 `json:"sequence"`
	Up       int64 `json:"up"`
	Down     int64 `json:"down"`
}
type leaseWire struct {
	Session   string    `json:"session"`
	NotAfter  time.Time `json:"notAfter"`
	ByteLimit int64     `json:"byteLimit"`
}
type response struct {
	Scope  Scope          `json:"scope"`
	Lease  *leaseWire     `json:"lease,omitempty"`
	Report *meter         `json:"report,omitempty"`
	Status store.QAStatus `json:"status,omitempty"`
}
type session struct {
	deadline time.Time
	closed   bool
}
type handler struct {
	b        Binding
	issuer   Issuer
	journal  Journal
	mu       sync.Mutex
	sessions map[string]session
	flight   chan struct{}
}

func nilValue(v any) bool {
	if v == nil {
		return true
	}
	r := reflect.ValueOf(v)
	return (r.Kind() == reflect.Pointer || r.Kind() == reflect.Interface) && r.IsNil()
}

// NewHandler must be mounted on a dedicated mutually authenticated TLS server.
// No forwarded identity header is accepted. Subject and installation never come
// from the request; the existing issuer still verifies the device's signed proof.
func NewHandler(b Binding, issuer Issuer, journal Journal) (http.Handler, error) {
	if !b.Scope.valid() || b.Subject.Validate() != nil || b.Installation.Validate() != nil ||
		b.ClientSPKI == ([32]byte{}) || nilValue(issuer) || nilValue(journal) {
		return nil, ErrConfig
	}
	return &handler{b: b, issuer: issuer, journal: journal, sessions: make(map[string]session), flight: make(chan struct{}, 4)}, nil
}

func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.TLS == nil || len(r.TLS.VerifiedChains) == 0 || len(r.TLS.VerifiedChains[0]) == 0 ||
		len(r.TLS.PeerCertificates) == 0 || !bytes.Equal(r.TLS.PeerCertificates[0].Raw, r.TLS.VerifiedChains[0][0].Raw) ||
		sha256.Sum256(r.TLS.PeerCertificates[0].RawSubjectPublicKeyInfo) != h.b.ClientSPKI {
		http.Error(w, "denied", http.StatusForbidden)
		return
	}
	if r.Method != http.MethodPost || r.URL.RawQuery != "" || r.URL.EscapedPath() != r.URL.Path ||
		r.Header.Get("Content-Type") != "application/json" || r.Header.Get("Content-Encoding") != "" {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	op := strings.TrimPrefix(r.URL.Path, pathPrefix)
	if r.URL.Path != pathPrefix+op || (op != "admit" && op != "renew" && op != "report" && op != "close") {
		http.NotFound(w, r)
		return
	}
	select {
	case h.flight <- struct{}{}:
		defer func() { <-h.flight }()
	default:
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req request
	if strictJSON(http.MaxBytesReader(w, r.Body, maxMessage), &req) != nil || !validRequest(op, req) {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Scope != h.b.Scope {
		http.Error(w, "denied", http.StatusForbidden)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), rpcTimeout)
	defer cancel()
	resp, err := h.perform(ctx, op, req)
	if err != nil {
		if errors.Is(err, personal.ErrDenied) {
			http.Error(w, "denied", http.StatusForbidden)
		} else {
			http.Error(w, "unavailable", http.StatusServiceUnavailable)
		}
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func validRequest(op string, r request) bool {
	if op == "admit" {
		if r.Session != "" || r.Report != nil || r.Final != nil || len(r.Claims) < 1 || len(r.Claims) > 8 {
			return false
		}
		for k, v := range r.Claims {
			s, ok := v.(string)
			if !ok || len(k) > 64 || k == "" || s == "" || len(s) > 1024 {
				return false
			}
		}
		return true
	}
	if len(r.Claims) != 0 || !opaque.MatchString(r.Session) {
		return false
	}
	switch op {
	case "renew":
		return r.Report == nil && r.Final == nil
	case "report":
		return r.Final == nil && r.Report != nil && r.Report.Sequence > 0 && r.Report.Up >= 0 && r.Report.Down >= 0
	case "close":
		return r.Report == nil && (r.Final == nil || r.Final.ClientToExitBytes >= 0 && r.Final.ExitToClientBytes >= 0)
	}
	return false
}

func validLease(l personal.SessionLease, now time.Time) bool {
	return opaque.MatchString(l.SessionID) && l.Deadline.After(now) && !l.Deadline.After(now.Add(maxLease)) && l.ByteCap > 0 && l.ByteCap <= 1<<40
}

func (h *handler) perform(ctx context.Context, op string, r request) (response, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := response{Scope: h.b.Scope}
	if ctx.Err() != nil {
		return response{}, personal.ErrUnavailable
	}
	s, known := h.sessions[r.Session]
	if op != "admit" && (!known || op == "renew" && (s.closed || !s.deadline.After(time.Now()))) {
		return response{}, personal.ErrDenied
	}
	if op == "admit" || op == "renew" {
		if op == "admit" && len(h.sessions) >= maxSessions {
			return response{}, personal.ErrUnavailable
		}
		var l personal.SessionLease
		var err error
		if op == "admit" {
			l, err = h.issuer.Admit(ctx, r.Claims)
		} else {
			l, err = h.issuer.Renew(ctx, r.Session)
		}
		if err != nil {
			if op == "renew" && errors.Is(err, personal.ErrDenied) {
				s.closed = true
				h.sessions[r.Session] = s
			}
			return response{}, err
		}
		if !validLease(l, time.Now()) || op == "renew" && (l.SessionID != r.Session || l.Deadline.Before(s.deadline)) {
			return response{}, personal.ErrUnavailable
		}
		if op == "admit" {
			if _, exists := h.sessions[l.SessionID]; exists {
				return response{}, personal.ErrDenied
			}
		}
		// Store only this epoch's committed session. Never reconstruct authority
		// from an arbitrary pre-existing QA row after a process restart.
		h.sessions[l.SessionID] = session{deadline: l.Deadline, closed: ctx.Err() != nil}
		if ctx.Err() != nil {
			return response{}, personal.ErrUnavailable
		}
		out.Lease = &leaseWire{Session: l.SessionID, NotAfter: l.Deadline, ByteLimit: l.ByteCap}
		return out, nil
	}
	if op == "report" {
		err := h.journal.RecordQACumulative(ctx, store.QAMeterReport{Subject: h.b.Subject, InstallationID: h.b.Installation.ID,
			SessionID: r.Session, Sequence: r.Report.Sequence, ClientToExitBytes: r.Report.Up, ExitToClientBytes: r.Report.Down})
		if err != nil || ctx.Err() != nil {
			return response{}, personal.ErrUnavailable
		}
		out.Report = r.Report // exact snapshot, only after the existing durable store ACK
		return out, nil
	}
	// Closing fences renewal even if the terminal journal write is ambiguous.
	// A late retry may confirm meter/disposal, but cannot restore traffic rights.
	s.closed = true
	h.sessions[r.Session] = s
	row, err := h.journal.CloseQASession(ctx, h.b.Subject, h.b.Installation.ID, r.Session, r.Final)
	if err != nil || ctx.Err() != nil {
		return response{}, personal.ErrUnavailable
	}
	if row.Status != store.QAClosed && row.Status != store.QAUnconfirmed {
		return response{}, personal.ErrUnavailable
	}
	out.Status = row.Status
	return out, nil
}

// strictJSON rejects unknown fields, duplicate keys at every level, trailing
// values and oversized responses. Signature claims remain flat and unmodified.
func strictJSON(r io.Reader, value any) error {
	b, err := io.ReadAll(io.LimitReader(r, maxMessage+1))
	if err != nil || len(b) > maxMessage {
		return ErrConfig
	}
	d := json.NewDecoder(bytes.NewReader(b))
	var scan func(int) error
	scan = func(depth int) error {
		if depth > 8 {
			return ErrConfig
		}
		tok, err := d.Token()
		if err != nil {
			return err
		}
		delim, ok := tok.(json.Delim)
		if !ok {
			return nil
		}
		if delim != '{' {
			return ErrConfig
		}
		seen := map[string]bool{}
		for d.More() {
			tok, err = d.Token()
			key, ok := tok.(string)
			if err != nil || !ok || seen[key] {
				return ErrConfig
			}
			seen[key] = true
			if err = scan(depth + 1); err != nil {
				return err
			}
		}
		end, err := d.Token()
		if err != nil || end != json.Delim('}') {
			return ErrConfig
		}
		return nil
	}
	if scan(0) != nil {
		return ErrConfig
	}
	if _, err := d.Token(); err != io.EOF {
		return ErrConfig
	}
	d = json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if err := d.Decode(value); err != nil {
		return ErrConfig
	}
	return nil
}

func ServerTLS(roots *x509.CertPool, certificate tls.Certificate) (*tls.Config, error) {
	if roots == nil || len(certificate.Certificate) == 0 || certificate.PrivateKey == nil {
		return nil, ErrConfig
	}
	return &tls.Config{MinVersion: tls.VersionTLS13, ClientAuth: tls.RequireAndVerifyClientCert,
		ClientCAs: roots.Clone(), Certificates: []tls.Certificate{certificate}}, nil
}

type ClientConfig struct {
	Origin       string
	Scope        Scope
	Subject      access.Subject
	Installation access.InstallationBinding
	Roots        *x509.CertPool
	Certificate  tls.Certificate
	ServerSPKI   [32]byte
}
type Client struct {
	cfg  ClientConfig
	http *http.Client
}

func NewClient(c ClientConfig) (*Client, error) {
	u, err := url.Parse(c.Origin)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.Path != "" || u.User != nil ||
		u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.Opaque != "" || net.ParseIP(u.Hostname()) != nil ||
		!c.Scope.valid() || c.Subject.Validate() != nil || c.Installation.Validate() != nil ||
		c.Roots == nil || len(c.Certificate.Certificate) == 0 || c.Certificate.PrivateKey == nil || c.ServerSPKI == ([32]byte{}) {
		return nil, ErrConfig
	}
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS13, RootCAs: c.Roots.Clone(),
		Certificates: []tls.Certificate{c.Certificate}, ServerName: u.Hostname(),
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.VerifiedChains) == 0 || len(cs.PeerCertificates) == 0 || sha256.Sum256(cs.PeerCertificates[0].RawSubjectPublicKeyInfo) != c.ServerSPKI {
				return ErrConfig
			}
			return nil
		}}
	transport := &http.Transport{Proxy: nil, TLSClientConfig: tlsConfig,
		DialContext:         (&net.Dialer{Timeout: 3 * time.Second}).DialContext,
		TLSHandshakeTimeout: 3 * time.Second, ResponseHeaderTimeout: rpcTimeout,
		MaxConnsPerHost: 4, MaxIdleConnsPerHost: 4, IdleConnTimeout: 30 * time.Second,
		DisableCompression: true}
	return &Client{cfg: c, http: &http.Client{Transport: transport, Timeout: rpcTimeout,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

func (c *Client) CloseIdleConnections() { c.http.CloseIdleConnections() }

func (c *Client) call(ctx context.Context, op string, req request) (response, error) {
	if ctx == nil || ctx.Err() != nil {
		return response{}, personal.ErrUnavailable
	}
	req.Scope = c.cfg.Scope
	if !validRequest(op, req) {
		return response{}, personal.ErrDenied
	}
	b, err := json.Marshal(req)
	if err != nil || len(b) > maxMessage {
		return response{}, personal.ErrDenied
	}
	r, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.Origin+pathPrefix+op, bytes.NewReader(b))
	if err != nil {
		return response{}, personal.ErrUnavailable
	}
	r.Header.Set("Content-Type", "application/json")
	res, err := c.http.Do(r)
	if err != nil {
		return response{}, personal.ErrUnavailable
	}
	defer res.Body.Close()
	if res.StatusCode == http.StatusForbidden {
		return response{}, personal.ErrDenied
	}
	if res.StatusCode != http.StatusOK || res.Header.Get("Content-Type") != "application/json" {
		return response{}, personal.ErrUnavailable
	}
	var out response
	if strictJSON(res.Body, &out) != nil || out.Scope != c.cfg.Scope || ctx.Err() != nil {
		return response{}, personal.ErrUnavailable
	}
	return out, nil
}

func (c *Client) getLease(ctx context.Context, op string, r request) (personal.SessionLease, error) {
	out, err := c.call(ctx, op, r)
	if err != nil {
		return personal.SessionLease{}, err
	}
	if out.Lease == nil || out.Report != nil || out.Status != "" {
		return personal.SessionLease{}, personal.ErrUnavailable
	}
	l := personal.SessionLease{SessionID: out.Lease.Session, Deadline: out.Lease.NotAfter, ByteCap: out.Lease.ByteLimit}
	if !validLease(l, time.Now()) || op == "renew" && l.SessionID != r.Session {
		return personal.SessionLease{}, personal.ErrUnavailable
	}
	return l, nil
}
func (c *Client) Admit(ctx context.Context, claims map[string]any) (personal.SessionLease, error) {
	return c.getLease(ctx, "admit", request{Claims: claims})
}
func (c *Client) Renew(ctx context.Context, id string) (personal.SessionLease, error) {
	return c.getLease(ctx, "renew", request{Session: id})
}
func (c *Client) RecordQACumulative(ctx context.Context, r store.QAMeterReport) error {
	if r.Subject != c.cfg.Subject || r.InstallationID != c.cfg.Installation.ID {
		return personal.ErrDenied
	}
	m := &meter{Sequence: r.Sequence, Up: r.ClientToExitBytes, Down: r.ExitToClientBytes}
	out, err := c.call(ctx, "report", request{Session: r.SessionID, Report: m})
	if err != nil {
		return err
	}
	if out.Report == nil || *out.Report != *m || out.Lease != nil || out.Status != "" {
		return personal.ErrUnavailable
	}
	return nil
}
func (c *Client) CloseQASession(ctx context.Context, subject access.Subject, installation, id string, final *store.QAFinal) (store.QASession, error) {
	if subject != c.cfg.Subject || installation != c.cfg.Installation.ID {
		return store.QASession{}, personal.ErrDenied
	}
	out, err := c.call(ctx, "close", request{Session: id, Final: final})
	if err != nil {
		return store.QASession{}, err
	}
	if out.Lease != nil || out.Report != nil || out.Status != store.QAClosed && out.Status != store.QAUnconfirmed {
		return store.QASession{}, personal.ErrUnavailable
	}
	return store.QASession{Subject: subject, InstallationID: installation, SessionID: id, Status: out.Status}, nil
}
