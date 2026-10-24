package org.olcbox.app.vpn.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus

@Suppress("DEPRECATION") // Exercise the retired unbound API's fail-closed compatibility.
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

    @Test fun unboundFullTunMarkerCannotPromoteEvenCheckingOlcRtcPath() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.OlcRtc)
        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.Ordinary)
        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)

        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun failedStopRetainsStopStateUntilRealDisconnection() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Unknown)
        OlcboxVpnState.setStatus(VpnStatus.Error("synthetic stop failure"))
        assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)

        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        assertEquals(PersonalTunnelPhase.Inactive, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun runtimeErrorInvalidatesPendingVerification() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)

        OlcboxVpnState.setStatus(VpnStatus.Error("synthetic runtime failure"))

        assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)
        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
    }

    @Test fun cancelledVerificationCannotPromoteAfterDisconnect() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)

        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        assertEquals(PersonalTunnelPhase.Inactive, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun lateUnboundCompletionCannotPromoteReplacementHandoff() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.Ordinary)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)

        // No generation/TUN/challenge accompanies this late completion, so it
        // must be rejected even when a replacement has the same visible phase.
        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun aggregateTrafficAndConnectedStatusCannotActAsFullTunProof() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        OlcboxVpnState.setStatus(VpnStatus.Connected)
        OlcboxVpnState.setTraffic(bytesIn = 1024, bytesOut = 512)

        assertFalse(OlcboxVpnState.markPersonalFullTunVerified())
        assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
    }
}
