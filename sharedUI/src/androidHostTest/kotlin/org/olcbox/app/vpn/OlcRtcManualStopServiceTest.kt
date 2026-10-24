package org.olcbox.app.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Network
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.net.Routing

@RunWith(RobolectricTestRunner::class)
class OlcRtcManualStopServiceTest {
    @Test fun manualStopWaitsForInFlightStartBeforePublishingDisconnected() = runBlocking {
        val entered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val runtime = FakeRuntime { it.running = false }.also { it.running = false }
        val service = serviceWith(runtime)
        val tunnelMutex = OlcboxVpnService::class.java.getDeclaredField("tunnelMutex")
            .apply { isAccessible = true }.get(service) as Mutex
        val inFlightStart = launch(Dispatchers.Default) {
            tunnelMutex.withLock {
                entered.countDown()
                check(releaseStart.await(10, TimeUnit.SECONDS))
                runtime.running = true
            }
        }
        try {
            assertTrue("handoff did not enter its critical section", entered.await(10, TimeUnit.SECONDS))
            stop(service)
            val cleanup = cleanupJob(service)
            assertNull("Stop completed before the in-flight start could finish",
                withTimeoutOrNull(500) { cleanup.join(); true })
            assertEquals(VpnStatus.Stopping, OlcboxVpnState.status.value)

            releaseStart.countDown()
            inFlightStart.join()
            cleanup.join()
            assertEquals(VpnStatus.Disconnected, OlcboxVpnState.status.value)
            assertFalse("late runtime start survived Stop", runtime.running)
        } finally {
            releaseStart.countDown()
            inFlightStart.join()
            release(service)
        }
    }

    @Test fun supersededNativeStartStopsTheRoomBeforeReturning() = runBlocking {
        lateinit var service: OlcboxVpnService
        val runtime = FakeRuntime(
            onWaitReady = {
                OlcboxVpnService::class.java.getDeclaredField("generation")
                    .apply { isAccessible = true }.setLong(service, 1L)
            },
            onStop = { it.running = false }
        ).also { it.running = false }
        service = serviceWith(runtime)
        try {
            connectedWifi(service)
            val network = (service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .activeNetwork!!
            val started = invokeStartMobile(service, network)

            assertFalse("stale generation cannot publish native start", started)
            assertEquals(1, runtime.startCalls)
            assertFalse("stale native room survived without a Stop", runtime.running)
        } finally {
            runtime.running = false
            release(service)
        }
    }

    @Test fun staleRecoveryCannotScheduleStartWhileManualStopIsPending() = runBlocking {
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val runtime = FakeRuntime { self ->
            stopEntered.countDown()
            check(releaseStop.await(10, TimeUnit.SECONDS))
            self.running = false
        }
        val service = serviceWith(runtime)
        try {
            stop(service)
            assertTrue("native Stop did not begin", stopEntered.await(10, TimeUnit.SECONDS))

            OlcboxVpnService::class.java.getDeclaredMethod(
                "startTunnel", Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(service, true, false, false)

            assertNull("a stale recovery scheduled a tunnel after Stop",
                OlcboxVpnService::class.java.getDeclaredField("startupJob")
                    .apply { isAccessible = true }.get(service))
            assertEquals(VpnStatus.Stopping, OlcboxVpnState.status.value)
        } finally {
            releaseStop.countDown()
            cleanupJob(service).join()
            release(service)
        }
    }

    @Test fun disconnectedIsNotPublishedBeforeNativeStopReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = FakeRuntime { self ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            self.running = false
        }
        val service = serviceWith(runtime)
        try {
            stop(service)
            assertTrue("native stop was not reached", entered.await(10, TimeUnit.SECONDS))
            assertEquals(VpnStatus.Stopping, OlcboxVpnState.status.value)

            release.countDown()
            cleanupJob(service).join()
            assertEquals(OlcboxVpnState.logs.value.takeLast(8).joinToString(" | "),
                VpnStatus.Disconnected, OlcboxVpnState.status.value)
        } finally {
            release.countDown()
            release(service)
        }
    }

    @Test fun nativeStopExceptionKeepsFailureVisibleAndAllowsStopRetry() = runBlocking {
        var attempts = 0
        val runtime = FakeRuntime { self ->
            attempts++
            if (attempts == 1) throw IllegalStateException("native stop failed")
            self.running = false
        }
        val service = serviceWith(runtime)
        try {
            stop(service)
            cleanupJob(service).join()
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
            assertTrue(runtime.running)

            stop(service)
            cleanupJob(service).join()
            assertEquals(2, attempts)
            assertEquals(OlcboxVpnState.logs.value.takeLast(8).joinToString(" | "),
                VpnStatus.Disconnected, OlcboxVpnState.status.value)
        } finally {
            release(service)
        }
    }

    @Test fun liveTunBridgeCannotBeReportedAsDisconnected() = runBlocking {
        val alive = AtomicBoolean(true)
        val bridge = Thread {
            while (alive.get()) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    // The stop request can interrupt a bridge that has not exited yet.
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val runtime = FakeRuntime { it.running = false }
        val service = serviceWith(runtime)
        OlcboxVpnService::class.java.getDeclaredField("tun2socksThread").apply {
            isAccessible = true
        }.set(service, bridge)
        try {
            stop(service)
            cleanupJob(service).join()
            assertTrue(bridge.isAlive)
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)

            alive.set(false)
            bridge.interrupt()
            bridge.join(1_000)
            stop(service)
            cleanupJob(service).join()
            assertEquals(VpnStatus.Disconnected, OlcboxVpnState.status.value)
        } finally {
            alive.set(false)
            bridge.interrupt()
            bridge.join(1_000)
            release(service)
        }
    }

    @Test fun startAfterFailedStopRetriesCleanupWithoutStartingAnotherTunnel() = runBlocking {
        var attempts = 0
        val runtime = FakeRuntime {
            attempts++
            throw IllegalStateException("native stop failed")
        }
        val service = serviceWith(runtime)
        try {
            stop(service)
            cleanupJob(service).join()
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)

            service.onStartCommand(
                Intent(service, OlcboxVpnService::class.java).apply { action = OlcboxVpnService.ACTION_START_VPN },
                0, 2
            )
            assertNull(OlcboxVpnService::class.java.getDeclaredField("startupJob").apply {
                isAccessible = true
            }.get(service))
            cleanupJob(service).join()
            assertEquals(2, attempts)
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
        } finally {
            release(service)
        }
    }

