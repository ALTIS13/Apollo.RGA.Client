package org.olcbox.app.vpn.service

import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.vpn.OlcChannelRole
import org.olcbox.app.vpn.OlcConnectionStage
import org.olcbox.app.vpn.VpnStatus

class OlcConnectionProgressStateTest {
    @Before fun startClean() { OlcboxVpnState.setStatus(VpnStatus.Disconnected) }
    @After fun reset() { OlcboxVpnState.setStatus(VpnStatus.Disconnected) }

    @Test fun reserveCausalMessageRequiresAnActualPrimaryFailure() {
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve)
        assertEquals(OlcConnectionStage.Connecting, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve, primaryAttemptFailed = true)
        assertEquals(OlcConnectionStage.ConnectingReserve, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Checking)
        assertEquals(OlcConnectionStage.ConnectingReserve, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(OlcChannelRole.Reserve, OlcboxVpnState.olcConnection.value.channel)
    }

    @Test fun primaryAttemptNeverClaimsReserveFailure() {
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Primary, primaryAttemptFailed = true)
        assertEquals(OlcConnectionStage.Connecting, OlcboxVpnState.olcConnection.value.stage)
    }

    @Test fun verifiedChannelCompletesItsBusyPresentationWithoutChangingVpnAuthority() {
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve, primaryAttemptFailed = true)
        assertTrue(OlcboxVpnState.olcConnection.value.connecting)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
        assertFalse("successful channel must not remain a perpetual internet check",
            OlcboxVpnState.olcConnection.value.connecting)
        assertTrue(OlcboxVpnState.olcConnection.value.active)
        assertEquals(OlcChannelRole.Reserve, OlcboxVpnState.olcConnection.value.channel)
        assertSame(VpnStatus.Reconnecting, OlcboxVpnState.status.value)
        assertEquals(GeneralTunnelPhase.TransportVerified, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Checking)
        assertTrue("a new check must retire the completed channel presentation",
            OlcboxVpnState.olcConnection.value.connecting)
        assertEquals(OlcChannelRole.Reserve, OlcboxVpnState.olcConnection.value.channel)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
        assertFalse(OlcboxVpnState.olcConnection.value.connecting)
        // Losing the proof must retire the finished presentation, not leave a
        // green/working badge on a new or failed attempt.
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
        assertEquals(OlcConnectionStage.Unavailable, OlcboxVpnState.olcConnection.value.stage)
    }

    @Test fun genericConnectedDoesNotCompleteAnUnverifiedChannel() {
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve)
        OlcboxVpnState.setStatus(VpnStatus.Connected)
        assertEquals(OlcConnectionStage.Connecting, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(OlcChannelRole.Reserve, OlcboxVpnState.olcConnection.value.channel)
        assertTrue(OlcboxVpnState.olcConnection.value.connecting)
    }

    @Test fun lostLiveHealthSuspendsOnlyAnAlreadyVerifiedPresentation() {
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve)
        OlcboxVpnState.setGeneralChannelHealth(true)
        assertEquals(OlcConnectionStage.Connecting, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
        OlcboxVpnState.setGeneralChannelHealth(false)
        assertTrue("known unhealthy transport must not retain the working badge",
            OlcboxVpnState.olcConnection.value.connecting)
        assertEquals(OlcConnectionStage.Checking, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(OlcChannelRole.Reserve, OlcboxVpnState.olcConnection.value.channel)
        assertEquals(GeneralTunnelPhase.TransportVerified, OlcboxVpnState.generalTunnel.value.phase)
        assertSame(VpnStatus.Reconnecting, OlcboxVpnState.status.value)
        OlcboxVpnState.setGeneralChannelHealth(true)
        assertEquals(OlcConnectionStage.ChannelReady, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Denied)
        OlcboxVpnState.setGeneralChannelHealth(true)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        OlcboxVpnState.setGeneralChannelHealth(true)
        assertFalse(OlcboxVpnState.olcConnection.value.active)
    }

    @Test fun stopAndErrorRemainActionableBeforePhasePublication() {
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Primary)
        OlcboxVpnState.setStatus(VpnStatus.Stopping)
        assertEquals(OlcConnectionStage.Stopping, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setStatus(VpnStatus.Error("synthetic"))
        assertEquals(OlcConnectionStage.Unavailable, OlcboxVpnState.olcConnection.value.stage)
        assertTrue(OlcboxVpnState.olcConnection.value.active)
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        assertFalse(OlcboxVpnState.olcConnection.value.active)
        assertEquals(OlcChannelRole.Primary, OlcboxVpnState.olcConnection.value.channel)
    }

    @Test fun explicitDenialSurvivesCleanupUntilResetOrNewAttempt() {
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Denied)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(OlcChannelRole.Reserve, OlcboxVpnState.olcConnection.value.channel)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Stopping)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(GeneralTunnelPhase.Denied, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(GeneralTunnelPhase.Denied, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Checking)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(GeneralTunnelPhase.Denied, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(GeneralTunnelPhase.Denied, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setStatus(VpnStatus.Stopping)
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        OlcboxVpnState.setStatus(VpnStatus.Error("synthetic cleanup failure"))
        assertEquals(OlcConnectionStage.Denied, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(GeneralTunnelPhase.Denied, OlcboxVpnState.generalTunnel.value.phase)
        assertTrue(OlcboxVpnState.olcConnection.value.active)
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        assertFalse(OlcboxVpnState.olcConnection.value.active)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Denied)
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Primary)
        assertEquals(OlcConnectionStage.Connecting, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(OlcChannelRole.Primary, OlcboxVpnState.olcConnection.value.channel)
        assertEquals(GeneralTunnelPhase.Checking, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
        assertEquals(OlcConnectionStage.ChannelReady, OlcboxVpnState.olcConnection.value.stage)
        assertEquals(GeneralTunnelPhase.TransportVerified, OlcboxVpnState.generalTunnel.value.phase)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Denied)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
        assertFalse(OlcboxVpnState.olcConnection.value.active)
    }

    @Test fun nextOrdinaryStartDoesNotRetainReservePresentation() {
        OlcboxVpnState.setGeneralChannel(OlcChannelRole.Reserve, true)
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        OlcboxVpnState.setStatus(VpnStatus.Connecting)
        assertFalse(OlcboxVpnState.olcConnection.value.active)
    }
}
