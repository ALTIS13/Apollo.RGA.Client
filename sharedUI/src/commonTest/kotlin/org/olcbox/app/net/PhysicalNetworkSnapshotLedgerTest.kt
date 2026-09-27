package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.olcbox.app.vpn.UpstreamTransport

class PhysicalNetworkSnapshotLedgerTest {
    @Test fun equivalentCallbacksKeepRevisionAndSettling() {
        val ledger = PhysicalNetworkSnapshotLedger()
        val classifier = RestrictedNetworkClassifier()
        val available = ledger.available(1, 17)!!
        assertEquals(0, available.revision)
        ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, true, false)
        val first = ledger.linkProperties(1, 17, 1, listOf("192.0.2.1"))!!
        classifier.onNetwork(1, 17, first.revision, cellular = true, captive = false, nowMs = 0)

        val repeatedCaps = ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, true, false)!!
        val repeatedLink = ledger.linkProperties(1, 17, 1, listOf("192.0.2.1"))!!
        assertEquals(first.revision, repeatedCaps.revision)
        assertEquals(first.revision, repeatedLink.revision)
        classifier.onNetwork(1, 17, repeatedLink.revision, cellular = true, captive = false, nowMs = 4_999)
        assertTrue(classifier.begin(1, 17, first.revision, 5_000))
    }

    @Test fun changedDnsOrLinkAdvancesRevisionAndInvalidatesOldRound() {
        val ledger = PhysicalNetworkSnapshotLedger()
        val classifier = RestrictedNetworkClassifier()
        ledger.available(1, 17)
        ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, true, false)
        val first = ledger.linkProperties(1, 17, 1, listOf("192.0.2.1"))!!
        classifier.onNetwork(1, 17, first.revision, cellular = true, captive = false, nowMs = 0)
        assertTrue(classifier.begin(1, 17, first.revision, 5_000))
        assertTrue(classifier.record(DirectProbeRound(1, 17, first.revision, 5_000,
            ProbeOutcome.Failed, ProbeOutcome.Failed, ProbeOutcome.Answered)))

        val changedDns = ledger.linkProperties(1, 17, 2, listOf("192.0.2.2"))!!
        assertEquals(first.revision + 1, changedDns.revision)
        classifier.onNetwork(1, 17, changedDns.revision, cellular = true, captive = false, nowMs = 5_001)
        assertFalse(classifier.record(DirectProbeRound(1, 17, first.revision, 8_000,
            ProbeOutcome.Failed, ProbeOutcome.Failed, ProbeOutcome.Answered)))
        assertEquals(RestrictedAssessment.Inconclusive,
            classifier.finish(1, 17, first.revision, 8_000, ProbeOutcome.Failed, ProbeOutcome.Unknown))

        val changedLink = ledger.linkProperties(1, 17, 3, listOf("192.0.2.2"))!!
        assertEquals(changedDns.revision + 1, changedLink.revision)
    }

    @Test fun lowerLinkRevisionCannotReplaceCurrentFacts() {
        val ledger = PhysicalNetworkSnapshotLedger()
        ledger.available(1, 17)
        val first = ledger.linkProperties(1, 17, 5, listOf("192.0.2.1", "192.0.2.2"))!!
        val stale = ledger.linkProperties(1, 17, 4, listOf("192.0.2.3"))!!
        assertEquals(first.revision, stale.revision)
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), stale.dnsServers)
    }

    @Test fun lostAndCancelledNetworkCannotReappearFromLateCallback() {
        val ledger = PhysicalNetworkSnapshotLedger()
        ledger.available(1, 17)
        ledger.lost(1, 17)
        assertNull(ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, true, false))
        assertNull(ledger.available(1, 17))
        assertNull(ledger.current(1, 17))

        ledger.available(1, 18)
        ledger.cancel(1)
        assertNull(ledger.linkProperties(1, 18, 1, listOf("192.0.2.1")))
        assertNull(ledger.available(1, 19))
        assertNull(ledger.available(0, 20))
        assertEquals(2, ledger.available(2, 21)?.generation)
    }

    @Test fun incompleteOrCaptiveFactsAreNotProbeReady() {
        val ledger = PhysicalNetworkSnapshotLedger()
        assertFalse(ledger.available(1, 17)!!.isProbeReady)
        assertFalse(ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, false, false)!!.isProbeReady)
        assertFalse(ledger.linkProperties(1, 17, 1, emptyList())!!.isProbeReady)
        assertTrue(ledger.linkProperties(1, 17, 2, listOf("192.0.2.1"))!!.isProbeReady)
        assertFalse(ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, true, true)!!.isProbeReady)
        assertTrue(ledger.capabilities(1, 17, UpstreamTransport.Cellular, true, true, false)!!.isProbeReady)
        assertFalse(ledger.capabilities(1, 17, UpstreamTransport.Wifi, true, true, false)!!.isProbeReady)
        assertFalse(ledger.capabilities(1, 17, UpstreamTransport.Cellular, false, false, false)!!.isProbeReady)
    }
}
