package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/access"
	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

type linkBeginner interface {
	BeginLink(context.Context, string, string, []byte) (access.LinkChallenge, error)
}

type bootstrapCompleter interface {
	CompleteLinkAndPrefetch(context.Context, string, string, []byte, []byte) (personal.BootstrapV1, error)
}

const (
	maxBootstrapRequestBytes = 4096
	bootstrapRateWindow      = time.Minute
	bootstrapGlobalPerMinute = 30
	bootstrapDevicePerMinute = 20
)

var challengeIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)

// One immutable installation is configured on this node; the device bucket
// needs no client-chosen key and cannot grow without bound.
type bootstrapRateGuard struct {
	mu                             sync.Mutex
	now                            func() time.Time
	windowStart                    time.Time
	global, perInstallation        int
	globalCount, installationCount int
	diagnostics                    bootstrapDiagnostics
}

func newBootstrapRateGuard(now func() time.Time, global, perInstallation int) *bootstrapRateGuard {
	return &bootstrapRateGuard{now: now, global: global, perInstallation: perInstallation}
}

func (g *bootstrapRateGuard) allow(installation bool) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	if g.windowStart.IsZero() || now.Before(g.windowStart) || !now.Before(g.windowStart.Add(bootstrapRateWindow)) {
		g.windowStart, g.globalCount, g.installationCount = now, 0, 0
	}
	if installation {
		if g.installationCount >= g.perInstallation {
			return false
		}
		g.installationCount++
		return true
	}
	if g.globalCount >= g.global {
		return false
	}
	g.globalCount++
	return true
}

type beginBootstrapRequest struct {
	ShortUUID      string `json:"shortUuid"`
	InstallationID string `json:"installationId"`
	PublicKeyDER   string `json:"publicKeyDer"`
}

type completeBootstrapRequest struct {
	ShortUUID    string `json:"shortUuid"`
	ChallengeID  string `json:"challengeId"`
	PublicKeyDER string `json:"publicKeyDer"`
	Signature    string `json:"signature"`
}

func newBootstrapHandler(binding access.InstallationBinding, link linkBeginner, complete bootstrapCompleter, guard *bootstrapRateGuard) http.Handler {
	return newBootstrapHandlerWithBoundary(binding, link, complete, guard, bootstrapRequestBoundary)
}

func newBootstrapHandlerWithBoundary(binding access.InstallationBinding, link linkBeginner, complete bootstrapCompleter, guard *bootstrapRateGuard, boundary func(*http.Request) bool) http.Handler {
	return newBootstrapHandlerForPaths(binding, link, complete, guard, boundary,
		"/v1/personal/link/begin", "/v1/personal/bootstrap",
		func(w http.ResponseWriter, result personal.BootstrapV1) {
			_ = json.NewEncoder(w).Encode(result)
		})
}

