package org.olcbox.app.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.ParcelFileDescriptor
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.AndroidPhysicalNetworkSnapshots
import org.olcbox.app.net.PhysicalNetworkSnapshotLedger
import org.olcbox.app.vpn.VpnStatus
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
class PersonalTransitionLifecycleSafetyTest {
    @Test fun rejectedWarmPublicationStopsOnlyOriginatingTransportAndNeverPublishes() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            var held = 0
            var published = 0
            val holder: suspend (ParcelFileDescriptor) -> Boolean = { observed ->
                assertSame(pfd, observed); held++; true
            }
            setField(service, "personalFailureTunHolder", holder)
            assertFalse(runBlocking { commitOrSettle(service, pfd, { false }) { published++ } })
            assertEquals(1, held)
            assertEquals(0, published)
            assertTrue(pfd.fileDescriptor.valid())
            assertEquals(org.olcbox.app.vpn.PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)
        }
    }

    @Test fun rejectedOldPublicationNeverStopsReplacementGeneration() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            setField(service, "generation", 2L)
            var held = 0
            val holder: suspend (ParcelFileDescriptor) -> Boolean = { held++; true }
            setField(service, "personalFailureTunHolder", holder)
            assertFalse(runBlocking { commitOrSettle(service, pfd, { false }) { fail("stale publish") } })
            assertEquals(0, held)
            assertTrue(pfd.fileDescriptor.valid())
        }
    }

    @Test fun rejectedOldPublicationNeverStopsReplacementDescriptor() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            val file = File.createTempFile("rga-replacement", ".bin")
            val replacement = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            try {
                setField(service, "vpnInterface", replacement)
                var held = 0
                val holder: suspend (ParcelFileDescriptor) -> Boolean = { held++; true }
                setField(service, "personalFailureTunHolder", holder)
                assertFalse(runBlocking { commitOrSettle(service, pfd, { false }) { fail("stale publish") } })
                assertEquals(0, held)
                assertTrue(replacement.fileDescriptor.valid())
            } finally { replacement.close(); file.delete() }
        }
    }

    private suspend fun commitOrSettle(
        service: OlcboxVpnService,
        pfd: ParcelFileDescriptor,
        current: () -> Boolean,
        publish: () -> Unit
    ): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
        service.javaClass.declaredMethods.single { it.name == "commitOrSettlePersonalTransition" }
            .apply { isAccessible = true }
            .invoke(service, 1L, pfd, true, current, publish, continuation)
    }

    @Test fun stopDuringFailureSettlementCannotBeOverwrittenAfterSuspend() = runBlocking {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            val hold: suspend (ParcelFileDescriptor) -> Boolean = {
                setField(service, "generation", 2L)
                OlcboxVpnState.setStatus(VpnStatus.Stopping)
                false
            }
            setField(service, "personalFailureTunHolder", hold)
            runBlocking {
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    val method = service.javaClass.declaredMethods.single { it.name == "settlePersonalFailure" }
                        .apply { isAccessible = true }
                    val args = mutableListOf<Any?>(pfd, "test failure", "unavailable", 1L)
                    args += continuation
                    method.invoke(service, *args.toTypedArray())
                }
            }
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Stopping)
        }
    }
    @Test fun realCleanupFenceWinsAgainstWaitingTransitionCommit() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            val attempted = CountDownLatch(1)
            var committed = true
            val worker: Thread
            synchronized(service) {
                worker = thread {
                    attempted.countDown()
                    committed = commit(service, pfd)
                }
                assertTrue(attempted.await(2, TimeUnit.SECONDS))
                service.javaClass.getDeclaredMethod("cleanup", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, false)
            }
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertFalse(committed)
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        }
    }

    @Test fun currentVerifiedTransitionCanPublishConnected() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            assertTrue(commit(service, pfd))
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Connected)
        }
    }

    @Test fun falseFinalTransitionGuardRejectsOtherwiseCurrentPublication() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            assertFalse(commit(service, pfd) { false })
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Reconnecting)
        }
    }
    @Test fun stoppingCannotBeOverwrittenByVerifiedTransitionPublication() {
        withService { service, pfd ->
            OlcboxVpnState.setStatus(VpnStatus.Stopping)
            assertFalse(commit(service, pfd))
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Stopping)
        }
    }

    @Test fun cleanupGenerationCannotPublishOldConnected() {
        withService { service, pfd ->
            setField(service, "generation", 2L)
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            assertFalse(commit(service, pfd))
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Reconnecting)
        }
    }

    @Test fun nonWifiOtherNetworkIsNotPersonalRecoveryPermission() = recoveryRejected(null, false)
    @Test fun captiveCellularIsNotPersonalRecoveryPermission() = recoveryRejected(NetworkCapabilities.TRANSPORT_CELLULAR, true)

    private fun recoveryRejected(transport: Int?, captive: Boolean) {
        withService { service, _ ->
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val shadow = Shadows.shadowOf(cm)
            shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_MOBILE, 0, true, true))
            val network = cm.activeNetwork!!
            val caps = ShadowNetworkCapabilities.newInstance().also {
                if (transport != null) Shadows.shadowOf(it).addTransportType(transport)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (captive) Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            }
            val snapshots = AndroidPhysicalNetworkSnapshots(1, PhysicalNetworkSnapshotLedger())
            snapshots.onAvailable(network)
            snapshots.onCapabilitiesChanged(network, caps)
            snapshots.onLinkPropertiesChanged(network, LinkProperties().apply {
                setDnsServers(listOf(InetAddress.getByName("192.0.2.1")))
            })
            setField(service, "physicalNetworkSnapshots", snapshots)
            val method = service.javaClass.declaredMethods.single { it.name == "personalRecoverySnapshot" }
                .apply { isAccessible = true }
            assertNull("only fresh non-captive usable cellular may revive personal", method.invoke(service, network))
        }
    }

    private fun commit(service: OlcboxVpnService, pfd: ParcelFileDescriptor, current: () -> Boolean = { true }): Boolean {
        val publish: () -> Unit = {
            service.javaClass.getDeclaredMethod("setStatus", VpnStatus::class.java).apply { isAccessible = true }
                .invoke(service, VpnStatus.Connected)
        }
        val method = service.javaClass.declaredMethods.single { it.name == "commitPersonalStateIfCurrent" }
        return method.apply { isAccessible = true }.invoke(service, 1L, pfd, current, publish) as Boolean
    }

    private fun withService(test: (OlcboxVpnService, ParcelFileDescriptor) -> Unit) {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-lifecycle", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        setField(service, "generation", 1L)
        setField(service, "vpnInterface", pfd)
        try { test(service, pfd) } finally {
            runCatching { pfd.close() }; file.delete(); OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    private fun setField(service: OlcboxVpnService, name: String, value: Any) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
