package org.olcbox.app.vpn.service

import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.VpnStatus
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneralRestrictedMigrationTest {
    // Removing the migration branch used to schedule another ordinary retry,
    // invalidating the same generation before its restricted-path handoff.
    @Test fun handledMigrationAssessmentRetainsTunAndDoesNotScheduleOrdinaryRetry() = runBlocking {
        withService { service, pfd, mutex ->
            set(service, "connectedGeneralSubscriptionUrl", "https://example.invalid/Selected_01")
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            var assessed = false
            val assessment: suspend (Long) -> Boolean = { generation ->
                assertEquals(0L, generation)
                assertTrue(mutex.isLocked)
                assertTrue(pfd.fileDescriptor.valid())
                assertTrue(OlcboxVpnState.status.value is VpnStatus.Reconnecting)
                delay(1)
                assessed = true
                true // Evidence/admission operation below this real service boundary.
            }
            set(service, "coldStartupRestrictionAssessment", assessment)
            mutex.withLock { failVerification(service, migration = true) }
            assertTrue("public migration bypassed restriction assessment", assessed)
            assertTrue(pfd.fileDescriptor.valid())
            assertNull("handled alternate must not be replaced by ordinary retry", get(service, "recoveryJob"))
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        }
    }

    @Test fun migrationWithoutGeneralLinkKeepsOrdinaryRetryAndCannotAssessAlternate() = runBlocking {
        withService { service, pfd, mutex ->
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            val assessment: suspend (Long) -> Boolean = { error("unlinked migration must not assess GENERAL") }
            set(service, "coldStartupRestrictionAssessment", assessment)
            mutex.withLock { failVerification(service, migration = true) }
            assertTrue(pfd.fileDescriptor.valid())
            assertTrue(get(service, "recoveryJob") is Job)
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Reconnecting)
        }
    }

    @Test fun inconclusiveMigrationReturnsToOrdinaryRetryWithoutConnectedClaim() = runBlocking {
        withService { service, pfd, mutex ->
            set(service, "connectedGeneralSubscriptionUrl", "https://example.invalid/Selected_01")
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            var assessed = false
            val assessment: suspend (Long) -> Boolean = { assessed = true; false }
            set(service, "coldStartupRestrictionAssessment", assessment)
            mutex.withLock { failVerification(service, migration = true) }
            assertTrue(assessed)
            assertTrue(pfd.fileDescriptor.valid())
            assertTrue(get(service, "recoveryJob") is Job)
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Reconnecting)
        }
    }

    @Test fun stopSupersedingMigrationAssessmentNeverSchedulesAnotherRetry() = runBlocking {
        withService { service, pfd, mutex ->
            set(service, "connectedGeneralSubscriptionUrl", "https://example.invalid/Selected_01")
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            val assessment: suspend (Long) -> Boolean = {
                set(service, "generation", 1L)
                OlcboxVpnState.setStatus(VpnStatus.Stopping)
                false
            }
            set(service, "coldStartupRestrictionAssessment", assessment)
            mutex.withLock { failVerification(service, migration = true) }
            assertTrue(pfd.fileDescriptor.valid())
            assertNull(get(service, "recoveryJob"))
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Stopping)
        }
    }

    @Test fun firstConnectingStillAwaitsSameAssessmentBeforeFailureCleanup() = runBlocking {
        withService { service, pfd, mutex ->
            OlcboxVpnState.setStatus(VpnStatus.Connecting)
            val assessment: suspend (Long) -> Boolean = { true }
            set(service, "coldStartupRestrictionAssessment", assessment)
            mutex.withLock { failVerification(service, migration = false) }
            assertTrue(pfd.fileDescriptor.valid())
            assertNull(get(service, "recoveryJob"))
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Connecting)
        }
    }

    private suspend fun withService(body: suspend (OlcboxVpnService, ParcelFileDescriptor, Mutex) -> Unit) {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val publicContext = object : android.content.ContextWrapper(service.baseContext) {
            override fun getPackageName(): String = "tech.gatealtas.rga"
            override fun getApplicationContext(): android.content.Context = this
        }
        android.content.ContextWrapper::class.java.getDeclaredField("mBase")
            .apply { isAccessible = true }.set(service, publicContext)
        assertEquals("tech.gatealtas.rga", service.packageName)
        val file = File.createTempFile("rga-migration-tun", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        try {
            set(service, "vpnInterface", pfd)
            body(service, pfd, get(service, "tunnelMutex") as Mutex)
        } finally {
            (get(service, "recoveryJob") as? Job)?.cancel()
            runCatching { pfd.close() }; file.delete()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    private fun get(service: OlcboxVpnService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun set(service: OlcboxVpnService, name: String, value: Any?) =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    private suspend fun failVerification(service: OlcboxVpnService, migration: Boolean): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            service.javaClass.declaredMethods.single { it.name == "failUnverifiedTunnel" }
                .apply { isAccessible = true }
                .invoke(service, "VPN transport", migration, 0L, continuation)
        }
}
