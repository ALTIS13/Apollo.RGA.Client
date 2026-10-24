package org.olcbox.app.vpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.GeneralBootstrapV1
import org.olcbox.app.net.GeneralTestFixture
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidGeneralRuntimeOwnerTest {
    @Test fun failedVkStartupReportsNativeBoundaryButNeverItsPrivateException() {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handle = ControlledHandle().apply {
            afterWait = { throw GeneralNativeFailure.from(IllegalStateException(
                "run public client: vkcalls: RGA_VK_STARTUP stage=AUTH_ANON class=DNS code=0 private-secret")) }
        }
        val f = fixture(handle)
        val observed = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "", log = { messages += it })
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, f.owner.start(1, metadata(), GeneralTestFixture.signer(), observed, 500))
        assertTrue(messages.contains("providerStage=AUTH_ANON providerClass=DNS providerCode=0"))
        assertFalse(messages.any { "private-secret" in it })
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.start(2))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
    }
    @Test fun failedStartupPublishesSafeCauseAndStillRequiresOwnedStop() {
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handle = ControlledHandle().apply {
            afterWait = { throw GeneralNativeFailure.from(IllegalStateException("preconnect private-secret")) }
        }
        val f = fixture(handle)
        val observed = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "", log = { messages += it })
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN,
            f.owner.start(1, metadata(), GeneralTestFixture.signer(), observed, 500))
        assertTrue(messages.contains("startupFailure=PROVIDER_BOOTSTRAP"))
        assertTrue(messages.contains("stage=FAILED"))
        assertFalse(messages.any { "private-secret" in it })
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.start(2))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
    }
    @Test fun typedSelectionCannotFallThroughLegacyMetadataFactory() {
        val key = GeneralTestFixture.signer()
        val selected = org.olcbox.app.net.GeneralBootstrapSelection.fromVerifiedReceipt(metadata(), key,
            GeneralTestFixture.now, GeneralTestFixture.now)!!
        var legacyCalls = 0
        val legacy = AndroidGeneralRuntimeOwner(AndroidGeneralRuntimeHandleFactory { _, _ -> legacyCalls++; ControlledHandle() },
            { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, { GeneralTestFixture.now + 300_001 })
        assertEquals(AndroidGeneralRuntimeResult.DENIED, legacy.start(1, selected, key, options, 500))
        assertEquals(0, legacyCalls)
        val typed = AndroidGeneralRuntimeOwner(object : AndroidGeneralSelectionRuntimeHandleFactory {
            override fun create(metadata: GeneralBootstrapV1, installation: org.olcbox.app.net.PersonalJoinSigningKey): AndroidGeneralRuntimeHandle = error("Legacy fallback")
            override fun create(selection: org.olcbox.app.net.GeneralBootstrapSelection, installation: org.olcbox.app.net.PersonalJoinSigningKey) = ControlledHandle()
        }, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, { GeneralTestFixture.now + 300_001 })
        assertEquals(AndroidGeneralRuntimeResult.READY, typed.start(1, selected, key, options, 500))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, typed.stop(1, 500))
    }

    @Test fun loadSnapshotCannotReadAnAbsentStaleOrStoppedOwner() {
        val f = fixture()
        var reads = 0
        f.handle.loadSnapshot = { reads++; "synthetic snapshot" }
        assertEquals("", f.owner.readLoadDiagnostics(1))
        assertEquals(AndroidGeneralRuntimeResult.READY, f.start(10))
        assertEquals("", f.owner.readLoadDiagnostics(9))
        assertEquals(0, reads)
        assertEquals("synthetic snapshot", f.owner.readLoadDiagnostics(10))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(10, 500))
        assertEquals("", f.owner.readLoadDiagnostics(10))
        assertEquals(1, reads)
    }

    @Test fun loadReadDoesNotHoldOwnerLockAndItsLateResultIsDiscardedAfterStop() {
        val f = fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<String>()
        f.handle.loadSnapshot = { entered.countDown(); release.await(); "old snapshot" }
        assertEquals(AndroidGeneralRuntimeResult.READY, f.start(1))
        val reader = Thread { result.set(f.owner.readLoadDiagnostics(1)) }.apply { isDaemon = true }
        try {
            reader.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        } finally { release.countDown(); reader.join(2000) }
        assertFalse(reader.isAlive)
        assertEquals("", result.get())
    }

    private val options = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "")
    private fun metadata() = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
    private fun fixture(handle: ControlledHandle = ControlledHandle()): Fixture = Fixture(handle)

    @Test fun stopFailureAndMissingListenerWitnessRetainTheSameOwner() {
        val f = fixture(); assertEquals(AndroidGeneralRuntimeResult.READY, f.start(1))
        f.handle.stopFailure = true; f.handle.running = false
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, f.owner.stop(1, 500))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.start(2)); assertEquals(1, f.created)
        f.handle.stopFailure = false; f.handle.released = false
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, f.owner.stop(1, 500))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.start(2))
        f.handle.released = true
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        assertEquals(AndroidGeneralRuntimeResult.READY, f.start(2)); assertEquals(2, f.created)
    }

    @Test fun staleGenerationCannotReadReadyOrStopOrReplaceTheCurrentGeneration() {
        val f = fixture(); assertEquals(AndroidGeneralRuntimeResult.READY, f.start(10))
        assertFalse(f.owner.isReady(9))
        assertEquals(AndroidGeneralRuntimeResult.DENIED, f.owner.stop(9, 500))
        assertTrue(f.owner.isReady(10)); assertEquals(0, f.handle.stopCalls)
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(10, 500))
        assertEquals(AndroidGeneralRuntimeResult.DENIED, f.start(10))
        assertEquals(AndroidGeneralRuntimeResult.READY, f.start(11))
        assertEquals(AndroidGeneralRuntimeResult.DENIED, f.owner.stop(10, 500))
        assertTrue(f.owner.isReady(11))
    }

    @Test fun legacySelectionClaimsAndDiagnosticsAreDeniedWithoutAnyNativeCall() {
        val handle = ControlledHandle()
        val denied = listOf<() -> Unit>(
            { handle.setProvider("other") }, { handle.setRoom("other") },
            { handle.setDeviceID("service-hwid") }, { handle.setKey("other") },
            { handle.setTransport("other") }, { handle.setClaimsJSON("{}") },
            { handle.check("", "", "", "", "", 1, 1, 1, 1) },
            { handle.ping("", "", "", "", "", 1, 1, "private", 1, 1) }
        )
        for (call in denied) {
            try { call(); fail("Mutable GENERAL selection accepted") }
            catch (e: IllegalStateException) { assertEquals(GENERAL_RUNTIME_DENIED, e.message) }
        }
        assertTrue(handle.events.isEmpty())
    }

    @Test fun lateStartIsSealedAndCannotBeReadyOrReleasedBeforeItsWorkerJoins() {
        val handle = ControlledHandle().apply { startGate = CountDownLatch(1) }
        val f = fixture(handle)
        val result = AtomicReference<AndroidGeneralRuntimeResult>()
        val caller = Thread { result.set(f.start(1, 150)) }.apply { isDaemon = true }
        caller.start(); assertTrue(handle.started.await(2, TimeUnit.SECONDS))
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, f.owner.stop(1, 40))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.start(2))
        assertFalse(f.owner.isReady(1)); assertEquals(0, handle.stopCalls)
        handle.startGate!!.countDown(); caller.join(2000); assertFalse(caller.isAlive)
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, result.get())
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        assertFalse(f.owner.isReady(1)); assertEquals(1, f.created)
    }

    @Test fun waitReadySharesTheStartupEndAndIsCancelledByOwnedStopOutsideTheLock() {
        val handle = ControlledHandle().apply { waitGate = CountDownLatch(1) }
        val f = fixture(handle)
        val caller = Thread { f.start(1, 500) }.apply { isDaemon = true }; caller.start()
        assertTrue(handle.waiting.await(2, TimeUnit.SECONDS))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        caller.join(2000); assertFalse(caller.isAlive); assertFalse(f.owner.isReady(1))
        assertTrue(handle.waitTimeout in 1..500); assertTrue(handle.stopTimeout in 1..500)
    }

    @Test fun lateFactoryIsOwnedBeforeAnyInitializationAndNeverStartsAfterStopSeal() {
        val gate = CountDownLatch(1); val entered = CountDownLatch(1); val handle = ControlledHandle()
        var created = 0
        val owner = AndroidGeneralRuntimeOwner(AndroidGeneralRuntimeHandleFactory { _, _ ->
            created++; entered.countDown(); gate.await(); handle
        }, elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, epochMs = { GeneralTestFixture.now })
        val caller = Thread { owner.start(1, metadata(), GeneralTestFixture.signer(), options, 120) }.apply { isDaemon = true }
        caller.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, owner.stop(1, 30))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, owner.start(2, metadata(), GeneralTestFixture.signer(), options, 100))
        gate.countDown(); caller.join(2000); assertFalse(caller.isAlive)
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(1, 500))
        assertEquals(1, created); assertFalse(handle.events.contains("initialize")); assertFalse(handle.events.contains("start"))
    }

    @Test fun aHeldStopCannotBeForgottenAndOnlyJoinedRecoveryCanRelease() {
        val handle = ControlledHandle().apply { stopGate = CountDownLatch(1) }
        val f = fixture(handle); assertEquals(AndroidGeneralRuntimeResult.READY, f.start(1))
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, f.owner.stop(1, 30))
        assertTrue(handle.stopping.await(1, TimeUnit.SECONDS))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.stop(1, 500))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, f.start(2))
        handle.stopGate!!.countDown()
        assertTrue(handle.stopped.await(2, TimeUnit.SECONDS))
        // Observe actual owner worker join, not only the controlled native callback's return.
        var result = AndroidGeneralRuntimeResult.BUSY
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (result == AndroidGeneralRuntimeResult.BUSY && System.nanoTime() < end) {
            Thread.yield(); result = f.owner.stop(1, 500)
        }
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, result)
        assertEquals(AndroidGeneralRuntimeResult.READY, f.start(2))
    }

    @Test fun successfulOrderingAndReentrantCallbacksDoNotHoldTheOwnerLock() {
        val f = fixture(); f.handle.reenter = { assertFalse(f.owner.isReady(1)) }
        assertEquals(AndroidGeneralRuntimeResult.READY, f.start(1))
        assertTrue(f.owner.isReady(1))
        assertEquals(listOf("initialize", "protect", "log", "port", "credentials", "listen", "dns", "direct", "vp8", "udp", "start", "wait"), f.handle.events.toList())
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        assertEquals(listOf("stop", "idle", "listener"), f.handle.events.takeLast(3))
    }

    @Test fun expiredMetadataAndInvalidTimeoutDenyBeforeRuntimeCreation() {
        val f = fixture()
        assertEquals(AndroidGeneralRuntimeResult.DENIED, f.start(1, 0))
        val expired = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata(GeneralTestFixture.now).toByteArray())!!
        assertEquals(AndroidGeneralRuntimeResult.DENIED, f.owner.start(1, expired, GeneralTestFixture.signer(), options, 500))
        assertEquals(0, f.created)
    }

    @Test fun factoryGetsTheSameTypedKeyAndStartupUsesOnePhysicalEnd() {
        val clock = AtomicLong(100)
        val meta = metadata(); val key = GeneralTestFixture.signer()
        val handle = ControlledHandle().apply { beforeWait = { clock.set(151) }; afterWait = { clock.set(200) } }
        val owner = AndroidGeneralRuntimeOwner(AndroidGeneralRuntimeHandleFactory { suppliedMeta, suppliedKey ->
            assertSame(meta, suppliedMeta); assertSame(key, suppliedKey); handle
        }, elapsedMs = clock::get, epochMs = { GeneralTestFixture.now })
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, owner.start(1, meta, key, options, 100))
        assertEquals(49L, handle.waitTimeout)
        assertFalse(owner.isReady(1)) // Callback returned at the exact physical end.
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(1, 500))
    }

    @Test fun nativeLogCallbackNeverExposesRawMessagesAndDoesNotChangeAdmission() {
        val output = mutableListOf<String>()
        val handle = ControlledHandle()
        val f = fixture(handle)
        val captured = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "", log = output::add)
        assertEquals(AndroidGeneralRuntimeResult.READY, f.owner.start(1, metadata(), GeneralTestFixture.signer(), captured, 500))
        handle.logger!!.invoke("room=test-room-general-0001 key=private https://private.example/token")
        assertEquals(listOf("GENERAL native event [redacted]"), output.filter { !it.startsWith("stage=") })
        assertTrue(output.all { it == "GENERAL native event [redacted]" || Regex("stage=[A-Z_]+").matches(it) })
        assertTrue(f.owner.isReady(1))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
    }

    @Test fun ackReadProjectionKeepsClosedVocabularyAtTheRealOwnerCallback() {
        val output = mutableListOf<String>()
        val f = fixture()
        val captured = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "", log = output::add)
        try {
            assertEquals(AndroidGeneralRuntimeResult.READY, f.owner.start(1, metadata(), GeneralTestFixture.signer(), captured, 500))
            val expected = mutableListOf<String>()
            for (kind in listOf("SMUX_TIMEOUT", "EOF", "CLOSED_PIPE", "OTHER")) {
                for (expired in listOf(false, true)) {
                    f.handle.logger!!.invoke("2026/10/08 07:30:00 RGA_TASK27_ACK_READ class=$kind ownEndExpired=$expired\n")
                    expected += "nativeAckRead=$kind ownEndExpired=$expired"
                }
            }
            for (invalid in listOf(
                "controlled-secret RGA_TASK27_ACK_READ class=EOF ownEndExpired=false",
                "RGA_TASK27_ACK_READ class=EOF ownEndExpired=false target=private.invalid",
                "RGA_TASK27_ACK_READ class=controlled-secret ownEndExpired=true",
                "RGA_TASK27_ACK_READ class=EOF ownEndExpired=false\nprivate.invalid"
            )) {
                f.handle.logger!!.invoke(invalid)
                expected += "GENERAL native event [redacted]"
            }
            assertEquals(expected, output.filter { !it.startsWith("stage=") })
            assertTrue(f.owner.isReady(1))
        } finally {
            assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        }
    }

    // Dropping existing control/reconnect warnings loses the cause before ACK EOF.
    @Test fun controlClosureProjectionKeepsCauseAndDropsIdentitiesAtTheRealCallback() {
        val output = mutableListOf<String>()
        val f = fixture()
        val captured = AndroidGeneralRuntimeOptions({ true }, 23456, "synthetic", "synthetic", "1.1.1.1", "", log = output::add)
        try {
            assertEquals(AndroidGeneralRuntimeResult.READY, f.owner.start(1, metadata(), GeneralTestFixture.signer(), captured, 500))
            val cases = listOf(
                "control stream ended role=client session=privateSession: control: EOF" to "CONTROL_EOF",
                "control stream ended role=client session=<id>: control stream closed by peer" to "CONTROL_PEER_CLOSE",
                "control stream ended role=client session=privateSession: control: io: read/write on closed pipe" to "CONTROL_CLOSED_PIPE",
                "control stream ended role=client session=privateSession: control: timeout" to "CONTROL_TIMEOUT",
                "control stream ended role=client session=privateSession: control stream unhealthy: missed 4 pong(s)" to "CONTROL_UNHEALTHY",
                "control stream ended role=client session=privateSession: private error with key=secret" to "CONTROL_ENDED_OTHER",
                "control healthy role=client session=privateSession but the data plane has stopped sending — tearing the session down" to "CONTROL_SEND_STALLED",
                "control missed pong role=client session=privateSession missed=1" to "CONTROL_MISSED_PONG",
                "client reconnect reason=liveness - tearing down smux session" to "RECONNECT_LIVENESS",
                "client reconnect reason=provider - tearing down smux session" to "RECONNECT_PROVIDER",
                "client reconnect reason=peer-close - tearing down smux session" to "RECONNECT_PEER_CLOSE",
                "Client link reported conference end: privateReason" to "CONFERENCE_END"
            )
            for ((input, _) in cases) f.handle.logger!!.invoke("2026/10/08 08:00:00 $input\n")
            assertEquals(cases.map { "native=${it.second}" }, output.filter { !it.startsWith("stage=") })
            assertTrue(output.none { it.contains("private") || it.contains("key=") || it.contains("secret") || it.contains("session=") })
            assertTrue(f.owner.isReady(1))
        } finally {
            assertEquals(AndroidGeneralRuntimeResult.STOPPED, f.owner.stop(1, 500))
        }
    }

    @Test fun prePortInitializationFailureRetiresOnlyWithTheEarlierExactCapture() {
        val failed = ControlledHandle().apply { failInitializeAfterAllocation = true; requireEarlyCapture = true }
        val replacement = ControlledHandle()
        var created = 0
        val owner = AndroidGeneralRuntimeOwner(AndroidGeneralRuntimeHandleFactory { _, _ ->
            created++; if (created == 1) failed else replacement
        }, elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, epochMs = { GeneralTestFixture.now })
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, owner.start(1, metadata(), GeneralTestFixture.signer(), options, 500))
        assertTrue(failed.allocated); assertFalse(failed.events.contains("start")); assertFalse(failed.events.contains("port"))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, owner.start(2, metadata(), GeneralTestFixture.signer(), options, 500))
        assertEquals(1, created)
        // Allocation is idle and owned Stop returns; only an EARLIER exact capture enables the witness.
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(1, 500))
        assertEquals(23456L, failed.portAtInitialization)
        assertEquals(listOf("stop", "idle", "listener"), failed.events.takeLast(3))
        assertEquals(AndroidGeneralRuntimeResult.READY, owner.start(2, metadata(), GeneralTestFixture.signer(), options, 500))
        assertEquals(2, created)
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(2, 500))
    }

    @Test fun laterPortMismatchCannotReachTheNativeSetter() {
        val handle = ControlledHandle()
        handle.captureSocksPort(23456)
        try { handle.setSocksPort(23457); fail("Listener port drift accepted") }
        catch (e: IllegalStateException) { assertEquals(GENERAL_RUNTIME_DENIED, e.message) }
        assertFalse(handle.events.contains("port"))
        handle.setSocksPort(23456)
        assertEquals(listOf("port"), handle.events.toList())
    }

    private inner class Fixture(val handle: ControlledHandle) {
        var created = 0
        val owner = AndroidGeneralRuntimeOwner(AndroidGeneralRuntimeHandleFactory { _, _ -> created++; handle },
            elapsedMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, epochMs = { GeneralTestFixture.now })
        fun start(generation: Long, timeout: Long = 500) = owner.start(generation, metadata(), GeneralTestFixture.signer(), options, timeout)
    }

    private class ControlledHandle : AndroidGeneralRuntimeHandle() {
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1); val waiting = CountDownLatch(1)
        val stopping = CountDownLatch(1); val stopped = CountDownLatch(1)
        var startGate: CountDownLatch? = null; var waitGate: CountDownLatch? = null; var stopGate: CountDownLatch? = null
        @Volatile var stopFailure = false; @Volatile var running = false; @Volatile var released = true
        var reenter: (() -> Unit)? = null
        var beforeWait: (() -> Unit)? = null; var afterWait: (() -> Unit)? = null
        var logger: ((String) -> Unit)? = null
        var loadSnapshot: () -> String = { "" }
        override fun readLoadDiagnostics(): String = loadSnapshot()
        var failInitializeAfterAllocation = false; var requireEarlyCapture = false
        var allocated = false; var portAtInitialization = 0L
        var stopCalls = 0; var waitTimeout = 0L; var stopTimeout = 0L
        override fun initialize() {
            events += "initialize"; allocated = true; portAtInitialization = capturedSocksPort
            if (failInitializeAfterAllocation) throw IllegalStateException("private setter detail")
        }
        override fun start() { events += "start"; started.countDown(); startGate?.await(); running = true; beforeWait?.invoke() }
        override fun waitReady(timeoutMs: Long) { events += "wait"; waitTimeout = timeoutMs; waiting.countDown(); reenter?.invoke(); waitGate?.await(); afterWait?.invoke() }
        override fun stop(timeoutMs: Long) {
            events += "stop"; stopCalls++; stopTimeout = timeoutMs; stopping.countDown()
            stopGate?.await(); if (stopFailure) throw IllegalStateException("private native detail")
            running = false; waitGate?.countDown(); stopped.countDown()
        }
        override fun isRunning(): Boolean { events += "idle"; return running }
        override fun listenerReleased(): Boolean {
            events += "listener"
            return released && (!requireEarlyCapture || capturedSocksPort == 23456L && portAtInitialization == 23456L)
        }
        override fun setProtector(protect: (Long) -> Boolean) { events += "protect" }
        override fun setLogWriter(writeLog: (String) -> Unit) { events += "log"; logger = writeLog }
        override fun setSocksPort(value: Long) { validateCapturedSocksPort(value); events += "port" }
        override fun setSocksCredentials(username: String, password: String) { events += "credentials" }
        override fun setDNS(value: String) { events += "dns" }
        override fun setSocksListenHost(value: String) { events += "listen" }
        override fun setVP8Options(fps: Long, batch: Long) { events += "vp8" }
        override fun setDirectRules(value: String) { events += "direct" }
        override fun setUDP(enabled: Boolean) { events += "udp" }
    }
}
