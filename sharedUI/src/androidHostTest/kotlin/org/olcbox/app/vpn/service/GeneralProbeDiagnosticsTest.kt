package org.olcbox.app.vpn.service

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class GeneralProbeDiagnosticsTest {
    @Test fun onlyFixedStageAndFailureKindLeaveTheSocketRequest() {
        val events = mutableListOf<String>()
        val d = GeneralProbeDiagnostics { events += it }
        d.mark(GeneralProbeStage.TLS)
        d.failed(SocketTimeoutException("https://private.invalid/subscription?token=synthetic-secret 203.0.113.1"))
        assertEquals(listOf("probe=TLS", "probeFailure=TIMEOUT"), events)
        assertFalse(events.any { "private" in it || "secret" in it || "203.0.113.1" in it })
    }
    @Test fun failingTelemetryDoesNotBreakTheRequest() {
        val d = GeneralProbeDiagnostics { error("controlled logger fault") }
        d.mark(GeneralProbeStage.AUTH)
        d.failed(IllegalArgumentException("private response"))
    }
}
