package relaycontrol

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"io"
	"log"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/store"
)

// Only the external issuer/store are doubled. The boundary under test uses
// real TLS, HTTP, decoding, scoped session custody and the existing NodeHooks.
type backend struct {
	mu                                      sync.Mutex
	admitCalls, renewCalls, reports, closes int
	err                                     error
	lease                                   personal.SessionLease
	lastReport                              store.QAMeterReport
}

func (b *backend) Admit(_ context.Context, claims map[string]any) (personal.SessionLease, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.admitCalls++
	if claims["signed"] != "device-proof" {
		return personal.SessionLease{}, personal.ErrDenied
	}
	return b.lease, b.err
}
func (b *backend) Renew(_ context.Context, _ string) (personal.SessionLease, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.renewCalls++
	return b.lease, b.err
}
func (b *backend) RecordQACumulative(_ context.Context, r store.QAMeterReport) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.reports++
	b.lastReport = r
	return b.err
}
func (b *backend) CloseQASession(_ context.Context, _ access.Subject, _, _ string, final *store.QAFinal) (store.QASession, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closes++
	status := store.QAUnconfirmed
	if final != nil {
		status = store.QAClosed
	}
	return store.QASession{Status: status}, b.err
}

type tlsFixture struct {
	ca             *x509.Certificate
	key            *ecdsa.PrivateKey
	roots          *x509.CertPool
	server, client tls.Certificate
}

func newTLSFixture(t *testing.T) tlsFixture {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	ca := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test-ca"},
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), IsCA: true,
		BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign}
	der, err := x509.CreateCertificate(rand.Reader, ca, ca, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	ca, err = x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	f := tlsFixture{ca: ca, key: key, roots: x509.NewCertPool()}
	f.roots.AddCert(ca)
	f.server = f.leaf(t, 2, true)
	f.client = f.leaf(t, 3, false)
	return f
}
func (f tlsFixture) leaf(t *testing.T, serial int64, server bool) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	usage := x509.ExtKeyUsageClientAuth
	if server {
		usage = x509.ExtKeyUsageServerAuth
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(serial), DNSNames: []string{"localhost"},
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{usage}}
	der, err := x509.CreateCertificate(rand.Reader, template, f.ca, &key.PublicKey, f.key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf}
}

type fixture struct {
	bind Binding
	b    *backend
	f    tlsFixture
	s    *httptest.Server
	c    *Client
	cc   ClientConfig
}

func newFixture(t *testing.T) fixture {
	t.Helper()
	f := fixture{f: newTLSFixture(t), b: &backend{lease: personal.SessionLease{
		SessionID: "session-00000000000001", Deadline: time.Now().Add(25 * time.Second).UTC(), ByteCap: 1 << 30}}}
	f.bind = Binding{Scope: Scope{Worker: "ru-worker-01", Epoch: "epoch-0000000001", Room: "room-00000001", Route: "subscriber-nl", Provider: "telemost"},
		Subject:      access.Subject{PanelInstance: "rga-main", UserID: 42},
		Installation: access.InstallationBinding{ID: "installation-test-0001", PublicKeySHA256: [32]byte{1}},
		ClientSPKI:   sha256.Sum256(f.f.client.Leaf.RawSubjectPublicKeyInfo)}
	h, err := NewHandler(f.bind, f.b, f.b)
	if err != nil {
		t.Fatal(err)
	}
	f.s = httptest.NewUnstartedServer(h)
	f.s.Config.ErrorLog = log.New(io.Discard, "", 0)
	f.s.TLS, err = ServerTLS(f.f.roots, f.f.server)
	if err != nil {
		t.Fatal(err)
	}
	f.s.StartTLS()
	t.Cleanup(f.s.Close)
	f.cc = ClientConfig{Origin: strings.Replace(f.s.URL, "127.0.0.1", "localhost", 1), Scope: f.bind.Scope,
		Subject: f.bind.Subject, Installation: f.bind.Installation, Roots: f.f.roots,
		Certificate: f.f.client, ServerSPKI: sha256.Sum256(f.f.server.Leaf.RawSubjectPublicKeyInfo)}
	f.c, err = NewClient(f.cc)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(f.c.CloseIdleConnections)
	return f
}
func (f fixture) admit(t *testing.T) personal.SessionLease {
	t.Helper()
	lease, err := f.c.Admit(context.Background(), map[string]any{"signed": "device-proof"})
	if err != nil {
		t.Fatal(err)
	}
	return lease
}

