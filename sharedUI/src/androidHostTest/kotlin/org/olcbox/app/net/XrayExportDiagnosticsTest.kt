package org.olcbox.app.net

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.log.LogScrubber
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class XrayExportDiagnosticsTest {
    @Test fun disconnectedExportNeverIncludesOpaqueXraySecretsButKeepsSingboxDiagnostics() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val xrayLog = File(context.cacheDir, "olcbox-xray/xray.log").apply { parentFile!!.mkdirs() }
        val singboxLog = File(context.cacheDir, "olcbox-singbox/singbox.log").apply { parentFile!!.mkdirs() }
        val marker = "SYNTHETIC_AUTH_NOT_REAL"
        xrayLog.writeText("opaque core message: $marker\n")
        singboxLog.writeText("synthetic useful sing-box state\n")
        OlcboxVpnState.activeLocation = null
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        try {
            val diagnostic = AndroidVpnManager(context).diagnosticsLog()
            val exported = diagnostic.lineSequence().joinToString("\n") { LogScrubber.default.scrub(it) }
            assertFalse(marker in exported)
            assertFalse("opaque core message" in exported)
            assertTrue("synthetic useful sing-box state" in exported)
            assertTrue("Xray raw diagnostics withheld" in exported)
        } finally {
            xrayLog.delete()
            singboxLog.delete()
        }
    }
}
