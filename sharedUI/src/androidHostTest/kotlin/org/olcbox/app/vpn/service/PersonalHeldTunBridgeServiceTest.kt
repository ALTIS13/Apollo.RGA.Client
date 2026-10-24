package org.olcbox.app.vpn.service

import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalHeldTunBridgeServiceTest {
    @Test fun stoppingBridgeWithoutAWorkerNeverClosesTheParentVpnDescriptor() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-held-tun", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        try {
            OlcboxVpnService::class.java.getDeclaredField("vpnInterface")
                .apply { isAccessible = true }.set(service, pfd)

            assertTrue(service.stopBridgeKeepingTun(pfd))
            assertTrue(pfd.fileDescriptor.valid())
        } finally {
            pfd.close()
            file.delete()
        }
    }

    @Test fun timedOutNativeBridgeCannotBeReportedAsSafelyHeld() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-stuck-tun", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val release = AtomicBoolean(false)
        val stuckWorker = thread(name = "rga-stuck-bridge-test", isDaemon = true) {
            while (!release.get()) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { /* simulate stuck native stop */ }
            }
        }
        try {
            OlcboxVpnService::class.java.getDeclaredField("vpnInterface")
                .apply { isAccessible = true }.set(service, pfd)
            OlcboxVpnService::class.java.getDeclaredField("tun2socksThread")
                .apply { isAccessible = true }.set(service, stuckWorker)

            assertFalse(service.holdTunWithoutTransport(pfd))
            assertFalse(pfd.fileDescriptor.valid())
        } finally {
            release.set(true)
            stuckWorker.interrupt()
            stuckWorker.join(1_000)
            runCatching { pfd.close() }
            file.delete()
        }
    }
}
