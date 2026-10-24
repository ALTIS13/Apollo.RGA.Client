package org.olcbox.app.vpn.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import org.olcbox.app.vpn.AndroidGeneralRuntimeOwner
import org.olcbox.app.net.GeneralTestFixture

/** Controlled monotonic time models carrier latency, not native/device acceptance. */
class GeneralCellularBodyProbeBudgetTest {
    @Test fun validForeignBodyAfterSixSecondsWithinOriginalHandoffIsAccepted(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        f.bodyAction = { f.elapsedOffset = 6_000L }
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, f.options, f.ops))
        assertEquals(GeneralTunnelPhase.TransportVerified, f.phase)
        assertTrue(f.events.contains("body"))
        assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!, 500))
        assertFalse(f.consumer.hasOwnedRuntime())
    }

    @Test fun localFifteenSecondCapRejectsEvenAWellFormedLateBody(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        f.bodyAction = { f.elapsedOffset = 16_000L }
        assertEquals(GeneralServiceResult.OrdinaryRestored, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
        assertFalse(f.consumer.hasOwnedRuntime())
        assertTrue(f.events.indexOf("stop") < f.events.indexOf("restore"))
    }

    @Test fun originalThirtySecondEndStillRejectsAndPhysicallySettlesLateBody(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        f.bodyAction = { f.elapsedOffset = 31_000L }
        assertFalse(f.consumer.handoff(f.binding, f.options, f.ops) == GeneralServiceResult.TransportVerified)
        assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
        assertFalse(f.consumer.hasOwnedRuntime())
        assertTrue(f.events.indexOf("close") < f.events.indexOf("stop"))
    }

    @Test fun priorStartupTimeClipsProbeToOriginalEndRatherThanMintingNewTime(): Unit = runBlocking {
        for (finish in listOf(27_000L, 32_000L)) {
            val f = AndroidGeneralServiceConsumerTest.Fixture()
            val clock = AtomicLong(1_000L)
            val epoch = { GeneralTestFixture.now + 300_001L }
            var capturedEnd = 0L
            val owner = AndroidGeneralRuntimeOwner(f.factory, clock::get, epoch)
            val consumer = AndroidGeneralServiceConsumer(owner, { object : GeneralProbeRequest {
                override fun body(originalEndElapsedMs: Long): ByteArray {
                    capturedEnd = originalEndElapsedMs
                    clock.set(finish)
                    return f.body.copyOf()
                }
                override fun close() { f.events += "close" }
            } }, clock::get, epoch)
            val ops = GeneralServiceOperations(f.ops.current, f.ops.stopBridge,
                { clock.set(21_000L); f.ops.stopOrdinary() }, f.ops.startBridge,
                f.ops.restoreOrdinary, f.ops.bridgeEpoch, f.ops.publish)
            val result = consumer.handoff(f.binding, f.options, ops)
            assertEquals(31_000L, capturedEnd)
            if (finish < capturedEnd) {
                assertEquals(GeneralServiceResult.TransportVerified, result)
                assertTrue(consumer.stopCaptured(consumer.retire()!!, 500))
            } else {
                assertFalse(result == GeneralServiceResult.TransportVerified)
                assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
            }
            assertFalse(consumer.hasOwnedRuntime())
        }
    }
}
