package org.olcbox.app.vpn.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus

class PersonalTunFlowGateTest {
    private val runtime = PersonalTunProof.RuntimeStamp(
        generation = 7, networkId = 42, snapshotRevision = 3,
        transportEpoch = 11, bridgeEpoch = 13, proofEpoch = 17,
    )
    private val liveCounters = longArrayOf(99, 4, 5, 240, 360, 0x0A005858)

    private fun verify(
        current: PersonalTunProof.RuntimeStamp = runtime,
        native: LongArray? = liveCounters,
        receiptLocalIpv4: String = "10.0.88.88",
    ) = PersonalTunProof.validateExactFlow(
        expected = runtime,
        current = current,
        observerEpoch = 99,
        receiptLocalIpv4 = receiptLocalIpv4,
        expectedTunIpv4 = "10.0.88.88",
        native = native,
    )

    @After fun reset() { OlcboxVpnState.setStatus(VpnStatus.Disconnected) }

    @Test fun exactBidirectionalPayloadAndCurrentRuntimeProduceEvidence() {
        assertNotNull(verify())
    }

    @Test fun nativeEpochPacketsPayloadAndTunAddressMustAllMatch() {
        assertNull(verify(native = liveCounters.clone().apply { this[0] = 98 }))
        assertNull(verify(native = liveCounters.clone().apply { this[1] = 0 }))
        assertNull(verify(native = liveCounters.clone().apply { this[2] = 0 }))
        assertNull(verify(native = liveCounters.clone().apply { this[3] = 0 }))
        assertNull(verify(native = liveCounters.clone().apply { this[4] = 0 }))
        assertNull(verify(native = liveCounters.clone().apply { this[5] = 0x0A005859 }))
        assertNull(verify(receiptLocalIpv4 = "10.0.88.89"))
        assertNull(verify(native = null))
        assertNull(verify(native = longArrayOf(99, 1)))
    }

    @Test fun replacementRuntimeOrNetworkCannotReuseOldFlowEvidence() {
        assertNull(verify(current = runtime.copy(generation = 8)))
        assertNull(verify(current = runtime.copy(networkId = 43)))
        assertNull(verify(current = runtime.copy(snapshotRevision = 4)))
        assertNull(verify(current = runtime.copy(transportEpoch = 12)))
        assertNull(verify(current = runtime.copy(bridgeEpoch = 14)))
        assertNull(verify(current = runtime.copy(proofEpoch = 18)))
    }

    @Test fun replacementHandoffWithSameVisibleCheckingPhaseRejectsOldEvidence() {
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        val oldPhaseEpoch = OlcboxVpnState.personalPhaseEpoch()
        val proof = verify()!!

        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.Ordinary)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)

        assertFalse(OlcboxVpnState.publishPersonalWorking(oldPhaseEpoch, proof))
        assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
    }

    @Test fun stopOrDisconnectRejectsEvenPreviouslyValidEvidence() {
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        val epoch = OlcboxVpnState.personalPhaseEpoch()
        val proof = verify()!!

        OlcboxVpnState.setStatus(VpnStatus.Stopping)
        assertFalse(OlcboxVpnState.publishPersonalWorking(epoch, proof))
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        assertFalse(OlcboxVpnState.publishPersonalWorking(epoch, proof))
    }

    @Test fun freshEvidenceOnlyPromotesCheckingOlcRtcAndReconnectInvalidatesWorking() {
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        val epoch = OlcboxVpnState.personalPhaseEpoch()

        assertTrue(OlcboxVpnState.publishPersonalWorking(epoch, verify()!!))
        assertEquals(PersonalTunnelPhase.Working, OlcboxVpnState.personalTunnel.value.phase)
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
    }
}
