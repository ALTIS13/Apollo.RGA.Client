package main

import (
	"context"
	"net/http"
	"os"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/cmd/rga-personal-node/internal/node"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/remnawave"
)

const browserClientTypeHeader = "X-Remnawave-Client-Type"

const browserCredentialsFileEnv = "RGA_PERSONAL_PANEL_CREDENTIALS_FILE"

type panelClient interface {
	Lookup(context.Context, string) (remnawave.UserSnapshot, error)
	LookupByID(context.Context, int64) (remnawave.UserSnapshot, error)
}

type browserSessionTransport struct{ next http.RoundTripper }

func (t browserSessionTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	copyRequest := request.Clone(request.Context())
	copyRequest.Header.Set(browserClientTypeHeader, "browser")
	return t.next.RoundTrip(copyRequest)
}

// This client never owns a cookie jar. Remnawave's constructor retains its
// timeout and redirect guard, and the default API-token path adds no header.
func newPanelClient(cfg node.Config, transport http.RoundTripper) (panelClient, error) {
	if cfg.PanelAuthKind != "" && cfg.PanelAuthKind != "api_token" && cfg.PanelAuthKind != "browser_session" {
		return nil, node.ErrConfig
	}
	if transport == nil {
		if cfg.PanelAuthKind == "browser_session" && os.Getenv(browserCredentialsFileEnv) != "" {
			isolated := http.DefaultTransport.(*http.Transport).Clone()
			isolated.Proxy = nil
			transport = isolated
		} else {
			transport = http.DefaultTransport
		}
	}
	if cfg.PanelAuthKind == "browser_session" {
		transport = browserSessionTransport{next: transport}
	}
	client, err := remnawave.NewClient(cfg.PanelOrigin, cfg.PanelAdminToken, &http.Client{Transport: transport})
	if err != nil {
		return nil, err
	}
	if cfg.PanelAuthKind == "browser_session" && os.Getenv(browserCredentialsFileEnv) != "" {
		refresh, refreshErr := newRefreshablePanel(cfg.PanelOrigin, os.Getenv(browserCredentialsFileEnv), cfg.PanelAdminToken, client, transport)
		if refreshErr != nil {
			return nil, refreshErr
		}
		return refresh, nil
	}
	return client, nil
}
