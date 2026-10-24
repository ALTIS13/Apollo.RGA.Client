package org.olcbox.app.vpn.service

import android.os.ParcelFileDescriptor
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.vpn.VpnStatus
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class PersonalTunDescriptorOwnershipTest {
    @Test fun workerHandoverDisarmsKotlinCloseBecauseJniOwnsDescriptor() {
        fixture { _, _, duplicate ->
            val ownerClass = Class.forName("org.olcbox.app.vpn.service.OlcboxVpnService\$PendingTunDescriptor")
            val owner = ownerClass.getDeclaredConstructor(ParcelFileDescriptor::class.java)
                .apply { isAccessible = true }.newInstance(duplicate)
            ownerClass.getDeclaredMethod("detachForNative").apply { isAccessible = true }.invoke(owner)
            try {
                ownerClass.getDeclaredMethod("closePending").apply { isAccessible = true }.invoke(owner)
                val closed = org.robolectric.shadows.ShadowParcelFileDescriptor::class.java.getDeclaredField("closed")
                    .apply { isAccessible = true }
                assertFalse("normal worker finally must not close a JNI-owned descriptor", closed.getBoolean(Shadows.shadowOf(duplicate)))
            } finally {
                // Robolectric keeps the backing test file on this shadow wrapper.
                // Avoid adoptFd's JDK-module-dependent simulation; production JNI closes the raw fd.
                duplicate.close()
            }
        }
    }

    @Test fun configWriteFailureDoesNotAcquireOrDetachDuplicate() {
        fixture { service, pfd, _ ->
            var duplicated = 0
            setField(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicated++; pfd })
            val config = File(service.filesDir, TUN2SOCKS_CONFIG_FILE_NAME)
            config.delete(); config.mkdir()
            try {
                assertFalse(start(service, pfd))
                assertEquals("configuration must precede descriptor acquisition", 0, duplicated)
            } finally { config.delete() }
        }
    }

    @Test fun threadStartFailureClosesPendingDuplicateButNotParent() {
        fixture { service, parent, duplicate ->
            var launched = false
            var abandoned = 0L
            setField(service, "tun2socksNativePreparer", { 71L })
            setField(service, "tun2socksNativeAbandoner", { epoch: Long -> abandoned = epoch; true })
            setField(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicate })
            setField(service, "tun2socksThreadLauncher", { _: () -> Unit -> launched = true; throw IllegalStateException("thread unavailable") })
            assertFalse(start(service, parent))
            assertTrue("must reach the actual thread-start boundary", launched)
            assertEquals("only the unstarted captured native reservation may be released", 71L, abandoned)
            val closed = org.robolectric.shadows.ShadowParcelFileDescriptor::class.java.getDeclaredField("closed")
                .apply { isAccessible = true }
            assertTrue("pre-JNI thread failure must close the owned duplicate", closed.getBoolean(Shadows.shadowOf(duplicate)))
            assertTrue(parent.fileDescriptor.valid())
        }
    }

    private fun start(service: OlcboxVpnService, pfd: ParcelFileDescriptor): Boolean =
        service.javaClass.getDeclaredMethod("startTun2socks", ParcelFileDescriptor::class.java)
            .apply { isAccessible = true }.invoke(service, pfd) as Boolean

    @Test fun rejectedNativeReservationDoesNotAcquireDuplicate() {
        fixture { service, parent, _ ->
            var duplicated = false
            setField(service, "tun2socksNativePreparer", { 0L })
            setField(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicated = true; parent })
            assertFalse(start(service, parent))
            assertFalse("a held native reservation cannot be replaced", duplicated)
            assertTrue(parent.fileDescriptor.valid())
        }
    }

    @Test fun actualWorkerDetachFailureReleasesOnlyItsPreparedReservation() {
        fixture { service, parent, duplicate ->
            var abandoned = 0L
            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            duplicate.close()
            setField(service, "tun2socksNativePreparer", { 71L })
            setField(service, "tun2socksNativeAbandoner", { epoch: Long -> abandoned = epoch; true })
            setField(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicate })
            setField(service, "tun2socksThreadLauncher", { work: () -> Unit ->
                Thread { try { work() } catch (failure: Throwable) { error.set(failure) } }
                    .apply { start(); join(1_000L) }
            })
            assertTrue(start(service, parent))
            assertNotNull("must execute the real captured worker's failed detach path", error.get())
            assertEquals("pre-JNI worker failure must release captured PREPARED native owner", 71L, abandoned)
            assertTrue(parent.fileDescriptor.valid())
        }
    }

    @Test fun stopNotificationUsesCapturedEpochAndNeverClaimsWorkerJoined() {
        fixture { service, _, _ ->
            var notified = 0L
            setField(service, "tun2socksNativeEpoch", 71L)
            setField(service, "tun2socksStarted", true)
            setField(service, "tun2socksNativeStopper", { epoch: Long -> notified = epoch; false })
            service.javaClass.getDeclaredMethod("stopTun2socks").apply { isAccessible = true }.invoke(service)
            assertEquals(71L, notified)
            assertTrue(service.javaClass.getDeclaredField("tun2socksStarted").apply { isAccessible = true }.getBoolean(service))
            assertFalse(service.javaClass.getDeclaredField("tun2socksStopRequested").apply { isAccessible = true }.getBoolean(service))
            assertEquals(71L, service.javaClass.getDeclaredField("tun2socksNativeEpoch").apply { isAccessible = true }.getLong(service))
        }
    }

    @Test fun unknownEnteredReservationIsRetainedAfterLauncherFailure() {
        fixture { service, parent, duplicate ->
            setField(service, "tun2socksNativePreparer", { 71L })
            setField(service, "tun2socksNativeAbandoner", { _: Long -> false })
            setField(service, "tun2socksFdDuplicator", { _: ParcelFileDescriptor -> duplicate })
            setField(service, "tun2socksThreadLauncher", { _: () -> Unit -> throw IllegalStateException("unknown entered worker") })
            assertFalse(start(service, parent))
            assertTrue("failed abort is not retirement", service.javaClass.getDeclaredField("tun2socksStarted").apply { isAccessible = true }.getBoolean(service))
            assertEquals(71L, service.javaClass.getDeclaredField("tun2socksNativeEpoch").apply { isAccessible = true }.getLong(service))
        }
    }

    private fun fixture(test: (OlcboxVpnService, ParcelFileDescriptor, ParcelFileDescriptor) -> Unit) {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val loaded = service.javaClass.getDeclaredField("nativeLibrariesLoaded").apply { isAccessible = true }
        val previous = loaded.getBoolean(null)
        loaded.setBoolean(null, true)
        val file = File.createTempFile("rga-fd-owner", ".bin")
        val parent = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val duplicate = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        setField(service, "vpnInterface", parent)
        setField(service, "tun2socksDnsScopePreparer", { 101L })
        setField(service, "tun2socksDnsScopeCloser", { _: Long -> true })
        try { test(service, parent, duplicate) } finally {
            runCatching { duplicate.close() }; runCatching { parent.close() }; file.delete()
            loaded.setBoolean(null, previous)
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    private fun setField(service: OlcboxVpnService, name: String, value: Any) {
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
