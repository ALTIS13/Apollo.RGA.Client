package main

import (
	"strings"
	"sync"
	"testing"

	"github.com/openlibrecommunity/olcrtc/pkg/olcrtc/tunnel"
)

// ai-generated: bounded observations must not emit arbitrary callback values.
func TestRelayConnectDiagnosticsBoundedConcurrentStages(t *testing.T) {
	var output []string
	var mu sync.Mutex
	d := relayConnectDiagnostics{emit: func(line string) {
		mu.Lock()
		defer mu.Unlock()
		output = append(output, line)
	}}
	stages := []tunnel.ConnectObservation{tunnel.ConnectEntry, tunnel.ConnectLeaseDenied,
		tunnel.ConnectDialBegin, tunnel.ConnectDialFailed, tunnel.ConnectAborted,
		tunnel.ConnectAckFailed, tunnel.ConnectAckOK, tunnel.ConnectObservation("UPSTREAM_DATA")}
	var workers sync.WaitGroup
	for range 20 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for _, stage := range stages {
				d.observe(stage)
			}
			d.observe(tunnel.ConnectObservation("private-destination-must-not-be-logged"))
		}()
	}
	workers.Wait()
	if len(output) != 0 {
		t.Fatal("diagnostic I/O ran on the forwarding path")
	}
	d.summary()
	if len(output) != 1 {
		t.Fatalf("diagnostic output unbounded or stages missing: lines=%d", len(output))
	}
	for _, line := range output {
		if strings.Contains(line, "private") {
			t.Fatal("untrusted observation value leaked")
		}
	}
	if output[0] != "rga_connect_summary entry=20 lease_denied=20 dial_begin=20 dial_failed=20 aborted=20 ack_failed=20 ack_ok=20 upstream_data=20" {
		t.Fatalf("cumulative observations lost or misattributed: %s", output[0])
	}
}
