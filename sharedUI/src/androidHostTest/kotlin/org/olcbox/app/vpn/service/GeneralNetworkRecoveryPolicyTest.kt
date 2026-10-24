package org.olcbox.app.vpn.service

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.net.PhysicalNetworkSnapshotLedger
import org.olcbox.app.vpn.GeneralTransportHealth33
import org.olcbox.app.vpn.UpstreamTransport
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
class GeneralNetworkRecoveryPolicyTest {
    private val snapshot = PhysicalNetworkSnapshot(7, 3, 9, UpstreamTransport.Cellular,
        usable = true, validated = false, captive = false, dnsServers = listOf("192.0.2.1"))

    @Test fun androidValidationRefreshDoesNotDestroyAHealthyAdmittedChannel() {
        val ledger = PhysicalNetworkSnapshotLedger()
        ledger.available(7, 3)
        ledger.capabilities(7, 3, UpstreamTransport.Cellular, true, false, false)
        val admitted = ledger.linkProperties(7, 3, 1, listOf("192.0.2.1"))!!
        val network = ShadowNetwork.newInstance(107)
        val revalidated = ledger.capabilities(7, 3, UpstreamTransport.Cellular, true, true, false)!!
        assertNotEquals(admitted, revalidated) // Probe freshness remains stricter than active socket identity.
        val reason = GeneralNetworkRecoveryPolicy.assess(network, admitted, network, revalidated,
            true, true, GeneralTransportHealth33.HEALTHY)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.Healthy, reason)
        assertFalse(GeneralNetworkRecoveryPolicy.recoveryRequired(reason, nativeRecoveryDue = true))
        val validationLost = ledger.capabilities(7, 3, UpstreamTransport.Cellular, true, false, false)!!
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.Healthy,
            GeneralNetworkRecoveryPolicy.assess(network, admitted, network, validationLost,
                true, true, GeneralTransportHealth33.HEALTHY))
        val changedLink = ledger.linkProperties(7, 3, 2, listOf("192.0.2.1"))!!
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.SnapshotChanged,
            GeneralNetworkRecoveryPolicy.assess(network, admitted, network, changedLink,
                true, true, GeneralTransportHealth33.HEALTHY))
    }

    @Test fun callbackDoesNotRetireForAnotherFrameworkWrapperOfSameNetwork() {
        val captured = ShadowNetwork.newInstance(101)
        val queried = ShadowNetwork.newInstance(101)
        assertNotSame(captured, queried)
        assertEquals(captured, queried)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.Healthy,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, queried, snapshot, true,
                transportHealth = GeneralTransportHealth33.HEALTHY))
    }

    @Test fun activePathEquivalenceNeverHidesRealOrUnknownPathChanges() {
        val known = snapshot.copy(linkRevision = 1)
        for (changed in listOf(known.copy(generation = 8), known.copy(networkId = 4),
            known.copy(linkRevision = 2), known.copy(linkRevision = null), known.copy(revision = 8),
            known.copy(transport = UpstreamTransport.Wifi), known.copy(usable = false),
            known.copy(captive = true), known.copy(dnsServers = listOf("192.0.2.2")), null)) {
            assertFalse(GeneralNetworkRecoveryPolicy.sameActivePath(known, changed))
        }
        assertFalse(GeneralNetworkRecoveryPolicy.sameActivePath(snapshot, snapshot.copy(revision = 10)))
        val network = ShadowNetwork.newInstance(108)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.TransportStale,
            GeneralNetworkRecoveryPolicy.assess(network, known, network,
                known.copy(revision = 10, validated = true), true, true, GeneralTransportHealth33.STALE))
        assertEquals(GeneralNetworkRecoveryPolicy.SnapshotDelta.VALIDATION,
            GeneralNetworkRecoveryPolicy.snapshotDelta(known, known.copy(revision = 10, validated = true)))
        assertEquals(GeneralNetworkRecoveryPolicy.SnapshotDelta.LINK,
            GeneralNetworkRecoveryPolicy.snapshotDelta(known, known.copy(linkRevision = 2)))
    }

    @Test fun watchdogDoesNotRestartHealthyOwnerForEquivalentNetworkWrapper() {
        val captured = ShadowNetwork.newInstance(102)
        val queried = ShadowNetwork.newInstance(102)
        assertNotSame(captured, queried)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.Healthy,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, queried, snapshot.copy(), true, true,
                GeneralTransportHealth33.HEALTHY))
    }

    @Test fun equivalentNetworkWithoutTransportObservationIsNotHealthy() {
        val captured = ShadowNetwork.newInstance(106)
        val queried = ShadowNetwork.newInstance(106)
        val reason = GeneralNetworkRecoveryPolicy.assess(captured, snapshot, queried, snapshot, true)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.TransportUnknown, reason)
        assertTrue(GeneralNetworkRecoveryPolicy.recoveryRequired(reason, nativeRecoveryDue = false))
    }

    @Test fun actualNetworkOrSnapshotChangeStillRequiresRecovery() {
        val captured = ShadowNetwork.newInstance(103)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.NetworkChanged,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, ShadowNetwork.newInstance(104), snapshot, true))
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.SnapshotChanged,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, captured, snapshot.copy(revision = 10), true))
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.SnapshotChanged,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, captured, snapshot.copy(generation = 8), true))
    }

    @Test fun missingNetworkStoppedOwnerAndBrokenBridgeNeverLookHealthy() {
        val captured = ShadowNetwork.newInstance(105)
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.NetworkChanged,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, null, null, true))
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.RuntimeStopped,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, captured, snapshot, false))
        assertEquals(GeneralNetworkRecoveryPolicy.Reason.BridgeStopped,
            GeneralNetworkRecoveryPolicy.assess(captured, snapshot, captured, snapshot, true, false))
    }
}
