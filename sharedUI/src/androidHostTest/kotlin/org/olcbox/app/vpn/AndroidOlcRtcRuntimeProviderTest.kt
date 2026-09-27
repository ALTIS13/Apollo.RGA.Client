package org.olcbox.app.vpn

import org.olcbox.app.net.RuntimeLocationPolicy
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidOlcRtcRuntimeProviderTest {
    @Test
    fun ordinaryReleaseDoesNotLoadExperimentalFactory() {
        val result = AndroidOlcRtcRuntimeProvider.loadFactory(RuntimeLocationPolicy.OrdinaryOnly) {
            error("release must never load debug binding")
        }
        assertNull(result)
    }

    @Test
    fun debugCanLoadItsIsolatedFactory() {
        val factory = AndroidOlcRtcRuntimeFactory { error("not needed") }
        assertSame(
            factory,
            AndroidOlcRtcRuntimeProvider.loadFactory(RuntimeLocationPolicy.ExperimentalDebug) { factory }
        )
    }

    @Test
    fun missingDebugFactoryFailsClosed() {
        assertNull(AndroidOlcRtcRuntimeProvider.loadFactory(RuntimeLocationPolicy.ExperimentalDebug) {
            throw ClassNotFoundException("debug binding absent")
        })
    }
}
