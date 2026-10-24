package org.olcbox.app.vpn

import org.junit.Assert.*
import org.junit.Test

class AndroidGeneralRuntimeProviderTest {
    @Test fun onlyPresentPublicClassCanProduceAnInertFactory() {
        var loaded = 0
        val factory = AndroidGeneralRuntimeHandleFactory { _, _ -> error("JNI must not run") }
        for (name in listOf("tech.gatealtas.rga.personal", "tech.gatealtas.rga.debug", "org.olcbox.app"))
            assertNull(AndroidGeneralRuntimeProvider.loadFactory(name) { loaded++; factory })
        assertEquals(0, loaded)
        assertSame(factory, AndroidGeneralRuntimeProvider.loadFactory("tech.gatealtas.rga") { loaded++; factory })
        assertEquals(1, loaded)
        assertNull(AndroidGeneralRuntimeProvider.loadFactory("tech.gatealtas.rga") { throw ClassNotFoundException() })
    }
}
