package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

const (
	maxPanelCredentialBytes = 64 << 10
	maxPanelLoginBytes      = 16 << 10
	panelLoginTimeout       = 5 * time.Second
	panelRefreshAhead       = 5 * time.Minute
)

type panelCredentials struct {
	username string
	password string
}

// refreshablePanel is exclusive to the personal-node executable. JWT exp is
// only a timing hint; Remnawave remains the authentication and policy authority.
// The password and JWT never leave this process except to the exact panel origin.
type refreshablePanel struct {
	mu          sync.Mutex
	origin      string
	credentials panelCredentials
	transport   http.RoundTripper
	loginHTTP   *http.Client
	client      *remnawave.Client
	expires     time.Time
}

func newRefreshablePanel(origin, credentialsPath, initialToken string, initial *remnawave.Client, transport http.RoundTripper) (*refreshablePanel, error) {
	credentials, err := readPanelCredentials(credentialsPath, origin)
	if err != nil || initial == nil || transport == nil {
		return nil, remnawave.ErrInvalidConfig
	}
	return &refreshablePanel{
		origin: origin, credentials: credentials, transport: transport, client: initial,
		expires: jwtExpiry(initialToken),
		loginHTTP: &http.Client{
			Transport: transport, Timeout: panelLoginTimeout,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}, nil
}

func readPanelCredentials(path, expectedOrigin string) (panelCredentials, error) {
	if path == "" {
		return panelCredentials{}, remnawave.ErrInvalidConfig
	}
	f, err := os.Open(path)
	if err != nil {
		return panelCredentials{}, remnawave.ErrInvalidConfig
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || runtime.GOOS == "linux" && (info.Mode().Perm()&0400 == 0 || info.Mode().Perm()&0077 != 0) {
		return panelCredentials{}, remnawave.ErrInvalidConfig
	}
	data, err := io.ReadAll(io.LimitReader(f, maxPanelCredentialBytes+1))
	if err != nil || len(data) > maxPanelCredentialBytes {
		return panelCredentials{}, remnawave.ErrInvalidConfig
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	var found *panelCredentials
	for {
		var document map[string]json.RawMessage
		err := decoder.Decode(&document)
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil || document == nil {
			return panelCredentials{}, remnawave.ErrInvalidConfig
		}
		entry, ok := document["Remnawave Panel"]
		if !ok {
			continue
		}
		if found != nil {
			return panelCredentials{}, remnawave.ErrInvalidConfig
		}
		var value struct {
			Address string `json:"Address"`
			Login   string `json:"Login"`
			Pass    string `json:"Pass"`
		}
		if json.Unmarshal(entry, &value) != nil || value.Address != expectedOrigin ||
			value.Login == "" || value.Pass == "" || len(value.Login) > 256 || len(value.Pass) > 8192 {
			return panelCredentials{}, remnawave.ErrInvalidConfig
		}
		found = &panelCredentials{username: value.Login, password: value.Pass}
	}
	if found == nil {
		return panelCredentials{}, remnawave.ErrInvalidConfig
	}
	return *found, nil
}

func jwtExpiry(token string) time.Time {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || len(parts[1]) > 8192 {
		return time.Time{}
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return time.Time{}
	}
	var claims struct {
		Exp int64 `json:"exp"`
	}
	if json.Unmarshal(payload, &claims) != nil || claims.Exp <= 0 {
		return time.Time{}
	}
	return time.Unix(claims.Exp, 0)
}

func (p *refreshablePanel) current(ctx context.Context) (*remnawave.Client, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if ctx.Err() != nil {
		return nil, remnawave.ErrUnavailable
	}
	if p.expires.IsZero() {
		// An opaque or malformed configured session must not be treated as
		// renewable just because a local file supplied it.
		return p.refreshLocked(ctx)
	}
	if p.expires.After(time.Now().Add(panelRefreshAhead)) {
		return p.client, nil
	}
	return p.refreshLocked(ctx)
}

func (p *refreshablePanel) afterUnauthorized(ctx context.Context, used *remnawave.Client) (*remnawave.Client, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if ctx.Err() != nil {
		return nil, remnawave.ErrUnavailable
	}
	if p.client != used && p.expires.After(time.Now().Add(panelRefreshAhead)) {
		return p.client, nil
	}
	return p.refreshLocked(ctx)
}

func (p *refreshablePanel) refreshLocked(ctx context.Context) (*remnawave.Client, error) {
	token, err := p.login(ctx)
	if err != nil {
		return nil, err
	}
	expires := jwtExpiry(token)
	if !expires.After(time.Now().Add(panelRefreshAhead)) {
		return nil, remnawave.ErrInvalidResponse
	}
	client, err := remnawave.NewClient(p.origin, token, &http.Client{Transport: p.transport})
	if err != nil {
		return nil, remnawave.ErrInvalidResponse
	}
	p.client, p.expires = client, expires
	return client, nil
}

func (p *refreshablePanel) login(ctx context.Context) (string, error) {
	body, _ := json.Marshal(struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}{p.credentials.username, p.credentials.password})
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, p.origin+"/api/auth/login", bytes.NewReader(body))
	if err != nil {
		return "", remnawave.ErrInvalidConfig
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", "application/json")
	response, err := p.loginHTTP.Do(request)
	if err != nil {
		return "", remnawave.ErrUnavailable
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return "", remnawave.ErrPanelAuthentication
	}
	if response.StatusCode != http.StatusOK {
		return "", remnawave.ErrUnavailable
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, maxPanelLoginBytes+1))
	if err != nil || len(data) > maxPanelLoginBytes {
		return "", remnawave.ErrInvalidResponse
	}
	var result struct {
		Response struct {
			AccessToken string `json:"accessToken"`
		} `json:"response"`
	}
	if json.Unmarshal(data, &result) != nil || result.Response.AccessToken == "" || len(result.Response.AccessToken) > 8192 {
		return "", remnawave.ErrInvalidResponse
	}
	return result.Response.AccessToken, nil
}

func (p *refreshablePanel) Lookup(ctx context.Context, shortUUID string) (remnawave.UserSnapshot, error) {
	return p.lookup(ctx, func(client *remnawave.Client) (remnawave.UserSnapshot, error) {
		return client.Lookup(ctx, shortUUID)
	})
}

func (p *refreshablePanel) LookupByID(ctx context.Context, id int64) (remnawave.UserSnapshot, error) {
	return p.lookup(ctx, func(client *remnawave.Client) (remnawave.UserSnapshot, error) {
		return client.LookupByID(ctx, id)
	})
}

func (p *refreshablePanel) lookup(ctx context.Context, operation func(*remnawave.Client) (remnawave.UserSnapshot, error)) (remnawave.UserSnapshot, error) {
	client, err := p.current(ctx)
	if err != nil {
		return remnawave.UserSnapshot{}, err
	}
	result, err := operation(client)
	if !errors.Is(err, remnawave.ErrPanelAuthentication) {
		return result, err
	}
	client, err = p.afterUnauthorized(ctx, client)
	if err != nil {
		return remnawave.UserSnapshot{}, err
	}
	return operation(client)
}