func TestScopeAndVerifiedPeerBeforeAdmission(t *testing.T) {
	f := newFixture(t)
	cc := f.cc
	cc.Scope.Epoch = "epoch-0000000002"
	c, err := NewClient(cc)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseIdleConnections()
	if _, err = c.Admit(context.Background(), map[string]any{"signed": "device-proof"}); !errors.Is(err, personal.ErrDenied) {
		t.Fatalf("cross-epoch = %v", err)
	}
	cc = f.cc
	cc.Certificate = f.f.leaf(t, 4, false)
	c, err = NewClient(cc)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseIdleConnections()
	if _, err = c.Admit(context.Background(), map[string]any{"signed": "device-proof"}); !errors.Is(err, personal.ErrDenied) {
		t.Fatalf("unbound CA-valid peer = %v", err)
	}
	if f.b.admitCalls != 0 {
		t.Fatal("unbound peer reached issuer")
	}
	lease := f.admit(t)
	if lease.SessionID != "session-00000000000001" || !lease.Deadline.Equal(f.b.lease.Deadline) || lease.ByteCap != 1<<30 {
		t.Fatal("lease was altered in transit")
	}
}

func TestRenewRequiresThisEpochAdmissionAndCannotReviveClosedSession(t *testing.T) {
	f := newFixture(t)
	if _, err := f.c.Renew(context.Background(), "session-00000000000001"); !errors.Is(err, personal.ErrDenied) {
		t.Fatalf("unadmitted renewal = %v", err)
	}
	if f.b.renewCalls != 0 {
		t.Fatal("unadmitted session reached issuer")
	}
	lease := f.admit(t)
	if _, err := f.c.Renew(context.Background(), lease.SessionID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.c.CloseQASession(context.Background(), f.bind.Subject, f.bind.Installation.ID, lease.SessionID, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := f.c.Renew(context.Background(), lease.SessionID); !errors.Is(err, personal.ErrDenied) {
		t.Fatalf("closed renewal = %v", err)
	}
	if f.b.renewCalls != 1 {
		t.Fatal("closed session reached issuer again")
	}
}

func TestMeterScopeAndAmbiguousCommitNeverAcknowledged(t *testing.T) {
	f := newFixture(t)
	lease := f.admit(t)
	r := store.QAMeterReport{Subject: f.bind.Subject, InstallationID: f.bind.Installation.ID,
		SessionID: lease.SessionID, Sequence: 7, ClientToExitBytes: 123, ExitToClientBytes: 456}
	wrong := r
	wrong.Subject.UserID++
	if err := f.c.RecordQACumulative(context.Background(), wrong); err == nil {
		t.Fatal("foreign subject accepted")
	}
	if f.b.reports != 0 {
		t.Fatal("foreign subject reached journal")
	}
	f.b.err = errors.New("PRIVATE DSN must not escape")
	if err := f.c.RecordQACumulative(context.Background(), r); !errors.Is(err, personal.ErrUnavailable) || strings.Contains(err.Error(), "PRIVATE") {
		t.Fatalf("ambiguous ACK/error = %v", err)
	}
	f.b.err = nil
	if err := f.c.RecordQACumulative(context.Background(), r); err != nil {
		t.Fatal(err)
	}
	if f.b.lastReport != r {
		t.Fatal("cumulative snapshot changed or lost server scope")
	}
}

func TestTransportRejectsDuplicateFieldsAndFakeTLSHeaders(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	// Fake headers cannot confer a peer identity without verified mutual TLS.
	h, err := NewHandler(f.bind, f.b, f.b)
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest(http.MethodPost, "/node-control/v1/admit", strings.NewReader(`{}`))
	r.Header.Set("X-Forwarded-Client-Cert", "trusted")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("fake TLS accepted: %d", w.Code)
	}
	bad := `{"scope":{"worker":"ru-worker-01","epoch":"epoch-0000000001","room":"room-00000001","route":"subscriber-nl","provider":"telemost"},"claims":{"signed":"device-proof","signed":"device-proof"}}`
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.cc.Origin+"/node-control/v1/admit", strings.NewReader(bad))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := f.c.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != http.StatusBadRequest || f.b.admitCalls != 0 {
		t.Fatal("ambiguous JSON reached issuer")
	}
}

