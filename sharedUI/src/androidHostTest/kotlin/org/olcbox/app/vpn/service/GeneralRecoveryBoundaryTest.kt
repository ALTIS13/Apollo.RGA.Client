package org.olcbox.app.vpn.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GeneralRecoveryBoundaryTest {
    @Test fun expiredBeforeProbeStartStillClosesAndStopsTheCapturedNative(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        f.requestCreatedAction = { f.elapsedOffset = 31_000 }
        assertEquals(GeneralServiceResult.OrdinaryRestored, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.events.contains("body"))
        assertTrue(f.events.indexOf("close") in 0 until f.events.indexOf("stop"))
        assertFalse(f.consumer.hasOwnedRuntime())
    }

    @Test fun failedRestoreKeepsExactKnownStopReceiptForBoundedRecovery(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        f.body = "invalid".toByteArray(); f.restoreSucceeded = false
        assertEquals(GeneralServiceResult.Unavailable, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.consumer.hasOwnedRuntime())
        val ticket = f.consumer.captureRecoveryTicket(f.binding)
        assertNotNull(ticket)
        assertTrue(f.consumer.hasKnownSettlement(ticket!!))
        assertTrue(f.consumer.stopCaptured(ticket, 500))
        val wrong = copyBinding(f.binding)
        assertNull(f.consumer.captureRecoveryTicket(wrong))
        f.body = "h=cloudflare.com\nip=203.0.113.10\nloc=NL\nvisit_scheme=https\n".toByteArray()
        f.restoreSucceeded = true
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(wrong, f.options, f.ops))
        assertNull(f.consumer.captureRecoveryTicket(f.binding))
        assertFalse(f.consumer.stopCaptured(ticket, 500))
        assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!, 500))
    }

    @Test fun anotherBindingCannotRetireTheCurrentNativeOwner(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, f.options, f.ops))
        assertNull(f.consumer.captureRecoveryTicket(copyBinding(f.binding)))
        assertTrue(f.consumer.isRunning())
        assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!, 500))
    }

    @Test fun unknownStopRetainsSameOwnerAndCannotMintKnownReceipt(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        f.body = "invalid".toByteArray(); f.badStop = true
        assertEquals(GeneralServiceResult.Unavailable, f.consumer.handoff(f.binding, f.options, f.ops))
        val ticket = f.consumer.captureRecoveryTicket(f.binding)!!
        assertFalse(f.consumer.hasKnownSettlement(ticket))
        assertTrue(f.consumer.hasOwnedRuntime())
        f.badStop = false
        assertTrue(f.consumer.stopCaptured(ticket, 500))
        assertSame(ticket, f.consumer.captureRecoveryTicket(f.binding))
    }

    private fun copyBinding(b: GeneralServiceBinding) = GeneralServiceBinding(b.serviceGeneration,
        b.tun, b.network, b.snapshot, b.storageId, b.link, b.selection, b.key)
}
