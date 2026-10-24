package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertFalse

class AndroidGeneralListenerWitnessTest {
    @Test fun invalidCapturedPortsNeverClaimReleased() {
        for (port in listOf(Long.MIN_VALUE, -1L, 0L, 65536L, Long.MAX_VALUE)) {
            assertFalse(generalListenerAddressReleased(port))
        }
    }
}
