package org.olcbox.app.vpn

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.GeneralBootstrapV1
import org.olcbox.app.net.GeneralTestFixture
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneralRuntimeDiagnosticsTest {
    // Missing/incorrect stage emission hides the failing boundary on a real device.
    @Test fun successfulStartupReportsOnlyFixedStagesInActualNativeCallOrder() {
        val output = CopyOnWriteArrayList<String>()
        val handle = Handle()
        val owner = owner(handle)
        assertEquals(AndroidGeneralRuntimeResult.READY, start(owner, output::add))
        assertEquals(listOf("stage=CREATE", "stage=INITIALIZE", "stage=CONFIGURE",
            "stage=START", "stage=WAIT_READY", "stage=READY"), output.toList())
        assertTrue(owner.isReady(1))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(1, 500))
    }

    // Reporting READY or native details after failed initialization is a wrong result.
    @Test fun initializationFailureReportsItsBoundaryWithoutPrivateDetailsAndRetainsCleanup() {
        val output = CopyOnWriteArrayList<String>()
        val owner = owner(Handle(failInitialize = true))
        assertEquals(AndroidGeneralRuntimeResult.UNKNOWN, start(owner, output::add))
        assertEquals(listOf("stage=CREATE", "stage=INITIALIZE", "stage=FAILED"), output.toList())
        assertFalse(owner.isReady(1))
        assertEquals(AndroidGeneralRuntimeResult.BUSY, start(owner, output::add, generation = 2))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(1, 500))
    }

    // An observer exception must not deny admission or lose an owned native handle.
    @Test fun throwingDiagnosticObserverCannotAlterStartupOrKnownStop() {
        val owner = owner(Handle())
        assertEquals(AndroidGeneralRuntimeResult.READY, start(owner, { throw IllegalStateException("private") }))
        assertTrue(owner.isReady(1))
        assertEquals(AndroidGeneralRuntimeResult.STOPPED, owner.stop(1, 500))
    }

    private fun owner(handle: Handle) = AndroidGeneralRuntimeOwner(
        AndroidGeneralRuntimeHandleFactory { _, _ -> handle },
        { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }, { GeneralTestFixture.now })
    private fun start(owner: AndroidGeneralRuntimeOwner, log: (String) -> Unit, generation: Long = 1) =
        owner.start(generation, GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!,
            GeneralTestFixture.signer(), AndroidGeneralRuntimeOptions({ true }, 23456, "fixture", "fixture",
                "1.1.1.1", "", log = log), 500)
    private class Handle(private val failInitialize: Boolean = false) : AndroidGeneralRuntimeHandle() {
        private var running = false
        override fun initialize() { if (failInitialize) error("room=private key=private https://private.example/token") }
        override fun start() { running = true }
        override fun waitReady(timeoutMs: Long) {}
        override fun stop(timeoutMs: Long) { running = false }
        override fun isRunning() = running
        override fun listenerReleased() = true
        override fun setProtector(protect: (Long) -> Boolean) {}
        override fun setLogWriter(writeLog: (String) -> Unit) {}
        override fun setSocksPort(value: Long) { validateCapturedSocksPort(value) }
        override fun setSocksCredentials(username: String, password: String) {}
        override fun setDNS(value: String) {}
        override fun setSocksListenHost(value: String) {}
        override fun setVP8Options(fps: Long, batch: Long) {}
        override fun setDirectRules(value: String) {}
        override fun setUDP(enabled: Boolean) {}
    }
}