    @Test fun fullRestartDoesNotPassFailedNativeStop() = runBlocking {
        val runtime = FakeRuntime(failOnStart = true) { throw IllegalStateException("native stop failed") }
        val service = serviceWith(runtime)
        try {
            connectedWifi(service)
            invokeSuspendTransition(service, "startFullTunnel", LocationConfig(id = "room", key = "key"))
            assertEquals(0, runtime.startCalls)
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
        } finally {
            release(service)
        }
    }

    @Test fun bridgeReconnectDoesNotPassFailedNativeStop() = runBlocking {
        val runtime = FakeRuntime(failOnStart = true) { throw IllegalStateException("native stop failed") }
        val service = serviceWith(runtime)
        try {
            connectedWifi(service)
            invokeReconnectTransition(service, LocationConfig(id = "room", key = "key"))
            assertEquals(0, runtime.startCalls)
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
        } finally {
            release(service)
        }
    }

    private suspend fun invokeReconnectTransition(service: OlcboxVpnService, location: LocationConfig) =
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            OlcboxVpnService::class.java.getDeclaredMethod(
                "reconnectTransport", LocationConfig::class.java,
                Long::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java
            ).apply { isAccessible = true }.invoke(service, location, 0L, continuation)
        }

    private suspend fun invokeStartMobile(service: OlcboxVpnService, network: Network): Boolean =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            OlcboxVpnService::class.java.getDeclaredMethod(
                "startMobile", LocationConfig::class.java, Network::class.java,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Routing::class.java, String::class.java, kotlin.coroutines.Continuation::class.java
            ).apply { isAccessible = true }.invoke(
                service,
                LocationConfig(id = "test-room", key = "test-key", bypassProvider = "telemost", transport = "vp8channel"),
                network, 0L, false, Routing.Global, "test-claims", continuation
            )
        }

    private suspend fun invokeSuspendTransition(
        service: OlcboxVpnService,
        name: String,
        location: LocationConfig
    ) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
        val method = OlcboxVpnService::class.java.getDeclaredMethod(
            name, LocationConfig::class.java, Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            kotlin.coroutines.Continuation::class.java
        ).apply { isAccessible = true }
        method.invoke(service, location, 0L, false, true, continuation)
    }

    private fun serviceWith(runtime: AndroidOlcRtcRuntime): OlcboxVpnService {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        Shadows.shadowOf(service.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(service.packageName),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = service.packageName
                    name = "org.olcbox.app.AppActivity"
                }
            }
        )
        OlcboxVpnService::class.java.getDeclaredField("olcrtc\$delegate").apply {
            isAccessible = true
        }.set(service, lazy { runtime })
        OlcboxVpnService::class.java.getDeclaredField("runtimePolicy\$delegate").apply {
            isAccessible = true
        }.set(service, lazy { RuntimeLocationPolicy.ExperimentalDebug })
        OlcboxVpnService::class.java.getDeclaredField("routingSettings").apply {
            isAccessible = true
        }.set(service, RoutingSettings(mode = RoutingMode.Global))
        OlcboxVpnService::class.java.getDeclaredField("connectivityManager").apply {
            isAccessible = true
        }.set(service, service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        // Isolate native-stop ordering from an unrelated process listening on the
        // application's usual SOCKS port on the host running Robolectric.
        OlcboxVpnService::class.java.getDeclaredField("socksListenPort").apply {
            isAccessible = true
        }.setInt(service, 0)
        OlcboxVpnState.setStatus(VpnStatus.Connected)
        return service
    }

    private fun connectedWifi(service: OlcboxVpnService) {
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true
        ))
        val network = cm.activeNetwork!!
        shadow.setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance().also {
            Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        })
    }

    private fun stop(service: OlcboxVpnService) {
        service.onStartCommand(
            Intent(service, OlcboxVpnService::class.java).apply { action = OlcboxVpnService.ACTION_STOP_VPN },
            0, 1
        )
    }

    private fun cleanupJob(service: OlcboxVpnService): Job =
        OlcboxVpnService::class.java.getDeclaredField("cleanupJob").apply { isAccessible = true }
            .get(service) as Job

    private fun release(service: OlcboxVpnService) {
        (OlcboxVpnService::class.java.getDeclaredField("job").apply { isAccessible = true }
            .get(service) as Job).cancel()
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
    }

    private class FakeRuntime(
        private val failOnStart: Boolean = false,
        private val onWaitReady: (() -> Unit)? = null,
        private val onStop: (FakeRuntime) -> Unit
    ) : AndroidOlcRtcRuntime {
        var running = true
        var startCalls = 0
        override fun stop(timeoutMs: Long) = onStop(this)
        override fun isRunning(): Boolean = running
        override fun setProtector(protect: (Long) -> Boolean) = Unit
        override fun setLogWriter(writeLog: (String) -> Unit) = Unit
        override fun setProvider(value: String) = Unit
        override fun setRoom(value: String) = Unit
        override fun setDeviceID(value: String) = Unit
        override fun setKey(value: String) = Unit
        override fun setClaimsJSON(value: String) = Unit
        override fun setSocksPort(value: Long) = Unit
        override fun setSocksCredentials(username: String, password: String) = Unit
        override fun setTransport(value: String) = Unit
        override fun setDNS(value: String) = Unit
        override fun setSocksListenHost(value: String) = Unit
        override fun setVP8Options(fps: Long, batch: Long) = Unit
        override fun setDirectRules(value: String) = Unit
        override fun setUDP(enabled: Boolean) = Unit
        override fun start() {
            startCalls++
            if (failOnStart) throw IllegalStateException("test start abort before network verification")
            running = true
        }
        override fun waitReady(timeoutMs: Long) { onWaitReady?.invoke() }
        override fun check(provider: String, transport: String, room: String, deviceId: String, key: String,
                           socksPort: Long, timeoutMs: Long, fps: Long, batch: Long): Long = 0
        override fun ping(provider: String, transport: String, room: String, deviceId: String, key: String,
                          socksPort: Long, timeoutMs: Long, url: String, fps: Long, batch: Long): Long = 0
    }
}
