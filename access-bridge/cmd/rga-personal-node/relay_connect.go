package main

import (
	"fmt"
	"sync/atomic"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

// ai-generated: optional destination-free diagnostic adapter.
type relayConnectDiagnostics struct {
	counts [8]atomic.Uint64
	emit   func(string)
}

// ai-generated: stage adapter.
func (d *relayConnectDiagnostics) observe(stage tunnel.ConnectObservation) {
	stages := [...]tunnel.ConnectObservation{tunnel.ConnectEntry, tunnel.ConnectLeaseDenied,
		tunnel.ConnectDialBegin, tunnel.ConnectDialFailed, tunnel.ConnectAborted,
		tunnel.ConnectAckFailed, tunnel.ConnectAckOK, tunnel.ConnectUpstreamData}
	for i, allowed := range stages {
		if stage == allowed {
			d.counts[i].Add(1) // No logging or external callback on the forwarding path.
			return
		}
	}
}

// ai-generated: final diagnostic summary.
func (d *relayConnectDiagnostics) summary() {
	if d.emit != nil {
		d.emit(fmt.Sprintf("rga_connect_summary entry=%d lease_denied=%d dial_begin=%d dial_failed=%d aborted=%d ack_failed=%d ack_ok=%d upstream_data=%d",
			d.counts[0].Load(), d.counts[1].Load(), d.counts[2].Load(), d.counts[3].Load(),
			d.counts[4].Load(), d.counts[5].Load(), d.counts[6].Load(), d.counts[7].Load()))
	}
}
