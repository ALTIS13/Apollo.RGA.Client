package org.olcbox.app.vpn.service

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.net.GeneralTestFixture

class AndroidGeneralProcessCustodyTest {
    @Test fun r3BeginCleanupCapturedJobsAreRegisteredAtomically(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        var armed = false
        val registry = AndroidGeneralProcessCustody {
            if (armed) { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        }
        val f = AndroidGeneralServiceConsumerTest.Fixture(); val tun = Closeable { }
        val binding = GeneralServiceBinding(1, tun, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
        val lease = registry.acquire(Any(), { f.consumer }, {})!!
        val ticket = f.consumer.retire()!!
        lease.beginCleanup(ticket, tun, null, emptyList()); lease.closeTun(tun)
        assertTrue(f.consumer.stopCaptured(ticket, 500)); lease.recordTransportStopped()
        val exactJob = CompletableDeferred<Unit>()
        armed = true
        val registration = Thread {
            try { lease.beginCleanup(ticket, null, null, listOf(exactJob)) } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        val releasedDuringRegistration: Boolean
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            releasedDuringRegistration = lease.tryRelease()
            release.countDown(); assertTrue(done.await(1, TimeUnit.SECONDS)); registration.join(1_000)
            assertFalse(lease.tryRelease()) // Exact captured cleanup Job is still incomplete.
        } finally { release.countDown(); registration.join(1_000); exactJob.complete(Unit) }
        assertFalse("beginCleanup registration=$releasedDuringRegistration", releasedDuringRegistration)
        assertFalse(registry.owns(lease)) // Only completion of the exact registered Job permits release.
    }
    @Test fun r3InFlightCallbackAndPostSnapshotJobRegistrationCannotEscapeRetirement(): Unit = runBlocking {
        data class Settled(val registry: AndroidGeneralProcessCustody, val f: AndroidGeneralServiceConsumerTest.Fixture,
            val lease: AndroidGeneralProcessCustody.Lease)
        suspend fun settled(callback: () -> Unit): Settled {
            val registry = AndroidGeneralProcessCustody(); val f = AndroidGeneralServiceConsumerTest.Fixture()
            val tun = Closeable { }
            val binding = GeneralServiceBinding(1, tun, f.binding.network, f.binding.snapshot, "selected",
                GeneralTestFixture.link, f.selection, f.key)
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
            val lease = registry.acquire(Any(), { f.consumer }, callback)!!
            val ticket = f.consumer.retire()!!
            lease.beginCleanup(ticket, tun, null, emptyList()); lease.closeTun(tun)
            assertTrue(f.consumer.stopCaptured(ticket, 500)); lease.recordTransportStopped()
            return Settled(registry, f, lease)
        }

        val callbackEntered = CountDownLatch(1); val callbackRelease = CountDownLatch(1)
        val callbackDone = CountDownLatch(1); val delayedJob = CompletableDeferred<Unit>()
        val callbackCalls = java.util.concurrent.atomic.AtomicInteger()
        lateinit var fromCallback: Settled
        fromCallback = settled {
            callbackCalls.incrementAndGet()
            callbackEntered.countDown(); check(callbackRelease.await(2, TimeUnit.SECONDS))
            fromCallback.lease.observeJobs(listOf(delayedJob))
        }
        val dispatch = Thread { try { fromCallback.registry.retryForeign(Any()) } finally { callbackDone.countDown() } }
            .apply { isDaemon = true; start() }
        val releasedWithCallback: Boolean
        try {
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
            releasedWithCallback = fromCallback.lease.tryRelease()
            callbackRelease.countDown(); assertTrue(callbackDone.await(1, TimeUnit.SECONDS)); dispatch.join(1_000)
            assertFalse(fromCallback.lease.tryRelease()) // The callback's SAME Job is still incomplete.
        } finally { callbackRelease.countDown(); dispatch.join(1_000); delayedJob.complete(Unit) }

        val afterSnapshot = settled { }
        val newJob = CompletableDeferred<Unit>(); val recheckDone = CountDownLatch(1)
        var releasedWithNewJob = false
        val consumerLock = AndroidGeneralServiceConsumer::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(afterSnapshot.f.consumer)
        val releaseThread = Thread {
            try { releasedWithNewJob = afterSnapshot.lease.tryRelease() } finally { recheckDone.countDown() }
        }.apply { isDaemon = true }
        try {
            synchronized(consumerLock) {
                releaseThread.start()
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (releaseThread.state != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield()
                assertEquals(Thread.State.BLOCKED, releaseThread.state)
                // The exact owner check is held AFTER the ready snapshot, not a new resource/owner.
                afterSnapshot.lease.observeJobs(listOf(newJob))
            }
            assertTrue(recheckDone.await(1, TimeUnit.SECONDS)); releaseThread.join(1_000)
            assertFalse(afterSnapshot.lease.tryRelease()) // New SAME-custody Job remains incomplete.
        } finally { newJob.complete(Unit); releaseThread.join(1_000) }
        assertFalse("callback=$releasedWithCallback postSnapshotJob=$releasedWithNewJob", releasedWithCallback || releasedWithNewJob)
        assertFalse(fromCallback.registry.owns(fromCallback.lease))
        assertFalse(afterSnapshot.registry.owns(afterSnapshot.lease))
        assertFalse(fromCallback.lease.requestCleanup()); assertEquals(1, callbackCalls.get())
        fromCallback.lease.observeJobs(listOf(CompletableDeferred<Unit>()))
        assertFalse(fromCallback.lease.tryRelease()) // A released/stale lease cannot reopen or release a replacement.
    }
    @Test fun knownStopCannotReleaseFailedExactCloseOrOldBridgeAndJob(): Unit = runBlocking {
        val registry = AndroidGeneralProcessCustody()
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        var failClose = true; var closes = 0; var factories = 0; var retries = 0
        val tun = Closeable { closes++; if (failClose) throw IOException("controlled close") }
        val binding = GeneralServiceBinding(1, tun, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
        val old = Any(); val replacement = Any()
        val lease = registry.acquire(old, { factories++; f.consumer }, { retries++ })!!
        val workerRelease = CountDownLatch(1); val workerEntered = CountDownLatch(1)
        val bridge = Thread { workerEntered.countDown(); workerRelease.await() }.apply { isDaemon = true; start() }
        val oldJob = CompletableDeferred<Unit>()
        try {
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS))
            val ticket = f.consumer.retire()!!
            lease.beginCleanup(ticket, tun, bridge, listOf(oldJob))
            lease.recordTransportStopped() // A necessary controlled legacy receipt alone grants nothing.
            assertFalse(lease.tryRelease())
            assertTrue(f.consumer.stopCaptured(ticket, 500))
            try { lease.closeTun(tun); fail("thrown exact close must retain custody") } catch (_: IOException) { }
            assertTrue(registry.retryForeign(replacement)); assertEquals(1, retries)
            assertNull(registry.acquire(replacement, { factories++; f.consumer }, {})); assertEquals(1, factories)
            failClose = false; lease.closeTun(tun)
            assertFalse(lease.tryRelease()) // SAME bridge remains physically live.
            workerRelease.countDown(); bridge.join(1_000)
            assertFalse(lease.tryRelease()) // SAME old coroutine job is not complete.
            oldJob.complete(Unit)
            assertFalse(registry.owns(lease)) // Exact completion callback releases only now.
            val next = registry.acquire(replacement, { factories++; f.consumer }, {})!!
            assertEquals(2, factories); assertEquals(2, closes)
            assertFalse(lease.tryRelease()); assertTrue(registry.owns(next))
            assertEquals("GeneralProcessLease([redacted])", lease.toString())
        } finally { workerRelease.countDown(); bridge.join(1_000); oldJob.complete(Unit) }
    }

    @Test fun copiedTicketAndCloseFlagCannotReplaceSameConsumerKnownSettlement(): Unit = runBlocking {
        val registry = AndroidGeneralProcessCustody(); val f = AndroidGeneralServiceConsumerTest.Fixture()
        val tun = Closeable { }
        val binding = GeneralServiceBinding(1, tun, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
        val lease = registry.acquire(Any(), { f.consumer }, {})!!
        val ticket = f.consumer.retire()!!
        val copy = AndroidGeneralServiceConsumer.CleanupTicket(tun, ticket.ownerGeneration)
        lease.beginCleanup(copy, tun, null, emptyList()); lease.closeTun(tun); lease.recordTransportStopped()
        assertFalse(lease.tryRelease())
        assertTrue(f.consumer.stopCaptured(ticket, 500)); assertFalse(lease.tryRelease())
        lease.beginCleanup(null, null, null, emptyList()); assertFalse(lease.tryRelease())
        lease.beginCleanup(ticket, null, null, emptyList()); assertTrue(lease.tryRelease())
    }
}
