// Package remnawave reads the minimum subscription facts needed by Access Bridge.
// It deliberately never exposes the panel's subscription URL or protocol keys.
package remnawave

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

const (
	maxResponseBytes = 1 << 20
	requestTimeout   = 5 * time.Second
)

var (
	ErrInvalidConfig       = errors.New("remnawave: invalid configuration")
	ErrInvalidResponse     = errors.New("remnawave: invalid response")
	ErrUnavailable         = errors.New("remnawave: unavailable")
	ErrNotFound            = errors.New("remnawave: user not found")
	ErrPanelAuthentication = errors.New("remnawave: panel authentication failed")
)

type Status string

const (
	StatusActive   Status = "ACTIVE"
	StatusDisabled Status = "DISABLED"
	StatusLimited  Status = "LIMITED"
	StatusExpired  Status = "EXPIRED"
)

type TrafficStrategy string

const (
	StrategyNoReset      TrafficStrategy = "NO_RESET"
	StrategyDay          TrafficStrategy = "DAY"
	StrategyWeek         TrafficStrategy = "WEEK"
	StrategyMonth        TrafficStrategy = "MONTH"
	StrategyMonthRolling TrafficStrategy = "MONTH_ROLLING"
)

type UserSnapshot struct {
	ID                   int64
	Status               Status
	ExpiresAt            time.Time
	TrafficLimitBytes    int64 // Zero means unlimited in Remnawave.
	TrafficLimitStrategy TrafficStrategy
	UsedTrafficBytes     int64
	HWIDDeviceLimit      *int64
}

type Client struct {
	origin string
	token  string
	http   *http.Client
}

// NewClient accepts a panel origin, not a subscription URL. A supplied HTTP
// client is copied so callers cannot remove the timeout or redirect guard.
func NewClient(origin, adminToken string, httpClient *http.Client) (*Client, error) {
	u, err := url.Parse(origin)
	if err != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") || u.RawPath != "" || u.Opaque != "" || adminToken == "" {
		return nil, ErrInvalidConfig
	}
	if u.Scheme != "https" && !(u.Scheme == "http" && isLoopback(u.Hostname())) {
		return nil, ErrInvalidConfig
	}
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	copyClient := *httpClient
	if copyClient.Timeout == 0 || copyClient.Timeout > requestTimeout {
		copyClient.Timeout = requestTimeout
	}
	copyClient.CheckRedirect = func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}
	return &Client{origin: strings.TrimSuffix(u.String(), "/"), token: adminToken, http: &copyClient}, nil
}

func isLoopback(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// Lookup resolves a subscription bearer once, then reads the canonical numeric
// user. Neither the bearer nor the administrative token is returned or logged.
func (c *Client) Lookup(ctx context.Context, shortUUID string) (UserSnapshot, error) {
	if err := ctx.Err(); err != nil {
		return UserSnapshot{}, err
	}
	if shortUUID == "" || len(shortUUID) > 256 {
		return UserSnapshot{}, ErrInvalidConfig
	}
	requestBody, _ := json.Marshal(struct {
		ShortUUID string `json:"shortUuid"`
	}{ShortUUID: shortUUID})
	resolveBody, err := c.request(ctx, http.MethodPost, "/api/users/resolve", requestBody)
	if err != nil {
		return UserSnapshot{}, err
	}
	var resolved struct {
		Response struct {
			ID        int64  `json:"id"`
			ShortUUID string `json:"shortUuid"`
		} `json:"response"`
	}
	if json.Unmarshal(resolveBody, &resolved) != nil || resolved.Response.ID <= 0 || resolved.Response.ShortUUID != shortUUID {
		return UserSnapshot{}, ErrInvalidResponse
	}
	userBody, err := c.request(ctx, http.MethodGet, "/api/users/"+strconv.FormatInt(resolved.Response.ID, 10), nil)
	if err != nil {
		return UserSnapshot{}, err
	}
	var user struct {
		Response struct {
			ID                   int64           `json:"id"`
			ShortUUID            string          `json:"shortUuid"`
			Status               Status          `json:"status"`
			ExpiresAt            string          `json:"expireAt"`
			TrafficLimitBytes    *int64          `json:"trafficLimitBytes"`
			TrafficLimitStrategy TrafficStrategy `json:"trafficLimitStrategy"`
			HWIDDeviceLimit      *int64          `json:"hwidDeviceLimit"`
			UserTraffic          *struct {
				UsedTrafficBytes *int64 `json:"usedTrafficBytes"`
			} `json:"userTraffic"`
		} `json:"response"`
	}
	if json.Unmarshal(userBody, &user) != nil {
		return UserSnapshot{}, ErrInvalidResponse
	}
	u := user.Response
	expiresAt, err := time.Parse(time.RFC3339Nano, u.ExpiresAt)
	if err != nil || u.ID != resolved.Response.ID || u.ShortUUID != shortUUID || !knownStatus(u.Status) || !knownStrategy(u.TrafficLimitStrategy) || u.TrafficLimitBytes == nil || *u.TrafficLimitBytes < 0 || u.UserTraffic == nil || u.UserTraffic.UsedTrafficBytes == nil || *u.UserTraffic.UsedTrafficBytes < 0 {
		return UserSnapshot{}, ErrInvalidResponse
	}
	return UserSnapshot{
		ID:                   u.ID,
		Status:               u.Status,
		ExpiresAt:            expiresAt,
		TrafficLimitBytes:    *u.TrafficLimitBytes,
		TrafficLimitStrategy: u.TrafficLimitStrategy,
		UsedTrafficBytes:     *u.UserTraffic.UsedTrafficBytes,
		HWIDDeviceLimit:      u.HWIDDeviceLimit,
	}, nil
}

func knownStatus(status Status) bool {
	switch status {
	case StatusActive, StatusDisabled, StatusLimited, StatusExpired:
		return true
	default:
		return false
	}
}

func knownStrategy(strategy TrafficStrategy) bool {
	switch strategy {
	case StrategyNoReset, StrategyDay, StrategyWeek, StrategyMonth, StrategyMonthRolling:
		return true
	default:
		return false
	}
}

func (c *Client) request(ctx context.Context, method, path string, body []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.origin+path, bytes.NewReader(body))
	if err != nil {
		return nil, ErrInvalidConfig
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := c.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, ErrUnavailable
	}
	defer response.Body.Close()
	switch response.StatusCode {
	case http.StatusOK:
	case http.StatusNotFound:
		return nil, ErrNotFound
	case http.StatusUnauthorized, http.StatusForbidden:
		return nil, ErrPanelAuthentication
	default:
		return nil, ErrUnavailable
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return nil, ErrUnavailable
	}
	if len(data) > maxResponseBytes {
		return nil, ErrInvalidResponse
	}
	return data, nil
}
