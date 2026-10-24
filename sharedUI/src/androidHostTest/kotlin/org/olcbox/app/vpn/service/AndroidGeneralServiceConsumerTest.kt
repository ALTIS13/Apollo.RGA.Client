package org.olcbox.app.vpn.service

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.net.GeneralBootstrapSelection
import org.olcbox.app.net.GeneralBootstrapV1
import org.olcbox.app.net.GeneralTestFixture
import org.olcbox.app.net.PersonalJoinSigningKey
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.vpn.*

class AndroidGeneralServiceConsumerTest {
    @Test fun reserveAdvanceRequiresPhysicalSettlementAndNeverRunsOrdinaryRestore(): Unit = runBlocking {
        val f = Fixture(); f.body = byteArrayOf()
        val ops = GeneralServiceOperations(f.ops.current, f.ops.stopBridge, f.ops.stopOrdinary,
            f.ops.startBridge, f.ops.restoreOrdinary, f.ops.bridgeEpoch, f.ops.publish)
        assertEquals(GeneralServiceResult.CandidateRetired, f.consumer.handoff(f.binding, f.options, ops, restoreOnFailure = false))
        assertFalse(f.consumer.hasOwnedRuntime())
        assertTrue(f.events.contains("stop"))
        assertFalse(f.events.contains("restore"))
    }
    @Test fun unknownCleanupAndSupersededSelectionCannotAdvanceToReserve(): Unit = runBlocking {
        for (superseded in listOf(false, true)) {
            val f = Fixture(); f.body = byteArrayOf()
            if (superseded) f.bodyAction = { f.current = false } else f.badStop = true
            val ops = GeneralServiceOperations(f.ops.current, f.ops.stopBridge, f.ops.stopOrdinary,
                f.ops.startBridge, f.ops.restoreOrdinary, f.ops.bridgeEpoch, f.ops.publish)
            assertNotEquals(GeneralServiceResult.CandidateRetired, f.consumer.handoff(f.binding, f.options, ops, restoreOnFailure = false))
            assertFalse(f.events.contains("restore"))
            if (!superseded) {
                assertTrue(f.consumer.hasOwnedRuntime())
                f.badStop = false
                assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!, 1000))
            }
        }
    }
    @Test fun dataSnapshotsBelongToTheActualHandoffProbeAndDoNotRunDuringStop(): Unit = runBlocking {
        val f = Fixture()
        val snapshot = "RGA_LOAD_V1 stage=MUX_SEND class=OTHER lane=DATA active=0 age_ms=0 last_ms=2 max_ms=2 ok=4 failed=0"
        f.loadSnapshot = snapshot
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, f.options, f.ops))
        assertEquals(listOf("loadPhase=BEGIN $snapshot", "loadPhase=END $snapshot"), f.loadEvents.toList())
        assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!, 1000))
        assertEquals(2, f.loadReads)
    }
    @Test fun r19JoinedFailedNotificationCanRetryBeforeDependentCloseJoins(): Unit = runBlocking {
        val nativeRequested = CountDownLatch(1); val closeEntered = CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger(); val overlap = java.util.concurrent.atomic.AtomicBoolean()
        val f = Fixture()
        f.stopAction = {
            if (active.incrementAndGet() != 1) overlap.set(true)
            try {
                if (attempts.incrementAndGet() == 1) error("controlled first stop failure before cancellation")
                nativeRequested.countDown()
            } finally { active.decrementAndGet() }
        }
        f.closeAction = { closeEntered.countDown(); check(nativeRequested.await(5, TimeUnit.SECONDS)) }
        val handoff = launch(Dispatchers.Default) { f.consumer.handoff(f.binding, f.options, f.ops) }
        try {
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            val ticket = f.consumer.retire()!!
            assertFalse(f.consumer.stopCaptured(ticket, 200))
            assertEquals(1, attempts.get())
            assertTrue(f.consumer.hasOwnedRuntime())
            assertFalse(f.consumer.hasKnownSettlement(ticket))
            assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.start(2, f.selection, f.key, f.options, 100))
            // Bounded separate cleanup attempts. A still-live previous worker
            // must remain retained; only a physically joined failed job retries.
            val retryEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (nativeRequested.count != 0L && System.nanoTime() < retryEnd) f.consumer.stopCaptured(ticket, 200)
            assertEquals("JOINED_FAILED_NATIVE_STOP_NEVER_RENOTIFIED", 0L, nativeRequested.count)
            withTimeout(3_000) { handoff.join() }
            assertTrue(f.consumer.stopCaptured(ticket, 700))
            assertTrue(f.consumer.hasKnownSettlement(ticket))
            assertFalse(f.consumer.hasOwnedRuntime())
            assertFalse(overlap.get())
            assertEquals(2, attempts.get())
        } finally {
            nativeRequested.countDown()
            f.consumer.retire()?.let { f.consumer.stopCaptured(it, 2_000) }
            withTimeout(3_000) { handoff.cancelAndJoin() }
        }
    }

    @Test fun r19NativeNotificationBreaksProbeCloseDependency(): Unit = runBlocking {
        val nativeRequested = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val f = Fixture()
        f.stopAction = { nativeRequested.countDown() }
        f.closeAction = {
            closeEntered.countDown()
            check(nativeRequested.await(3, TimeUnit.SECONDS)) { "controlled native-close dependency" }
        }
        val handoff = launch(Dispatchers.Default) { f.consumer.handoff(f.binding, f.options, f.ops) }
        try {
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            val ticket = f.consumer.retire()!!
            f.consumer.stopCaptured(ticket, 700)
            assertEquals("NATIVE_STOP_NOT_NOTIFIED_BEFORE_PROBE_JOIN", 0L, nativeRequested.count)
            // The handoff's failure cleanup may own the concurrent final Stop;
            // join that caller before requiring idempotent exact settlement.
            withTimeout(3_000) { handoff.join() }
            assertTrue(f.consumer.stopCaptured(ticket, 700))
            assertFalse(f.consumer.hasOwnedRuntime())
            assertTrue(f.consumer.hasKnownSettlement(ticket))
        } finally {
            nativeRequested.countDown()
            f.consumer.retire()?.let { f.consumer.stopCaptured(it, 2_000) }
            withTimeout(3_000) { handoff.cancelAndJoin() }
        }
    }

    @Test fun r19NotificationDoesNotReleaseRuntimeOwnerBeforeLocalJoin(): Unit = runBlocking {
        val nativeRequested = CountDownLatch(1); val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1); val f = Fixture()
        f.stopAction = { nativeRequested.countDown() }
        f.closeAction = { closeEntered.countDown(); check(releaseClose.await(3, TimeUnit.SECONDS)) }
        val handoff = launch(Dispatchers.Default) { f.consumer.handoff(f.binding, f.options, f.ops) }
        try {
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))
            val ticket = f.consumer.retire()!!
            assertFalse(f.consumer.stopCaptured(ticket, 200))
            assertEquals(0L, nativeRequested.count)
            assertTrue(f.consumer.hasOwnedRuntime())
            assertFalse(f.consumer.hasKnownSettlement(ticket))
            assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.start(2, f.selection, f.key, f.options, 100))
            releaseClose.countDown()
            assertTrue(f.consumer.stopCaptured(ticket, 1_000))
            assertTrue(f.consumer.hasKnownSettlement(ticket))
            assertEquals(1, f.events.count { it == "stop" })
        } finally {
            releaseClose.countDown()
            f.consumer.retire()?.let { f.consumer.stopCaptured(it, 2_000) }
            withTimeout(3_000) { handoff.cancelAndJoin() }
        }
    }

    @Test fun r2RawAndTlsCloseReceiptsKeepOnlyFailedExactSocketForRetry() {
        val custody = GeneralProbeSocketCustody()
        var rawCalls = 0; var tlsCalls = 0; var failTls = true
        val raw = custody.allocate { object : java.net.Socket() {
            override fun close() { rawCalls++ }
        } }
        val tls = custody.allocate { object : java.net.Socket() {
            override fun close() { tlsCalls++; if (failTls) throw java.io.IOException("controlled close") }
        } }
        try { custody.close(); fail("failed TLS close must be unknown") } catch (_: java.io.IOException) { }
        assertEquals(1, rawCalls); assertEquals(1, tlsCalls)
        try { custody.allocate { java.net.Socket() }; fail("no new allocation after close") } catch (_: IllegalStateException) { }
        failTls = false; custody.close(); custody.close()
        assertEquals(1, rawCalls); assertEquals(2, tlsCalls)
        assertNotSame(raw, tls)
    }
    @Test fun r2ThrowingCloseRetainsSameProbeUntilKnownRetry(): Unit = runBlocking {
        val f = Fixture(); var failClose = true
        val closeThreads = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
        f.closeAction = { closeThreads += Thread.currentThread().threadId(); if (failClose) error("controlled close failure") }
        assertEquals(GeneralServiceResult.Unavailable, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
        assertTrue(f.consumer.hasOwnedRuntime())
        assertEquals(GeneralServiceResult.Busy, f.consumer.handoff(f.binding, f.options, f.ops))
        val ticket = f.consumer.retire()!!
        assertFalse(f.consumer.stopCaptured(ticket, 250))
        failClose = false
        assertTrue(f.consumer.stopCaptured(ticket, 500))
        assertTrue(f.consumer.stopCaptured(ticket, 500))
        assertEquals(1, closeThreads.size)
        assertEquals(1, f.events.count { it == "body" })
        assertFalse(f.consumer.hasOwnedRuntime())
    }
    @Test fun genuineBodySettlesThenPublishesOnlyTransportVerifiedOnTheSameHeldTun(): Unit = runBlocking {
        val f = Fixture()
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, f.options, f.ops))
        assertEquals(listOf("bridge-stop", "ordinary-stop", "init", "start", "wait", "bridge-start", "body", "close"), f.events)
        assertEquals(GeneralTunnelPhase.TransportVerified, f.phase)
        assertTrue(GeneralTunnelUiState(f.phase).fullTunQualificationPending)
        val capture = f.consumer.retire()!!
        assertTrue(f.consumer.stopCaptured(capture, 500)); assertFalse(f.consumer.hasOwnedRuntime())
    }

    @Test fun lateCurrentFenceNeverPublishesAndUnknownStopBlocksReplacement(): Unit = runBlocking {
        val f = Fixture()
        f.bodyAction = { f.current = false }
        assertFalse(f.consumer.handoff(f.binding, f.options, f.ops) == GeneralServiceResult.TransportVerified)
        assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
        val stuck = Fixture(); stuck.badStop = true; stuck.body = "not a response".toByteArray()
        assertEquals(GeneralServiceResult.Unavailable, stuck.consumer.handoff(stuck.binding, stuck.options, stuck.ops))
        assertTrue(stuck.consumer.hasOwnedRuntime())
        assertEquals(GeneralServiceResult.Busy, stuck.consumer.handoff(stuck.binding, stuck.options, stuck.ops))
        val ticket = stuck.consumer.retire()!!
        stuck.badStop = false; assertTrue(stuck.consumer.stopCaptured(ticket, 500))
        assertFalse(stuck.consumer.hasOwnedRuntime())
        assertFalse(stuck.events.contains("restore"))
    }

    @Test fun knownFailureMayRestoreOrdinaryButReadyWithoutBodyCannotClaimSuccess(): Unit = runBlocking {
        val f = Fixture(); f.body = "ip=203.0.113.1\n".toByteArray()
        assertEquals(GeneralServiceResult.OrdinaryRestored, f.consumer.handoff(f.binding, f.options, f.ops))
        assertTrue(f.events.indexOf("stop") < f.events.indexOf("restore"))
        assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
    }

    @Test fun knownOrdinaryRestoreReleasesGeneralCustodyRatherThanRecheckingRetiredBinding(): Unit = runBlocking {
        val f = Fixture(); f.body = "invalid".toByteArray(); f.restoreAction = { f.current = false }
        assertEquals(GeneralServiceResult.OrdinaryRestored, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.consumer.hasOwnedRuntime())
        assertTrue(f.events.indexOf("stop") < f.events.indexOf("restore"))
    }

    @Test fun cancellationClosesAndPhysicallySettlesLocalWorkerBeforeKnownSettlement(): Unit = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val f = Fixture()
        f.bodyAction = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); f.events += "body-settled" }
        f.closeAction = { release.countDown() }
        val job = launch(Dispatchers.Default) { f.consumer.handoff(f.binding, f.options, f.ops) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertFalse(f.consumer.hasOwnedRuntime())
            assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
            assertTrue(f.events.indexOf("close") < f.events.indexOf("body-settled"))
            // Native cancellation may be notified before the body joins; only
            // the full joined settlement can release the consumer's custody.
            assertFalse(f.consumer.hasOwnedRuntime())
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    @Test fun keyBridgeAndOriginalProbeEndDriftRejectLatePositiveBodies(): Unit = runBlocking {
        for (drift in listOf<(Fixture) -> Unit>({ it.keyChanged = true }, { it.bridgeEpoch++ }, { it.elapsedOffset = 31_000 })) {
            val f = Fixture(); f.bodyAction = { drift(f) }
            assertFalse(f.consumer.handoff(f.binding, f.options, f.ops) == GeneralServiceResult.TransportVerified)
            assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
            assertFalse(f.consumer.hasOwnedRuntime())
        }
    }

    @Test fun staleDiscoveryMissingTunnelExceptionAndWrongTargetBodyDoNotGrant(): Unit = runBlocking {
        val expired = Fixture(); expired.epochOffset = 86_400_000
        assertEquals(GeneralServiceResult.Unavailable, expired.consumer.handoff(expired.binding, expired.options, expired.ops))
        assertTrue(expired.events.isEmpty())
        val direct = Fixture()
        val options = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "domain:cloudflare.com")
        assertEquals(GeneralServiceResult.Unavailable, direct.consumer.handoff(direct.binding, options, direct.ops))
        assertTrue(direct.events.isEmpty())
        for (body in listOf("h=wrong.invalid\nip=203.0.113.1\nloc=NL\nvisit_scheme=https\n",
            "h=cloudflare.com\nip=203.0.113.1\nloc=NL\nvisit_scheme=https\nh=cloudflare.com\n")) {
            val f = Fixture(); f.body = body.toByteArray()
            assertEquals(GeneralServiceResult.OrdinaryRestored, f.consumer.handoff(f.binding, f.options, f.ops))
            assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
        }
    }

    @Test fun usableDiscoveryCannotReplaceFreshNativePreparedProof(): Unit = runBlocking {
        val f = Fixture(); f.serverDeniesReady = true
        assertEquals(GeneralServiceResult.OrdinaryRestored, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.events.contains("body"))
        assertFalse(f.phase == GeneralTunnelPhase.TransportVerified)
        assertTrue(f.events.indexOf("stop") < f.events.indexOf("restore"))
    }

    @Test fun knownSettlementIsIdempotentOnlyForTheExactCapturedTicket(): Unit = runBlocking {
        val f = Fixture()
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, f.options, f.ops))
        val ticket = f.consumer.retire()!!
        assertTrue(f.consumer.stopCaptured(ticket, 500))
        // Cancellation may complete this same stop before the queued actual-service cleanup enters its mutex.
        assertTrue(f.consumer.stopCaptured(ticket, 500))
        assertFalse(f.consumer.stopCaptured(AndroidGeneralServiceConsumer.CleanupTicket(ticket.tun, ticket.ownerGeneration), 500))
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, f.options, f.ops))
        assertFalse(f.consumer.stopCaptured(ticket, 500))
        assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!, 500))
    }

    internal class Fixture {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        var current = true; var badStop = false; var phase = GeneralTunnelPhase.Inactive
        var serverDeniesReady = false
        @Volatile var keyChanged = false
        @Volatile var bridgeEpoch = 2L
        @Volatile var elapsedOffset = 0L
        @Volatile var epochOffset = 300_001L
        var body = "h=cloudflare.com\nip=203.0.113.10\nloc=NL\nvisit_scheme=https\n".toByteArray()
        var bodyAction: () -> Unit = {}
        var closeAction: () -> Unit = {}
        var stopAction: () -> Unit = {}
        var restoreAction: () -> Unit = {}
        var requestCreatedAction: () -> Unit = {}
        var restoreSucceeded = true
        private val delegateKey = GeneralTestFixture.signer()
        val key = object : PersonalJoinSigningKey {
            override val installationId get() = if (keyChanged) "changed_installation_0001" else delegateKey.installationId
            override val publicKeyDer get() = delegateKey.publicKeyDer.copyOf()
            override fun sign(transcript: ByteArray) = delegateKey.sign(transcript)
        }
        val selection = GeneralBootstrapSelection.fromVerifiedReceipt(
            GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!,
            key, GeneralTestFixture.now, GeneralTestFixture.now)!!
        val binding = GeneralServiceBinding(1, Any(), Any(), PhysicalNetworkSnapshot(1, 10, 2,
            UpstreamTransport.Cellular, true, false, false, listOf("1.1.1.1")), "selected", GeneralTestFixture.link, selection, key)
        val loadEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        var loadSnapshot = ""
        var loadReads = 0
        val options = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "tunnel-domain:cloudflare.com\n",
            log = { if (it.startsWith("loadPhase=")) loadEvents += it })
        val handle = object : AndroidGeneralRuntimeHandle() {
            var running = false
            override fun readLoadDiagnostics(): String { loadReads++; return loadSnapshot }
            override fun initialize() { events += "init" }
            override fun listenerReleased() = !running
            override fun setProtector(protect: (Long) -> Boolean) {}
            override fun setLogWriter(writeLog: (String) -> Unit) {}
            override fun setSocksPort(value: Long) {}
            override fun setSocksCredentials(username: String, password: String) {}
            override fun setDNS(value: String) {}
            override fun setSocksListenHost(value: String) {}
            override fun setVP8Options(fps: Long, batch: Long) {}
            override fun setDirectRules(value: String) {}
            override fun setUDP(enabled: Boolean) {}
            override fun start() { running = true; events += "start" }
            override fun waitReady(timeoutMs: Long) { events += "wait"; if (serverDeniesReady) error("controlled native denial") }
            override fun stop(timeoutMs: Long) { events += "stop"; stopAction(); if (badStop) error("controlled"); running = false }
            override fun isRunning() = running
        }
        val factory = object : AndroidGeneralSelectionRuntimeHandleFactory {
            override fun create(metadata: GeneralBootstrapV1, installation: PersonalJoinSigningKey) = handle
            override fun create(selection: GeneralBootstrapSelection, installation: PersonalJoinSigningKey) = handle
        }
        val owner = AndroidGeneralRuntimeOwner(factory,
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + elapsedOffset }, { GeneralTestFixture.now + epochOffset })
        val consumer = AndroidGeneralServiceConsumer(owner,
            { requestCreatedAction(); object : GeneralProbeRequest {
                override fun body(originalEndElapsedMs: Long): ByteArray { events += "body"; bodyAction(); return body.copyOf() }
                override fun close() { events += "close"; closeAction() }
            } }, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + elapsedOffset }, { GeneralTestFixture.now + epochOffset })
        val ops = GeneralServiceOperations({ current }, { events += "bridge-stop"; true },
            { events += "ordinary-stop"; true }, { events += "bridge-start"; true },
            { events += "restore"; restoreAction(); restoreSucceeded }, { bridgeEpoch }, { phase = it })
    }
}