func TestNoInsecureOriginAndWrongServerPinFailsClosed(t *testing.T) {
	f := newFixture(t)
	for _, origin := range []string{f.s.URL, "http://localhost:1234", "https://localhost/x", "https://user@localhost", "https://localhost?x=1"} {
		cc := f.cc
		cc.Origin = origin
		if _, err := NewClient(cc); err == nil {
			t.Fatalf("unsafe control origin accepted: %q", origin)
		}
	}
	cc := f.cc
	cc.ServerSPKI[0] ^= 1
	c, err := NewClient(cc)
	if err != nil {
		t.Fatal(err)
	}
	defer c.CloseIdleConnections()
	if _, err := c.Admit(context.Background(), map[string]any{"signed": "device-proof"}); !errors.Is(err, personal.ErrUnavailable) {
		t.Fatalf("bad server pin = %v", err)
	}
	if f.b.admitCalls != 0 {
		t.Fatal("unverified server received proof")
	}
}

func TestOverlongLeaseDeniedRatherThanLocallyRecapped(t *testing.T) {
	f := newFixture(t)
	f.b.lease.Deadline = time.Now().Add(time.Minute)
	if lease, err := f.c.Admit(context.Background(), map[string]any{"signed": "device-proof"}); err == nil || lease.SessionID != "" {
		t.Fatal("overlong committed lease was granted or silently rewritten")
	}
}

type nativeLeaseBoundary struct{ renewed, closed int }

func (n *nativeLeaseBoundary) RenewLease(string, time.Time) bool { n.renewed++; return true }
func (n *nativeLeaseBoundary) CloseSession(string) bool          { n.closed++; return true }

func TestRemoteFailureClassificationDrivesExistingLocalLeaseHooks(t *testing.T) {
	f := newFixture(t)
	native := &nativeLeaseBoundary{}
	h, err := personal.NewNodeHooks(f.bind.Subject, f.bind.Installation, f.c, f.c, native)
	if err != nil {
		t.Fatal(err)
	}
	l, err := h.Admit(context.Background(), "untrusted-label", map[string]any{"signed": "device-proof"})
	if err != nil {
		t.Fatal(err)
	}
	f.b.err = personal.ErrUnavailable
	if err := h.Renew(context.Background(), l.SessionID); !errors.Is(err, personal.ErrNodeUnavailable) {
		t.Fatalf("transient result = %v", err)
	}
	if native.renewed != 0 || native.closed != 0 {
		t.Fatal("outage changed existing native deadline")
	}
	f.b.err = personal.ErrDenied
	if err := h.Renew(context.Background(), l.SessionID); !errors.Is(err, personal.ErrNodeDenied) {
		t.Fatalf("revoke result = %v", err)
	}
	if native.renewed != 0 || native.closed != 1 {
		t.Fatal("definitive denial did not cut exact local session")
	}
}

func TestAmbiguousCloseFencesRenewalButAllowsExactJournalRetry(t *testing.T) {
	f := newFixture(t)
	l := f.admit(t)
	f.b.err = errors.New("commit outcome unknown")
	if _, err := f.c.CloseQASession(context.Background(), f.bind.Subject, f.bind.Installation.ID, l.SessionID, nil); !errors.Is(err, personal.ErrUnavailable) {
		t.Fatalf("ambiguous close = %v", err)
	}
	f.b.err = nil
	if _, err := f.c.Renew(context.Background(), l.SessionID); !errors.Is(err, personal.ErrDenied) || f.b.renewCalls != 0 {
		t.Fatal("ambiguous close restored authority")
	}
	row, err := f.c.CloseQASession(context.Background(), f.bind.Subject, f.bind.Installation.ID, l.SessionID,
		&store.QAFinal{ClientToExitBytes: 123, ExitToClientBytes: 456})
	if err != nil || row.Status != store.QAClosed {
		t.Fatal("late terminal confirmation rejected")
	}
}
