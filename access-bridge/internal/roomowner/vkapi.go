package roomowner

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var ErrOrganizerUnavailable = errors.New("room owner: authorized organizer unavailable")
var appID = regexp.MustCompile(`^[1-9][0-9]{0,19}$`)

// OrganizerToken comes from a separately approved operator OAuth/custody flow.
// Metadata must be established by that flow; it is not inferred by parsing an
// opaque access token. This adapter does not register apps, grant Calls rights,
// scrape browser sessions or request authority on behalf of an RGA subscriber.
type OrganizerToken struct {
	Reference   string
	AppID       string
	OrganizerID int64
	AccessToken string `json:"-"`
	NotAfter    time.Time
}

func (OrganizerToken) String() string { return "organizer credential [redacted]" }

type TokenSource interface {
	Load(context.Context, string) (OrganizerToken, error)
}

type VKProvider struct {
	application string
	organizer   int64
	tokens      TokenSource
	endpoint    string
	client      *http.Client
}

// NewVKProvider fixes the official API origin. In particular, a user/device
// cannot supply an endpoint, redirect target, app ID or organizer credential.
// Constructing it does not establish that VK grants this app Calls API access.
func NewVKProvider(application string, organizer int64, tokens TokenSource) (*VKProvider, error) {
	if !appID.MatchString(application) || organizer <= 0 || nilInterface(tokens) {
		return nil, ErrConfig
	}
	transport := &http.Transport{
		Proxy: nil, DialContext: (&net.Dialer{Timeout: 5 * time.Second}).DialContext,
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout: 5 * time.Second, ResponseHeaderTimeout: 8 * time.Second,
		DisableKeepAlives: true, MaxConnsPerHost: 2,
	}
	return &VKProvider{application: application, organizer: organizer, tokens: tokens,
		endpoint: "https://api.vk.com/method",
		client: &http.Client{Transport: transport, Timeout: 10 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }},
	}, nil
}

func (p *VKProvider) credential(ctx context.Context, ref string) (OrganizerToken, error) {
	if ctx.Err() != nil || !opaque.MatchString(ref) {
		return OrganizerToken{}, ErrOrganizerUnavailable
	}
	token, err := p.tokens.Load(ctx, ref)
	if err != nil || token.Reference != ref || token.AppID != p.application || token.OrganizerID != p.organizer ||
		token.AccessToken == "" || len(token.AccessToken) > 8192 || strings.TrimSpace(token.AccessToken) != token.AccessToken ||
		!token.NotAfter.After(time.Now()) || ctx.Err() != nil {
		return OrganizerToken{}, ErrOrganizerUnavailable
	}
	return token, nil
}

type vkResponse struct {
	Response json.RawMessage `json:"response"`
	Error    *struct {
		Code int `json:"error_code"`
	} `json:"error"`
}

func (p *VKProvider) request(ctx context.Context, ref, method string, fields url.Values) (vkResponse, error) {
	token, err := p.credential(ctx, ref)
	if err != nil {
		return vkResponse{}, err
	}
	fields.Set("access_token", token.AccessToken)
	fields.Set("v", "5.199")
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.endpoint+"/"+method, strings.NewReader(fields.Encode()))
	if err != nil {
		return vkResponse{}, ErrCreationUnknown
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "ApolloRGA-RoomControl/0.1")
	response, err := p.client.Do(req)
	if err != nil {
		return vkResponse{}, ErrCreationUnknown
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return vkResponse{}, ErrCreationUnknown
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, (16<<10)+1))
	if err != nil || len(body) > 16<<10 {
		return vkResponse{}, ErrCreationUnknown
	}
	var value vkResponse
	decoder := json.NewDecoder(bytes.NewReader(body))
	if decoder.Decode(&value) != nil || decoder.Decode(new(any)) != io.EOF {
		return vkResponse{}, ErrCreationUnknown
	}
	if value.Error != nil {
		// Method presence/token type does not document error-side-effect
		// semantics. Even a positive internal error code is not proof that no
		// room was created. A qualified no-side-effect contract is needed before
		// introducing any definitive-rejection classification here.
		return vkResponse{}, ErrCreationUnknown
	}
	if len(value.Response) == 0 {
		return vkResponse{}, ErrCreationUnknown
	}
	return value, nil
}

func (p *VKProvider) Create(ctx context.Context, ref string) (CreatedRoom, error) {
	result, err := p.request(ctx, ref, "calls.start", url.Values{})
	if err != nil {
		return CreatedRoom{}, err
	}
	var response struct {
		CallID   string `json:"call_id"`
		JoinLink string `json:"join_link"`
	}
	if json.Unmarshal(result.Response, &response) != nil {
		return CreatedRoom{}, ErrCreationUnknown
	}
	room := CreatedRoom{CallID: response.CallID, Locator: response.JoinLink}
	if !room.valid() {
		return CreatedRoom{}, ErrCreationUnknown
	}
	return room, nil
}

func (p *VKProvider) Finish(ctx context.Context, ref, id string) error {
	if !callID.MatchString(id) {
		return ErrConfig
	}
	result, err := p.request(ctx, ref, "calls.forceFinish", url.Values{"call_id": {id}})
	if err != nil {
		return ErrCleanupUnknown
	}
	if !bytes.Equal(bytes.TrimSpace(result.Response), []byte("1")) {
		return ErrCleanupUnknown
	}
	return nil
}
