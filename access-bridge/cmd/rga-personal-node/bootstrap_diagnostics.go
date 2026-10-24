package main

import (
	"fmt"
	"strings"
	"sync/atomic"
)

// Closed, destination/identity-free outcomes. No error, request, URL, key or
// callback is retained. Request processing performs only one atomic increment;
// the owner emits one summary after shutting down the bootstrap server.
type bootstrapOutcome uint8

const (
	bootstrapBoundary bootstrapOutcome = iota
	bootstrapGlobalRate
	bootstrapBeginRequest
	bootstrapInstallation
	bootstrapPublicKey
	bootstrapDeviceRate
	bootstrapLinkDenied
	bootstrapLinkUnavailable
	bootstrapBeginCreated
	bootstrapCompleteRequest
	bootstrapSignature
	bootstrapCompleteDenied
	bootstrapCompleteUnavailable
	bootstrapCompletePrepared
	bootstrapPath
	bootstrapConfig
	bootstrapOutcomeCount
)

type bootstrapDiagnostics struct {
	counts [bootstrapOutcomeCount]atomic.Uint64
}

func (g *bootstrapRateGuard) observeBootstrap(outcome bootstrapOutcome) {
	if g != nil && outcome < bootstrapOutcomeCount {
		g.diagnostics.counts[outcome].Add(1)
	}
}

func (g *bootstrapRateGuard) bootstrapSummary() string {
	names := [...]string{"boundary", "global_rate", "begin_request", "installation", "public_key",
		"device_rate", "link_denied", "link_unavailable", "begin_created", "complete_request",
		"signature", "complete_denied", "complete_unavailable", "complete_prepared", "path", "config"}
	var out strings.Builder
	out.WriteString("rga_bootstrap_summary")
	for i, name := range names {
		fmt.Fprintf(&out, " %s=%d", name, g.diagnostics.counts[i].Load())
	}
	return out.String()
}
