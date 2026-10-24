package org.olcbox.app.vpn.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus

class OlcboxPersonalTunnelUiStateTest {
    @After fun reset() {
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
    }

    @Test fun connectedStatusAndSocksReadinessCannotPromotePersonalPhase() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)

        OlcboxVpnState.setStatus(VpnStatus.Connected)

        assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
        assertEquals(PersonalActivePath.OlcRtc, OlcboxVpnState.personalTunnel.value.activePath)
    }

    @Test fun fullTunProofMarkerRequiresCheckingOlcRtcPath() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.OlcRtc)
        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.Ordinary)
        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)

        assertTrue(OlcboxVpnState.markPersonalFullTunVerified())
        assertEquals(PersonalTunnelPhase.Working, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun failedStopRetainsStopStateUntilRealDisconnection() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Unknown)
        OlcboxVpnState.setStatus(VpnStatus.Error("synthetic stop failure"))
        assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)

        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        assertEquals(PersonalTunnelPhase.Inactive, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun runtimeErrorCannotLeaveAStaleWorkingClaim() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        assertTrue(OlcboxVpnState.markPersonalFullTunVerified())

        OlcboxVpnState.setStatus(VpnStatus.Error("synthetic runtime failure"))

        assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)
    }
}
