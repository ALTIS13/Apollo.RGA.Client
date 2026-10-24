package org.olcbox.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.olcbox.app.vpn.AndroidOlcRtcRuntime

class AndroidOlcRtcClaimsContractTest {
    @Test fun runtimeExposesOneShotClaimsSetterAsVoidOperation() {
        val setter = AndroidOlcRtcRuntime::class.java.getMethod(
            "setClaimsJSON", String::class.java
        )
        assertNotNull(setter)
        assertEquals(Void.TYPE, setter.returnType)
    }
}
