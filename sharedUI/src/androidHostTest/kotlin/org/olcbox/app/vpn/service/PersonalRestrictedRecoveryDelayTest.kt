package org.olcbox.app.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Test
import org.olcbox.app.vpn.UpstreamTransport

class PersonalRestrictedRecoveryDelayTest {
    @Test fun personalCellularGivesClassifierTimeBeforeOrdinaryRecovery() {
        // The selected SOCKS path can take 10 s after the two physical rounds.
        // Leave scheduling margin so ordinary recovery cannot cancel the assessment.
        assertEquals(45_000L, PersonalRestrictedRecoveryDelay.apply(1_500L, true, UpstreamTransport.Cellular))
        assertEquals(45_000L, PersonalRestrictedRecoveryDelay.apply(0L, true, UpstreamTransport.Cellular))
    }

    @Test fun ordinaryAndWifiRecoveryRemainUnchanged() {
        assertEquals(1_500L, PersonalRestrictedRecoveryDelay.apply(1_500L, false, UpstreamTransport.Cellular))
        assertEquals(0L, PersonalRestrictedRecoveryDelay.apply(0L, false, UpstreamTransport.Cellular))
        assertEquals(1_500L, PersonalRestrictedRecoveryDelay.apply(1_500L, true, UpstreamTransport.Wifi))
    }
}
