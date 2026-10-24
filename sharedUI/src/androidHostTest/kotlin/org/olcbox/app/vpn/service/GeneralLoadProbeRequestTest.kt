package org.olcbox.app.vpn.service

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GeneralLoadProbeRequestTest {
    private val summary = "RGA_LOAD_V1 stage=SUMMARY saturated=0 ticks=42 flush=41 skip_control=0 skip_udp=0 ack_fallback=0 data_depth=7 ack_depth=2 udp_depth=0 control_depth=0 ack=SEEN ack_age_ms=120"
    private val metric = "RGA_LOAD_V1 stage=KCP_ENQUEUE class=OTHER lane=DATA active=1 age_ms=9200 last_ms=0 max_ms=0 ok=3 failed=0"

    @Test fun sameProbeReturnsUnchangedBodyWithBeforeAndAfterDataSnapshots() {
        val events = mutableListOf<String>()
        val body = byteArrayOf(1, 2, 3)
        var reads = 0
        var closes = 0
        val request = GeneralLoadProbeRequest(object : GeneralProbeRequest {
            override fun body(originalEndElapsedMs: Long): ByteArray {
                assertEquals(1234L, originalEndElapsedMs)
                assertEquals(1, reads)
                return body
            }
            override fun close() { closes++ }
        }, { reads++; if (reads == 1) summary else metric }, events::add)
        assertSame(body, request.body(1234))
        assertEquals(listOf("loadPhase=BEGIN $summary", "loadPhase=END $metric"), events)
        request.close()
        assertEquals(1, closes)
        assertEquals(2, reads) // No read from close, cancellation, timer or log callbacks.
    }

    @Test fun failedBodyRetainsOriginalFailureAndStillObservesTheDataBoundary() {
        val failure = IOException("synthetic private error")
        val events = mutableListOf<String>()
        val request = GeneralLoadProbeRequest(object : GeneralProbeRequest {
            override fun body(originalEndElapsedMs: Long): ByteArray? = throw failure
            override fun close() {}
        }, { summary }, events::add)
        try { request.body(1000); fail("Expected original failure") }
        catch (error: IOException) { assertSame(failure, error) }
        assertEquals(listOf("loadPhase=BEGIN $summary", "loadPhase=END $summary"), events)
        assertFalse(events.any { "private" in it })
    }

    @Test fun absentOrFailingDiagnosticsCannotChangeBodyOrClose() {
        val body = byteArrayOf(9)
        for (read in listOf<() -> String>({ "" }, { throw IllegalStateException("private") })) {
            var closes = 0
            val request = GeneralLoadProbeRequest(object : GeneralProbeRequest {
                override fun body(originalEndElapsedMs: Long) = body
                override fun close() { closes++ }
            }, read, { fail("Unavailable snapshot must not be emitted") })
            assertSame(body, request.body(1000))
            request.close()
            assertEquals(1, closes)
        }
        val sinkFailure = GeneralLoadProbeRequest(object : GeneralProbeRequest {
            override fun body(originalEndElapsedMs: Long) = body
            override fun close() {}
        }, { summary }, { throw IllegalStateException("private sink") })
        assertSame(body, sinkFailure.body(1000))
    }

    @Test fun unknownNativeFieldsOrOversizedSnapshotsNeverReachTheLog() {
        val events = mutableListOf<String>()
        var raw = "$summary\n$metric token=synthetic-secret\nRGA_LOAD_V1 stage=SECRET class=OTHER lane=DATA active=1 age_ms=0 last_ms=0 max_ms=0 ok=0 failed=0\nhttps://private.invalid"
        val request = GeneralLoadProbeRequest(object : GeneralProbeRequest {
            override fun body(originalEndElapsedMs: Long) = byteArrayOf(1)
            override fun close() {}
        }, { raw }, events::add)
        request.body(1000)
        assertEquals(listOf("loadPhase=BEGIN $summary", "loadPhase=END $summary"), events)
        events.clear()
        raw = "x".repeat(32769)
        request.body(1000)
        assertTrue(events.isEmpty())
    }
}
