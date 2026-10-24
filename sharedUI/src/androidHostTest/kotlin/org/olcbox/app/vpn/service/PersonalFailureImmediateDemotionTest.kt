package org.olcbox.app.vpn.service

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalFailureImmediateDemotionTest {
    @Test fun deadNativeRetiresConnectedProofBeforeTunHolderSuspends() = runBlocking {
        withService { service, pfd ->
            makeWorking()
            val epoch = service.javaClass.getDeclaredField("personalProofEpoch")
                .apply { isAccessible = true }.get(service) as AtomicLong
            val before = epoch.get()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Boolean>()
            val holder: suspend (ParcelFileDescriptor) -> Boolean = { observed ->
                assertSame(pfd, observed)
                entered.complete(Unit)
                release.await()
            }
            setField(service, "personalFailureTunHolder", holder)
            val settling = async { settle(service, pfd) }
            entered.await()

            assertEquals(VpnStatus.Reconnecting, OlcboxVpnState.status.value)
            assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
            assertTrue(epoch.get() > before)

            release.complete(true)
            settling.await()
            assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)
        }
    }

    @Test fun missingFreshSnapshotRetiresPreviousWorkingProof() = runBlocking {
        withService { service, _ ->
            makeWorking()
            val attempt = async(start = CoroutineStart.LAZY) { attemptProof(service) }
            setField(service, "personalProofJob", attempt as Job)
            attempt.start()

            assertFalse(attempt.await())
            assertEquals(VpnStatus.Reconnecting, OlcboxVpnState.status.value)
            assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
        }
    }

    @Test fun nativeObserverEpochDoesNotRepeatAcrossServiceInstances() {
        val first = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val replacement = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val firstEpoch = runCatching { nextObserverEpoch(first) }.getOrNull()
        assertNotNull("native observer epoch must be process-global", firstEpoch)
        val secondEpoch = nextObserverEpoch(replacement)
        assertTrue(secondEpoch > firstEpoch!!)
    }

    private fun makeWorking() {
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        val evidence = PersonalTunProof.validateExactFlow(
            expected = PersonalTunProof.RuntimeStamp(1, 2, 3, 4, 5, 6),
            current = PersonalTunProof.RuntimeStamp(1, 2, 3, 4, 5, 6),
            observerEpoch = 7,
            receiptLocalIpv4 = "10.0.88.88",
            expectedTunIpv4 = "10.0.88.88",
            native = longArrayOf(7, 1, 1, 1, 1, 0x0A005858),
        )!!
        assertTrue(OlcboxVpnState.publishPersonalWorking(OlcboxVpnState.personalPhaseEpoch(), evidence))
        OlcboxVpnState.setStatus(VpnStatus.Connected)
    }

    private suspend fun settle(service: OlcboxVpnService, pfd: ParcelFileDescriptor): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            service.javaClass.declaredMethods.single { it.name == "settlePersonalFailure" }
                .apply { isAccessible = true }
                .invoke(service, pfd, "native stopped", "unavailable", 1L, continuation)
        }

    private suspend fun attemptProof(service: OlcboxVpnService): Boolean =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            service.javaClass.declaredMethods.single { it.name == "attemptPersonalTunProof" }
                .apply { isAccessible = true }.invoke(service, continuation)
        }

    private fun nextObserverEpoch(service: OlcboxVpnService): Long =
        service.javaClass.getDeclaredMethod("nextPersonalObserverEpoch")
            .apply { isAccessible = true }.invoke(service) as Long

    private suspend fun withService(test: suspend (OlcboxVpnService, ParcelFileDescriptor) -> Unit) {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-proof-settlement", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        setField(service, "generation", 1L)
        setField(service, "vpnInterface", pfd)
        setField(service, "connectivityManager", service.getSystemService(Context.CONNECTIVITY_SERVICE))
        try { test(service, pfd) } finally {
            runCatching { pfd.close() }
            file.delete()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    private fun setField(service: OlcboxVpnService, name: String, value: Any) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
