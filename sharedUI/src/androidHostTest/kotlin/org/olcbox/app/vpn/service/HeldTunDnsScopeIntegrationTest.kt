package org.olcbox.app.vpn.service

import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.VpnStatus
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Actual service boundaries; no native worker, transport, room, or device. */
@RunWith(RobolectricTestRunner::class)
class HeldTunDnsScopeIntegrationTest {
    @Test fun deniedDnsOwnerDoesNotReserveWorkerOrAcquireDuplicate() = fixture { service, parent, duplicate ->
        var reservations = 0
        var duplicates = 0
        set(service, "tun2socksDnsScopePreparer", { 0L })
        set(service, "tun2socksNativePreparer", { reservations++; 71L })
        set(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicates++; duplicate })
        set(service, "tun2socksThreadLauncher", { _: () -> Unit -> Thread() })
        assertFalse("an unavailable DNS owner must prevent worker start", start(service, parent))
        assertEquals(0, reservations)
        assertEquals(0, duplicates)
        assertTrue(parent.fileDescriptor.valid())
    }

    @Test fun workerFailureAndReplacementKeepOneScopeUntilParentActuallyCloses() = fixture { service, parent, duplicate ->
        var opened = 0
        var closed = 0
        val workerError = AtomicReference<Throwable?>()
        set(service, "tun2socksDnsScopePreparer", { opened++; 901L })
        set(service, "tun2socksDnsScopeCloser", { id: Long ->
            assertEquals(901L, id)
            assertFalse("parent close must precede native scope close", parent.fileDescriptor.valid())
            closed++; true
        })
        failedDetachWorker(service, duplicate, workerError)
        assertTrue(start(service, parent))
        assertNotNull("the real captured worker must reach failed detach", workerError.get())
        assertTrue(parent.fileDescriptor.valid())
        assertEquals(0, closed)
        workerError.set(null)
        assertTrue(start(service, parent))
        assertNotNull(workerError.get())
        assertEquals("a held descriptor retains the same DNS owner", 1, opened)
        assertEquals(0, closed)
        assertTrue(cleanup(service))
        assertEquals(1, closed)
        assertNull(field(service, "vpnInterface"))
        assertTrue(cleanup(service))
        assertEquals("empty cleanup cannot close the owner again", 1, closed)
    }

    @Test fun unknownScopeCloseRetainsExactClosedParentForSameOwnerRetry() = fixture { service, parent, duplicate ->
        var allowClose = false
        val ids = mutableListOf<Long>()
        set(service, "tun2socksDnsScopePreparer", { 901L })
        set(service, "tun2socksDnsScopeCloser", { id: Long -> ids += id; allowClose })
        failedDetachWorker(service, duplicate, AtomicReference())
        assertTrue(start(service, parent))
        assertFalse("a descriptor close is not a DNS-owner close receipt", cleanup(service))
        assertSame(parent, field(service, "vpnInterface"))
        assertFalse(parent.fileDescriptor.valid())
        assertEquals(listOf(901L), ids)
        allowClose = true
        assertTrue(cleanup(service))
        assertEquals(listOf(901L, 901L), ids)
        assertNull(field(service, "vpnInterface"))
    }

    private fun failedDetachWorker(service: OlcboxVpnService, duplicate: ParcelFileDescriptor,
                                    error: AtomicReference<Throwable?>) {
        duplicate.close()
        set(service, "tun2socksNativePreparer", { 71L })
        set(service, "tun2socksNativeAbandoner", { _: Long -> true })
        set(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicate })
        set(service, "tun2socksThreadLauncher", { work: () -> Unit ->
            Thread { try { work() } catch (failure: Throwable) { error.set(failure) } }
                .apply { start(); join(1_000L); assertFalse("test worker must join", isAlive) }
        })
    }

    private fun start(service: OlcboxVpnService, pfd: ParcelFileDescriptor): Boolean =
        service.javaClass.getDeclaredMethod("startTun2socks", ParcelFileDescriptor::class.java)
            .apply { isAccessible = true }.invoke(service, pfd) as Boolean

    private fun cleanup(service: OlcboxVpnService): Boolean =
        service.javaClass.getDeclaredMethod("cleanupVpnInterface")
            .apply { isAccessible = true }.invoke(service) as Boolean

    private fun set(service: OlcboxVpnService, name: String, value: Any) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
    private fun field(service: OlcboxVpnService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun fixture(test: (OlcboxVpnService, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit) {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val loaded = service.javaClass.getDeclaredField("nativeLibrariesLoaded").apply { isAccessible = true }
        val previous = loaded.getBoolean(null)
        loaded.setBoolean(null, true)
        val file = File.createTempFile("rga-dns-owner", ".bin")
        val parent = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val duplicate = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        set(service, "vpnInterface", parent)
        try { test(service, parent, duplicate) } finally {
            runCatching { duplicate.close() }; runCatching { parent.close() }; file.delete()
            loaded.setBoolean(null, previous)
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }
}