// Both explicit discovery modes share the same boundary, strict decoder,
// installation proof binding, rate guard and actual link/completer calls.
// Paths and response encoding are server composition, never request metadata.
func newBootstrapHandlerForPaths(binding access.InstallationBinding, link linkBeginner, complete bootstrapCompleter, guard *bootstrapRateGuard, boundary func(*http.Request) bool, beginPath, completePath string, writeComplete func(http.ResponseWriter, personal.BootstrapV1)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if boundary == nil || !boundary(r) {
			guard.observeBootstrap(bootstrapBoundary)
			bootstrapError(w, http.StatusBadRequest, "denied")
			return
		}
		if !guard.allow(false) {
			guard.observeBootstrap(bootstrapGlobalRate)
			bootstrapError(w, http.StatusTooManyRequests, "denied")
			return
		}
		switch r.URL.Path {
		case beginPath:
			var request beginBootstrapRequest
			if !decodeBootstrapRequest(r.Body, &request, map[string]bool{"shortUuid": true, "installationId": true, "publicKeyDer": true}) ||
				!validBearer(request.ShortUUID) {
				guard.observeBootstrap(bootstrapBeginRequest)
				bootstrapError(w, http.StatusBadRequest, "denied")
				return
			}
			if request.InstallationID != binding.ID {
				guard.observeBootstrap(bootstrapInstallation)
				bootstrapError(w, http.StatusBadRequest, "denied")
				return
			}
			der, ok := boundBase64URL(request.PublicKeyDER, 256)
			if !ok || !matchesBootstrapBinding(der, binding) {
				guard.observeBootstrap(bootstrapPublicKey)
				bootstrapError(w, http.StatusBadRequest, "denied")
				return
			}
			if !guard.allow(true) {
				guard.observeBootstrap(bootstrapDeviceRate)
				bootstrapError(w, http.StatusTooManyRequests, "denied")
				return
			}
			challenge, err := link.BeginLink(r.Context(), request.ShortUUID, request.InstallationID, der)
			if err != nil {
				if errors.Is(err, access.ErrLinkDenied) {
					guard.observeBootstrap(bootstrapLinkDenied)
				} else {
					guard.observeBootstrap(bootstrapLinkUnavailable)
				}
				bootstrapServiceError(w, err, access.ErrLinkDenied)
				return
			}
			guard.observeBootstrap(bootstrapBeginCreated)
			_ = json.NewEncoder(w).Encode(struct {
				ChallengeID   string `json:"challengeId"`
				PanelInstance string `json:"panelInstance"`
				Nonce         string `json:"nonce"`
				ExpiresAt     string `json:"expiresAt"`
			}{challenge.ID, challenge.Subject.PanelInstance, base64.RawURLEncoding.EncodeToString(challenge.Nonce[:]), challenge.ExpiresAt.UTC().Format(time.RFC3339Nano)})
		case completePath:
			var request completeBootstrapRequest
			if !decodeBootstrapRequest(r.Body, &request, map[string]bool{"shortUuid": true, "challengeId": true, "publicKeyDer": true, "signature": true}) ||
				!validBearer(request.ShortUUID) || !challengeIDPattern.MatchString(request.ChallengeID) {
				guard.observeBootstrap(bootstrapCompleteRequest)
				bootstrapError(w, http.StatusBadRequest, "denied")
				return
			}
			der, ok := boundBase64URL(request.PublicKeyDER, 256)
			if !ok || !matchesBootstrapBinding(der, binding) {
				guard.observeBootstrap(bootstrapPublicKey)
				bootstrapError(w, http.StatusBadRequest, "denied")
				return
			}
			sig, ok := boundBase64URL(request.Signature, 80)
			if !ok {
				guard.observeBootstrap(bootstrapSignature)
				bootstrapError(w, http.StatusBadRequest, "denied")
				return
			}
			if !guard.allow(true) {
				guard.observeBootstrap(bootstrapDeviceRate)
				bootstrapError(w, http.StatusTooManyRequests, "denied")
				return
			}
			result, err := complete.CompleteLinkAndPrefetch(r.Context(), request.ShortUUID, request.ChallengeID, der, sig)
			if err != nil {
				if errors.Is(err, personal.ErrBootstrapDenied) {
					guard.observeBootstrap(bootstrapCompleteDenied)
				} else {
					guard.observeBootstrap(bootstrapCompleteUnavailable)
				}
				bootstrapServiceError(w, err, personal.ErrBootstrapDenied)
				return
			}
			guard.observeBootstrap(bootstrapCompletePrepared)
			writeComplete(w, result)
		default:
			guard.observeBootstrap(bootstrapPath)
			bootstrapError(w, http.StatusNotFound, "denied")
		}
	})
}

func bootstrapRequestBoundary(r *http.Request) bool {
	return bootstrapRequestBoundaryWithIngress(r, "", "")
}

func validBearer(value string) bool {
	return value != "" && len(value) <= 256 && strings.TrimSpace(value) == value
}

func boundBase64URL(value string, maxDecoded int) ([]byte, bool) {
	if value == "" || len(value) > base64.RawURLEncoding.EncodedLen(maxDecoded) {
		return nil, false
	}
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	return decoded, err == nil && len(decoded) > 0 && len(decoded) <= maxDecoded && base64.RawURLEncoding.EncodeToString(decoded) == value
}

func matchesBootstrapBinding(der []byte, binding access.InstallationBinding) bool {
	fingerprint, err := access.FingerprintLinkPublicKey(der)
	return err == nil && fingerprint == binding.PublicKeySHA256
}

func decodeBootstrapRequest(body io.ReadCloser, out any, allowed map[string]bool) bool {
	defer body.Close()
	data, err := io.ReadAll(io.LimitReader(body, maxBootstrapRequestBytes+1))
	if err != nil || len(data) > maxBootstrapRequestBytes {
		return false
	}
	d := json.NewDecoder(bytes.NewReader(data))
	first, err := d.Token()
	if err != nil || first != json.Delim('{') {
		return false
	}
	seen := make(map[string]bool, len(allowed))
	for d.More() {
		token, err := d.Token()
		name, ok := token.(string)
		if err != nil || !ok || !allowed[name] || seen[name] {
			return false
		}
		seen[name] = true
		var raw json.RawMessage
		if d.Decode(&raw) != nil {
			return false
		}
	}
	last, err := d.Token()
	if err != nil || last != json.Delim('}') || d.Decode(new(any)) != io.EOF {
		return false
	}
	for name := range allowed {
		if !seen[name] {
			return false
		}
	}
	return json.Unmarshal(data, out) == nil
}

func bootstrapError(w http.ResponseWriter, status int, kind string) {
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": kind})
}

func bootstrapServiceError(w http.ResponseWriter, err, denied error) {
	if errors.Is(err, denied) {
		bootstrapError(w, http.StatusForbidden, "denied")
	} else {
		bootstrapError(w, http.StatusServiceUnavailable, "unavailable")
	}
}
