package org.olcbox.app.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.olcbox.app.data.repository.SubscriptionFetchProxy

class AndroidSelectedPathProbeTest {
    private val proxy = SubscriptionFetchProxy("127.0.0.1", 10818)

    @Test fun failedBulkThroughCurrentOrdinaryCoreIsTransportFailureNotTunProof() = runBlocking {
        var calls = 0
        val probe = AndroidSelectedPathProbe { _ -> calls++; false }

        val result = probe.collect(
            selectedOnCurrentNetwork = true,
            coreRunning = true,
            bridgeReady = true,
            proxy = proxy
        )

        assertEquals(1, calls)
        assertEquals(SelectedPathEvidence(ProbeOutcome.Failed, ProbeOutcome.Unknown), result)
    }

    @Test fun answeredBulkStillDoesNotClaimIndependentTunReadiness() = runBlocking {
        val probe = AndroidSelectedPathProbe { true }

        val result = probe.collect(true, true, true, proxy)

        assertEquals(SelectedPathEvidence(ProbeOutcome.Answered, ProbeOutcome.Unknown), result)
    }

    @Test fun staleCoreOrBrokenBridgeCannotBecomeRestrictionEvidence() = runBlocking {
        var calls = 0
        val probe = AndroidSelectedPathProbe { _ -> calls++; false }

        assertEquals(SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Unknown),
            probe.collect(false, true, true, proxy))
        assertEquals(SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Unknown),
            probe.collect(true, false, true, proxy))
        assertEquals(SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Failed),
            probe.collect(true, true, false, proxy))
        assertEquals(0, calls)
    }

    @Test fun unexpectedProbeErrorIsUnknownAndCancellationPropagates() = runBlocking {
        val failed = AndroidSelectedPathProbe { throw IllegalStateException("test failure") }
        assertEquals(SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Unknown),
            failed.collect(true, true, true, proxy))

        val cancelled = AndroidSelectedPathProbe { throw CancellationException("superseded") }
        assertThrows(CancellationException::class.java) {
            runBlocking { cancelled.collect(true, true, true, proxy) }
        }
        Unit
    }

    @Test fun ruleBasedFrontIsNotASelectedTransportProbeUnlessRawBackendExists() {
        assertNull(AndroidSelectedPathProbe.transportPort(10818, null, hasRoutingFront = true))
        assertEquals(10901, AndroidSelectedPathProbe.transportPort(10818, 10901, hasRoutingFront = true))
        assertEquals(10818, AndroidSelectedPathProbe.transportPort(10818, null, hasRoutingFront = false))
    }
}
