package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.olcbox.app.net.ProbeOutcome.Answered
import org.olcbox.app.net.ProbeOutcome.Failed
import org.olcbox.app.net.ProbeOutcome.Unknown
import org.olcbox.app.net.RestrictedAssessment.Inconclusive
import org.olcbox.app.net.RestrictedAssessment.LocalBridgeFailure
import org.olcbox.app.net.RestrictedAssessment.RestrictedSuspect

class RestrictedNetworkClassifierTest {
    private fun round(
        generation: Long,
        networkId: Long,
        revision: Long,
        time: Long,
        foreignA: ProbeOutcome = Failed,
        foreignB: ProbeOutcome = Failed,
        domestic: ProbeOutcome = Answered
    ) = DirectProbeRound(generation, networkId, revision, time, foreignA, foreignB, domestic)

    @Test fun stableCellularWithTwoFailedForeignControlsAndFailedBulkIsSuspect() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)

        assertFalse(detector.begin(1, 17, 1, 4_999))
        assertTrue(detector.begin(1, 17, 1, 5_000))
        assertTrue(detector.record(round(1, 17, 1, 5_000)))
        assertEquals(Inconclusive, detector.finish(1, 17, 1, 5_000, Failed, Unknown))
        assertFalse(detector.record(round(1, 17, 1, 7_999)))
        assertTrue(detector.record(round(1, 17, 1, 8_000)))
        assertEquals(RestrictedSuspect, detector.finish(1, 17, 1, 8_000, Failed, Unknown))
        assertFalse(detector.begin(1, 17, 1, 64_999))
    }

    @Test fun sameRevisionDoesNotResetSettling() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 4_999)
        assertTrue(detector.begin(1, 17, 1, 5_000))

        val changed = RestrictedNetworkClassifier()
        changed.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
        changed.onNetwork(1, 17, 2, cellular = true, captive = false, nowMs = 4_999)
        assertFalse(changed.begin(1, 17, 2, 5_000))
        assertTrue(changed.begin(1, 17, 2, 9_999))
    }

    @Test fun oneForeignAnswerOrDomesticFailureIsInconclusive() {
        fun assess(foreignA: ProbeOutcome, domestic: ProbeOutcome): RestrictedAssessment {
            val detector = RestrictedNetworkClassifier()
            detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
            assertTrue(detector.begin(1, 17, 1, 5_000))
            assertTrue(detector.record(round(1, 17, 1, 5_000, foreignA = foreignA, domestic = domestic)))
            assertTrue(detector.record(round(1, 17, 1, 8_000)))
            return detector.finish(1, 17, 1, 8_000, Failed, Unknown)
        }

        assertEquals(Inconclusive, assess(Answered, Answered))
        assertEquals(Inconclusive, assess(Failed, Failed))
        assertEquals(Inconclusive, assess(Failed, Unknown))
    }

    @Test fun workingSocksWithBrokenBridgeIsLocalFailure() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
        assertTrue(detector.begin(1, 17, 1, 5_000))
        assertTrue(detector.record(round(1, 17, 1, 5_000)))
        assertTrue(detector.record(round(1, 17, 1, 8_000)))
        assertEquals(LocalBridgeFailure, detector.finish(1, 17, 1, 8_000, Answered, Failed))
    }

    @Test fun staleGenerationNetworkAndDnsRevisionAreRejected() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
        assertTrue(detector.begin(1, 17, 1, 5_000))
        assertTrue(detector.record(round(1, 17, 1, 5_000)))

        detector.onNetwork(1, 17, 2, cellular = true, captive = false, nowMs = 5_001)
        assertFalse(detector.record(round(1, 17, 1, 8_000)))
        assertEquals(Inconclusive, detector.finish(1, 17, 1, 8_000, Failed, Unknown))
        detector.onNetwork(2, 18, 1, cellular = true, captive = false, nowMs = 10_000)
        assertFalse(detector.record(round(1, 17, 2, 13_000)))
        assertFalse(detector.record(round(2, 17, 1, 13_000)))
        assertEquals(Inconclusive, detector.finish(1, 17, 2, 13_000, Failed, Unknown))
        assertFalse(detector.begin(2, 18, 1, 14_999))
    }

    @Test fun olderRevisionCallbackCannotReplaceActiveAttempt() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 2, cellular = true, captive = false, nowMs = 0)
        assertTrue(detector.begin(1, 17, 2, 5_000))
        assertTrue(detector.record(round(1, 17, 2, 5_000)))

        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 5_500)

        assertTrue(detector.record(round(1, 17, 2, 8_000)))
        assertEquals(RestrictedSuspect, detector.finish(1, 17, 2, 8_000, Failed, Unknown))
    }

    @Test fun manualCancelAndCooldownPreventNewAttempt() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
        assertTrue(detector.begin(1, 17, 1, 5_000))
        detector.cancel(1)
        assertFalse(detector.record(round(1, 17, 1, 8_000)))
        assertEquals(Inconclusive, detector.finish(1, 17, 1, 8_000, Failed, Unknown))

        detector.onNetwork(2, 99, 1, cellular = false, captive = false, nowMs = 10_000)
        assertFalse(detector.begin(2, 99, 1, 15_000))
        detector.onNetwork(2, 17, 2, cellular = true, captive = false, nowMs = 60_000)
        assertFalse(detector.begin(2, 17, 2, 64_999))
        assertTrue(detector.begin(2, 17, 2, 65_000))
        assertTrue(detector.record(round(2, 17, 2, 65_000)))
        assertTrue(detector.record(round(2, 17, 2, 68_000)))
        assertEquals(RestrictedSuspect, detector.finish(2, 17, 2, 68_000, Failed, Unknown))
        assertFalse(detector.begin(2, 17, 2, 184_999))
        assertTrue(detector.begin(2, 17, 2, 185_000))

        val captive = RestrictedNetworkClassifier()
        captive.onNetwork(1, 20, 1, cellular = true, captive = true, nowMs = 0)
        assertFalse(captive.begin(1, 20, 1, 5_000))
        val backwards = RestrictedNetworkClassifier()
        backwards.onNetwork(1, 21, 1, cellular = true, captive = false, nowMs = 5_000)
        assertFalse(backwards.begin(1, 21, 1, 4_999))
    }

    @Test fun cancelOfNewGenerationInvalidatesOlderAttempt() {
        val detector = RestrictedNetworkClassifier()
        detector.onNetwork(1, 17, 1, cellular = true, captive = false, nowMs = 0)
        assertTrue(detector.begin(1, 17, 1, 5_000))

        detector.cancel(2)
        assertFalse(detector.record(round(1, 17, 1, 5_000)))
        assertEquals(Inconclusive, detector.finish(1, 17, 1, 8_000, Failed, Unknown))
    }
}
