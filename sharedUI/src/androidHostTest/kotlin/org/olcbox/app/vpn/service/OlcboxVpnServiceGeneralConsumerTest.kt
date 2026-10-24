package org.olcbox.app.vpn.service

import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.net.GeneralTestFixture
import org.olcbox.app.net.LocationKind
import org.olcbox.app.vpn.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OlcboxVpnServiceGeneralConsumerTest {
    @Test fun r24ExplicitStopEscalatesAnAlreadyRunningBackgroundCleanup(): Unit = runBlocking {
        verifyEscalatedCleanup(lateStop = false)
    }

    @Test fun r24LateStopIsNotLostWhileCleanupBodyHasFinishedButChildrenRemain(): Unit = runBlocking {
        verifyEscalatedCleanup(lateStop = true)
    }

    private suspend fun verifyEscalatedCleanup(lateStop: Boolean) {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).create().get()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ownedScope = CoroutineScope(SupervisorJob() + dispatcher)
        fun field(name: String) = OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("scope").set(service, ownedScope)
        // Never let a host service on the app's default loopback port decide
        // whether this controlled, resource-free Android cleanup can settle.
        val freePort = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        field("socksListenPort").setInt(service, freePort)
        val mutex = field("tunnelMutex").get(service) as Mutex
        var hold: kotlinx.coroutines.CompletableJob? = null
        try {
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            mutex.lock()
            OlcboxVpnService::class.java.getDeclaredMethod("cleanup", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            val cleanup = field("cleanupJob").get(service) as Job
            assertTrue(cleanup.isActive)
            if (lateStop) {
                // Hold real parent completion after its body returns. A queued
                // dispatcher barrier below proves the old finally has passed.
                hold = Job(cleanup)
                mutex.unlock()
                val terminal = withTimeout(3_000) { OlcboxVpnState.status.first {
                    it is VpnStatus.Disconnected || it is VpnStatus.Error
                } }
                assertEquals("fixture cleanup must physically settle: ${OlcboxVpnState.logs.value.takeLast(3)}",
                    VpnStatus.Disconnected, terminal)
                withContext(dispatcher) { Unit }
                assertTrue("completion must still be held", cleanup.isActive)
                assertFalse(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
            }
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(
                android.content.Intent(OlcboxVpnActions.ACTION_STOP_VPN), 0, 2))
            assertSame("Stop must join the exact cleanup, not replace it", cleanup, field("cleanupJob").get(service))
            if (lateStop) assertEquals("a retained Stop must fence new starts on this instance",
                android.app.Service.START_NOT_STICKY, service.onStartCommand(
                    android.content.Intent(OlcboxVpnActions.ACTION_START_VPN), 0, 3))
            if (mutex.isLocked) mutex.unlock()
            hold?.complete()
            withTimeout(3_000) { cleanup.join() }
            assertTrue("explicit Stop intent was lost behind cleanup(false)",
                org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
        } finally {
            if (mutex.isLocked) mutex.unlock()
            hold?.complete()
            ownedScope.cancel()
            dispatcher.close()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    @Test fun r21LateNativeReturnCannotConsumeAnExpiredSharedBudget(): Unit = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("general-late-stop", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        var expireOnReturn = true
        val o = f.options
        val logged = AndroidGeneralRuntimeOptions(o.protect, o.socksPort, o.socksUsername, o.socksPassword,
            o.dns, o.directRules, o.fps, o.batch, o.udp) { event ->
            if (expireOnReturn && event == "stopStage=OWNER_NATIVE_RETURN")
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600))
        }
        val b = GeneralServiceBinding(1, pfd, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        try {
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(b, logged, f.ops))
            for ((name, value) in listOf("vpnInterface" to pfd, "generalConsumer" to f.consumer))
                OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
            val ticket = f.consumer.retire()!!
            assertFalse(service.stopGeneralForCleanup(ticket, 500))
            assertTrue(f.consumer.hasOwnedRuntime()); assertFalse(f.consumer.hasKnownSettlement(ticket))
            assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.start(2, f.selection, f.key, logged, 100))
            assertTrue(pfd.fileDescriptor.valid())
            expireOnReturn = false
            // Only a later bounded cleanup of the SAME retained owner can join
            // and consume it; expiry did not manufacture a release witness.
            assertTrue(service.stopGeneralForCleanup(ticket, 1_000))
            assertTrue(f.consumer.hasKnownSettlement(ticket)); assertFalse(f.consumer.hasOwnedRuntime())
        } finally {
            expireOnReturn = false
            f.consumer.retire()?.let { assertTrue(f.consumer.stopCaptured(it, 1_000)) }
            pfd.close(); file.delete(); OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
        }
    }

    @Test fun r21ExpiryDuringRetireCannotStartNativeStop(): Unit = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("general-stop-budget", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        val notified = java.util.concurrent.CountDownLatch(1)
        var advanceOnRetire = false
        val ops = GeneralServiceOperations(f.ops.current, f.ops.stopBridge, f.ops.stopOrdinary,
            f.ops.startBridge, f.ops.restoreOrdinary, f.ops.bridgeEpoch) { phase ->
            f.ops.publish(phase)
            if (advanceOnRetire && phase == GeneralTunnelPhase.Stopping)
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(600))
        }
        val binding = GeneralServiceBinding(1, pfd, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        f.stopAction = { notified.countDown() }
        try {
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, ops))
            for ((name, value) in listOf("vpnInterface" to pfd, "generalConsumer" to f.consumer))
                OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
            val ticket = f.consumer.retire()!!
            advanceOnRetire = true
            assertFalse(service.stopGeneralForCleanup(ticket, 500))
            assertFalse("EXPIRED_ORIGINAL_STOP_BUDGET_RENEWED_FOR_NATIVE", notified.await(250, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(f.consumer.hasOwnedRuntime()); assertFalse(f.consumer.hasKnownSettlement(ticket))
            assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.start(2, f.selection, f.key, f.options, 100))
            assertTrue(pfd.fileDescriptor.valid())
        } finally {
            advanceOnRetire = false
            f.consumer.retire()?.let { assertTrue(f.consumer.stopCaptured(it, 1_000)) }
            pfd.close(); file.delete(); OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
        }
    }

    @Test fun r21StopDiagnosticsStayFixedAndDoNotReleaseFailedOwner(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val unknown = java.util.concurrent.CountDownLatch(1)
        val o = f.options
        val logged = AndroidGeneralRuntimeOptions(o.protect, o.socksPort, o.socksUsername, o.socksPassword,
            o.dns, o.directRules, o.fps, o.batch, o.udp) { event ->
            events += event
            if (event == "stopStage=OWNER_UNKNOWN") unknown.countDown()
            if (event == "stopStage=OWNER_STARTUP_GATE") error("observer must not change stop custody")
        }
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, logged, f.ops))
        val ticket = f.consumer.retire()!!
        f.stopAction = { error("secret=do-not-export") }
        try {
            assertTrue(f.consumer.requestStopCaptured(ticket, 500))
            assertTrue(unknown.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(f.consumer.hasOwnedRuntime()); assertFalse(f.consumer.hasKnownSettlement(ticket))
            assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.start(2, f.selection, f.key, logged, 100))
            val stopEvents = events.filter { it.startsWith("stopStage=") }
            assertEquals(listOf("stopStage=OWNER_STARTUP_GATE", "stopStage=OWNER_NATIVE_ENTER", "stopStage=OWNER_UNKNOWN"), stopEvents)
            assertFalse(events.any { it.contains("secret") || it.contains("observer must") })
        } finally {
            f.stopAction = {}
            assertTrue(f.consumer.stopCaptured(ticket, 1_000))
        }
    }

    @Test fun r21OuterBridgeJoinCannotSuppressExactNativeNotification(): Unit = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("general-stop-notify", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        val notified = java.util.concurrent.CountDownLatch(1)
        val releaseBridge = java.util.concurrent.atomic.AtomicBoolean(false)
        // This controlled local bridge remains held independently of native
        // Stop. It proves ordering/custody, not an actual C shutdown dependency.
        val bridge = kotlin.concurrent.thread(name = "rga-r21-held-bridge", isDaemon = true) {
            while (!releaseBridge.get()) try { Thread.sleep(10) } catch (_: InterruptedException) { }
        }
        val binding = GeneralServiceBinding(1, pfd, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        fun field(name: String, value: Any?) = OlcboxVpnService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value)
        f.stopAction = { notified.countDown() }
        try {
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
            field("vpnInterface", pfd); field("generalConsumer", f.consumer); field("tun2socksThread", bridge)
            val ticket = f.consumer.retire()!!
            assertFalse(service.stopGeneralForCleanup(ticket, 500))
            assertEquals("OUTER_BRIDGE_JOIN_SUPPRESSED_NATIVE_NOTIFICATION", 0L, notified.count)
            assertTrue(f.consumer.hasOwnedRuntime()); assertFalse(f.consumer.hasKnownSettlement(ticket))
            assertEquals(AndroidGeneralRuntimeResult.BUSY, f.owner.start(2, f.selection, f.key, f.options, 100))
            assertTrue(bridge.isAlive); assertTrue(pfd.fileDescriptor.valid())
            releaseBridge.set(true); bridge.interrupt(); bridge.join(1_000)
            assertFalse(bridge.isAlive)
            assertTrue(service.stopGeneralForCleanup(ticket, 500))
            assertTrue(f.consumer.hasKnownSettlement(ticket)); assertFalse(f.consumer.hasOwnedRuntime())
            assertEquals(1, f.events.count { it == "stop" }); assertTrue(pfd.fileDescriptor.valid())
        } finally {
            releaseBridge.set(true); bridge.interrupt(); bridge.join(1_000)
            assertFalse("test-owned bridge unjoined", bridge.isAlive)
            f.consumer.retire()?.let { f.consumer.stopCaptured(it, 1_000) }
            pfd.close(); file.delete(); OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
        }
    }

    @Test fun r2DestroyedServiceUnknownCustodyDeniesReplacementConsumer(): Unit = runBlocking {
        val old = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val replacement = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("general-recreation", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        for (service in listOf(old, replacement)) {
            val publicContext = object : android.content.ContextWrapper(service.baseContext) {
                override fun getPackageName(): String = "tech.gatealtas.rga"
                override fun getApplicationContext(): android.content.Context = this
            }
            android.content.ContextWrapper::class.java.getDeclaredField("mBase")
                .apply { isAccessible = true }.set(service, publicContext)
            assertEquals("tech.gatealtas.rga", service.packageName)
        }
        fun ensure(service: OlcboxVpnService) = OlcboxVpnService::class.java.getDeclaredMethod("ensureGeneralConsumer")
            .apply { isAccessible = true }.invoke(service)
        fun field(service: OlcboxVpnService, name: String, value: Any?) = OlcboxVpnService::class.java
            .getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        try {
            val binding = GeneralServiceBinding(1, pfd, f.binding.network, f.binding.snapshot, "selected",
                GeneralTestFixture.link, f.selection, f.key)
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
            field(old, "vpnInterface", pfd); field(old, "generalConsumer", f.consumer)
            assertSame(f.consumer, ensure(old))
            f.badStop = true
            old.onDestroy()
            withTimeout(2_000) { (OlcboxVpnService::class.java.getDeclaredField("cleanupJob")
                .apply { isAccessible = true }.get(old) as? Job)?.join() }
            assertTrue(f.consumer.hasOwnedRuntime()); assertTrue(pfd.fileDescriptor.valid())
            assertNull("replacement must not allocate a fresh GENERAL owner", ensure(replacement))
            assertEquals(android.app.Service.START_NOT_STICKY, replacement.onStartCommand(
                android.content.Intent(OlcboxVpnActions.ACTION_START_VPN), 0, 1))
            assertTrue(f.consumer.hasOwnedRuntime()); assertTrue(pfd.fileDescriptor.valid())
            assertNull(OlcboxVpnService::class.java.getDeclaredField("generalConsumer")
                .apply { isAccessible = true }.get(replacement))
        } finally {
            withTimeout(2_000) { (OlcboxVpnService::class.java.getDeclaredField("cleanupJob")
                .apply { isAccessible = true }.get(old) as? Job)?.join() }
            f.badStop = false
            f.consumer.retire()?.let { f.consumer.stopCaptured(it, 500) }
            (OlcboxVpnService::class.java.getDeclaredField("generalProcessLease")
                .apply { isAccessible = true }.get(old) as? AndroidGeneralProcessCustody.Lease)?.let {
                it.closeTun(pfd); it.recordTransportStopped(); assertTrue(it.tryRelease())
            } // Controlled fixture teardown; this does not claim a real legacy/native cleanup.
            pfd.close(); file.delete(); OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
        }
    }
    @Test fun onlySelectedImportedPublicFullTunAndPresentClassIsEligible() {
        val bundle = LocationBundleV4(activeLocationId = "selected", locations = listOf(
            LocationEntry.from("selected", LocationConfig(kind = LocationKind.Vless), GeneralTestFixture.link)))
        fun select(pkg: String = "tech.gatealtas.rga", mode: AndroidConnectionMode = AndroidConnectionMode.Tun,
            split: AndroidSplitTunnelMode = AndroidSplitTunnelMode.AllApps, present: Boolean = true,
            chosen: LocationBundleV4 = bundle) = selectGeneralSubscriptionUrl(pkg, mode, split, chosen, "selected", present)
        assertEquals(GeneralTestFixture.link, select())
        assertNull(select(present = false)); assertNull(select(pkg = "tech.gatealtas.rga.personal"))
        assertNull(select(mode = AndroidConnectionMode.Proxy)); assertNull(select(split = AndroidSplitTunnelMode.BypassSelected))
        assertNull(select(chosen = bundle.copy(activeLocationId = "other")))
        assertNull(select(chosen = bundle.copy(locations = listOf(LocationEntry.from("selected",
            LocationConfig(kind = LocationKind.Olcrtc), GeneralTestFixture.link)))))
    }

    @Test fun actualServiceCleanupRetainsHeldDescriptorAndSameUnknownOwnerUntilKnownRetry(): Unit = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("general-held-tun", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        val b = GeneralServiceBinding(1, pfd, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        try {
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(b, f.options, f.ops))
            for ((name, value) in listOf("vpnInterface" to pfd, "generalConsumer" to f.consumer))
                OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
            val captured = f.consumer.retire()!!
            f.badStop = true
            assertFalse(service.stopGeneralForCleanup(captured, 500)); assertTrue(pfd.fileDescriptor.valid())
            assertTrue(f.consumer.hasOwnedRuntime())
            f.badStop = false
            assertTrue(service.stopGeneralForCleanup(captured, 500)); assertTrue(pfd.fileDescriptor.valid())
            assertFalse(f.consumer.hasOwnedRuntime())
        } finally { pfd.close(); file.delete() }
    }

    @Test fun exactActualBridgeExitRetiresPositiveStateWithoutClosingOrReplacingTheHeldTun(): Unit = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("general-bridge-exit", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        val binding = GeneralServiceBinding(1, pfd, f.binding.network, f.binding.snapshot, "selected",
            GeneralTestFixture.link, f.selection, f.key)
        val bridge = Thread()
        try {
            assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(binding, f.options, f.ops))
            for ((name, value) in listOf("vpnInterface" to pfd, "generalConsumer" to f.consumer,
                "generalActiveBinding" to binding, "generalOptions" to f.options, "tun2socksThread" to bridge))
                OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
            OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
            assertNull(service.retireGeneralForBridgeExit(Thread(), f.options.socksPort.toInt()))
            assertNull(service.retireGeneralForBridgeExit(bridge, f.options.socksPort.toInt() + 1))
            assertTrue(f.consumer.isRunning())
            val capture = service.retireGeneralForBridgeExit(bridge, f.options.socksPort.toInt())
            assertNotNull(capture)
            assertEquals(GeneralTunnelPhase.Unavailable, OlcboxVpnState.generalTunnel.value.phase)
            assertFalse(f.consumer.isRunning()); assertTrue(f.consumer.hasOwnedRuntime())
            assertTrue(pfd.fileDescriptor.valid()); assertTrue(f.consumer.stopCaptured(capture!!, 500))
        } finally { pfd.close(); file.delete(); OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive) }
    }
}

