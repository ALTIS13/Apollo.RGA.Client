package org.olcbox.app.net

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class XrayRuntimeDiagnosticsTest {
    @Test fun exactProfileDiagnosticsNeverReadRawCoreTail() {
        val context = RuntimeEnvironment.getApplication() as Context
        val dir = File(context.cacheDir, "olcbox-xray").apply { mkdirs() }
        val log = File(dir, "xray.log")
        log.writeText("SECRET_TOKEN uuid=password https://secret.invalid/subscription\n")
        val process = AndroidCoreProcess(context, "libxraycore.so", "xray") { bin, config -> listOf(bin, config) }
        try {
            val diagnostic = process.diagnostics(includeRawLogs = false)
            assertFalse(diagnostic.contains("SECRET_TOKEN"))
            assertFalse(diagnostic.contains("secret.invalid"))
            assertFalse(diagnostic.contains("password"))
            assertTrue(diagnostic.contains("was never started"))
        } finally {
            process.stop()
        }
    }
}
