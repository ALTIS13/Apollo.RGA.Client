package org.olcbox.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.vpn.NetworkRefreshDecision
import org.olcbox.app.vpn.OrdinaryNetworkRefreshPolicy
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class OrdinaryNetworkRecoveryServiceTest {
    @Test fun repeatedCellularCallbackDoesNotRestartConnectedService() {
        val fixture = serviceOnNetwork(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR)
        try {
            setField(fixture.service, "lastConnectedOrdinaryNetwork", fixture.network)
            setField(fixture.service, "lastConnectedOrdinaryTransport", UpstreamTransport.Cellular)
            setField(fixture.service, "lastConnectedOrdinaryDns", listOf("192.0.2.1"))
            setField(fixture.service, "currentNetwork", fixture.network)
            OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.setStatus(VpnStatus.Connected)

            handleNetworkChange(fixture.service, fixture.network, fixture.snapshot)

            assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
            assertNull(field(fixture.service, "recoveryJob"))
        } finally {
            release(fixture.service)
        }
    }

    @Test fun firstOfflineStartAcceptsUsableNetworkWithoutAnOldDnsBaseline() {
        val fixture = serviceOnNetwork(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR)
        try {
            OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)

            handleNetworkChange(fixture.service, fixture.network, fixture.snapshot)

            assertNotNull(field(fixture.service, "recoveryJob"))
            assertEquals(VpnStatus.Reconnecting, OlcboxVpnState.status.value)
        } finally {
            release(fixture.service)
        }
    }

    @Test fun networkReappearingWithTheSameHandleStillNeedsRecovery() {
        val fixture = serviceOnNetwork(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR)
        try {
            setField(fixture.service, "lastConnectedOrdinaryNetwork", fixture.network)
            setField(fixture.service, "lastConnectedOrdinaryNetworkId", fixture.snapshot.networkId)
            setField(fixture.service, "lastConnectedOrdinaryTransport", UpstreamTransport.Cellular)
            setField(fixture.service, "lastConnectedOrdinaryDns", listOf("192.0.2.1"))
            setField(fixture.service, "currentNetwork", fixture.network)
            OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.setStatus(VpnStatus.Connected)

            val snapshots = field(fixture.service, "physicalNetworkSnapshots") as AndroidPhysicalNetworkSnapshots
            snapshots.onLost(fixture.network)
            snapshots.onAvailable(fixture.network)
            snapshots.onCapabilitiesChanged(fixture.network, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
            val reappeared = snapshots.onLinkPropertiesChanged(fixture.network, link("192.0.2.1"))!!
            handleNetworkChange(fixture.service, fixture.network, reappeared)

            assertNotNull(field(fixture.service, "recoveryJob"))
        } finally {
            release(fixture.service)
        }
    }

    @Test fun dnsChangedWhileFrontWasStartingRemainsPendingAfterVerification() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setField(service, "connectivityManager", cm)
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true
        ))
        val network = cm.activeNetwork!!
        shadow.setNetworkCapabilities(network, wifiCapabilities())
        shadow.setLinkProperties(network, link("192.0.2.1"))
        val snapshots = AndroidPhysicalNetworkSnapshots(0, PhysicalNetworkSnapshotLedger())
        snapshots.onAvailable(network)
        snapshots.onCapabilitiesChanged(network, wifiCapabilities())
        snapshots.onLinkPropertiesChanged(network, link("192.0.2.1"))
        setField(service, "physicalNetworkSnapshots", snapshots)
        // A custom direct rule exercises the real rule-based front without
        // loading regional assets unavailable in Robolectric's resource reader.
        setField(service, "routingSettings", RoutingSettings(
            mode = RoutingMode.Global, directRules = listOf("example.com")
        ))

        // The front was built with A. Callback B arrives while verification is in flight.
        val routing = service.routingFor(network, LocationKind.Vless) as Routing.Rules
        assertEquals(DirectDns.Servers(listOf("192.0.2.1")), routing.directDns)
        shadow.setLinkProperties(network, link("192.0.2.2"))
        val changed = snapshots.onLinkPropertiesChanged(network, link("192.0.2.2"))!!
        assertFalse(markVerified(service, network))

        assertNull(field(service, "lastConnectedOrdinaryDns"))
        assertEquals(NetworkRefreshDecision.Recover, OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true, sameNetwork = true,
            previousTransport = UpstreamTransport.Wifi, nextTransport = changed.transport,
            previousDns = listOf("192.0.2.1"), nextDns = changed.dnsServers,
            status = VpnStatus.Connected
        ))
        release(service)
    }

    private data class ServiceFixture(
        val service: OlcboxVpnService,
        val network: Network,
        val snapshot: PhysicalNetworkSnapshot
    )

    private fun serviceOnNetwork(legacyType: Int, transport: Int): ServiceFixture {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setField(service, "connectivityManager", cm)
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, legacyType, 0, true, true
        ))
        val network = cm.activeNetwork!!
        shadow.setNetworkCapabilities(network, capabilities(transport))
        shadow.setLinkProperties(network, link("192.0.2.1"))
        val snapshots = AndroidPhysicalNetworkSnapshots(0, PhysicalNetworkSnapshotLedger())
        snapshots.onAvailable(network)
        snapshots.onCapabilitiesChanged(network, capabilities(transport))
        val snapshot = snapshots.onLinkPropertiesChanged(network, link("192.0.2.1"))!!
        setField(service, "physicalNetworkSnapshots", snapshots)
        return ServiceFixture(service, network, snapshot)
    }

    private fun handleNetworkChange(
        service: OlcboxVpnService,
        network: Network,
        snapshot: PhysicalNetworkSnapshot
    ) {
        OlcboxVpnService::class.java.getDeclaredMethod(
            "handleAllowedNetworkChange", Network::class.java,
            PhysicalNetworkSnapshot::class.java, String::class.java
        ).apply { isAccessible = true }.invoke(service, network, snapshot, "Test callback")
    }

    private fun release(service: OlcboxVpnService) {
        service.onDestroy()
        OlcboxVpnState.activeLocation = null
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
    }

    private fun capabilities(transport: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        Shadows.shadowOf(it).addTransportType(transport)
        Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun wifiCapabilities(): NetworkCapabilities = capabilities(NetworkCapabilities.TRANSPORT_WIFI)

    private fun link(dns: String): LinkProperties = LinkProperties().apply {
        setDnsServers(listOf(InetAddress.getByName(dns)))
    }

    private fun markVerified(service: OlcboxVpnService, network: Network): Boolean =
        OlcboxVpnService::class.java.getDeclaredMethod(
            "markVerifiedOrdinaryNetwork", Network::class.java, LocationConfig::class.java
        ).apply { isAccessible = true }
            .invoke(service, network, LocationConfig(kind = LocationKind.Vless)) as Boolean

    private fun setField(service: OlcboxVpnService, name: String, value: Any) {
        OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun field(service: OlcboxVpnService, name: String): Any? =
        OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
}
