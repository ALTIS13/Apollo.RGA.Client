package org.olcbox.app.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class OrdinaryNetworkRefreshPolicyTest {
    private val oldDns = listOf("192.0.2.1", "192.0.2.2")

    @Test fun sameWifiAndSameDnsCanRemainConnected() {
        assertEquals(NetworkRefreshDecision.Keep, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Wifi,
            previousDns = oldDns, nextDns = oldDns.toList(), status = VpnStatus.Connected
        ))
    }

    @Test fun repeatedCellularFactsDoNotRestartAnAlreadyConnectedTransport() {
        assertEquals(NetworkRefreshDecision.Keep, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Cellular, nextTransport = UpstreamTransport.Cellular,
            previousDns = oldDns, nextDns = oldDns.toList(), status = VpnStatus.Connected
        ))
    }

    @Test fun initialOfflineStartRecoversWhenCompleteNetworkFactsArrive() {
        assertEquals(NetworkRefreshDecision.Recover, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = false,
            previousTransport = null, nextTransport = UpstreamTransport.Cellular,
            previousDns = null, nextDns = oldDns, status = VpnStatus.Reconnecting
        ))
    }

    @Test fun changedWifiDnsOrPhysicalNetworkRequiresRecovery() {
        assertEquals(NetworkRefreshDecision.Recover, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Wifi,
            previousDns = oldDns, nextDns = listOf("192.0.2.3"), status = VpnStatus.Connected
        ))
        assertEquals(NetworkRefreshDecision.Recover, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = false,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Wifi,
            previousDns = oldDns, nextDns = oldDns, status = VpnStatus.Connected
        ))
        assertEquals(NetworkRefreshDecision.Recover, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = false,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Cellular,
            previousDns = oldDns, nextDns = oldDns, status = VpnStatus.Connected
        ))
    }

    @Test fun reconnectingNeverPromotesOnBridgeLiveness() {
        assertEquals(NetworkRefreshDecision.Recover, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Wifi,
            previousDns = oldDns, nextDns = oldDns, status = VpnStatus.Reconnecting
        ))
    }

    @Test fun incompleteFactsWaitWithoutLoop() {
        assertEquals(NetworkRefreshDecision.AwaitFacts, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = null,
            previousDns = oldDns, nextDns = oldDns, status = VpnStatus.Connected
        ))
        assertEquals(NetworkRefreshDecision.AwaitFacts, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Wifi,
            previousDns = oldDns, nextDns = null, status = VpnStatus.Connected
        ))
        assertEquals(NetworkRefreshDecision.AwaitFacts, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Wifi,
            previousDns = null, nextDns = oldDns, status = VpnStatus.Connected
        ))
    }

    @Test fun oldLteEventIsIgnoredAfterWifiSelection() {
        assertEquals(NetworkRefreshDecision.Ignore, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = false, sameNetwork = false,
            previousTransport = UpstreamTransport.Wifi, nextTransport = UpstreamTransport.Cellular,
            previousDns = oldDns, nextDns = listOf("192.0.2.3"), status = VpnStatus.Connected
        ))
    }
}
