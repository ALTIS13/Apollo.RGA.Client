package org.olcbox.app.net

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.UpstreamTransport
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class AndroidPhysicalNetworkSnapshotsTest {
    private fun caps(transport: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        Shadows.shadowOf(it).addTransportType(transport)
        Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun link(vararg dns: String): LinkProperties = LinkProperties().apply {
        setDnsServers(dns.map(InetAddress::getByName))
    }

    @Test fun sameNetworkAndEquivalentCallbackKeepRevision() {
        val adapter = AndroidPhysicalNetworkSnapshots(1, PhysicalNetworkSnapshotLedger())
        val network = ShadowNetwork.newInstance(101)
        adapter.onAvailable(network)
        val first = adapter.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_CELLULAR))!!
        val withLink = adapter.onLinkPropertiesChanged(network, link("192.0.2.1"))!!
        assertEquals(UpstreamTransport.Cellular, withLink.transport)
        assertTrue(withLink.isProbeReady)
        assertEquals(first.revision + 1, withLink.revision)

        assertEquals(withLink.revision,
            adapter.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_CELLULAR))!!.revision)
        assertEquals(withLink.revision,
            adapter.onLinkPropertiesChanged(network, link("192.0.2.1"))!!.revision)
        assertSame(network, adapter.networkFor(withLink))
    }

    @Test fun dnsChangeAndLostNetworkInvalidateSnapshot() {
        val adapter = AndroidPhysicalNetworkSnapshots(1, PhysicalNetworkSnapshotLedger())
        val network = ShadowNetwork.newInstance(102)
        adapter.onAvailable(network)
        adapter.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI))
        val old = adapter.onLinkPropertiesChanged(network, link("192.0.2.1"))!!
        val changed = adapter.onLinkPropertiesChanged(network, link("192.0.2.2"))!!
        assertEquals(old.revision + 1, changed.revision)
        assertNull(adapter.networkFor(old))
        assertSame(network, adapter.networkFor(changed))

        adapter.onLost(network)
        assertNull(adapter.current(network))
        assertNull(adapter.networkFor(changed))
        assertNull(adapter.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_WIFI)))
    }

    @Test fun oldRegistrationCannotPublishIntoNewGeneration() {
        val ledger = PhysicalNetworkSnapshotLedger()
        val oldAdapter = AndroidPhysicalNetworkSnapshots(1, ledger)
        val oldNetwork = ShadowNetwork.newInstance(103)
        oldAdapter.onAvailable(oldNetwork)
        val newAdapter = AndroidPhysicalNetworkSnapshots(2, ledger)
        val newNetwork = ShadowNetwork.newInstance(104)
        newAdapter.onAvailable(newNetwork)
        val current = newAdapter.onCapabilitiesChanged(newNetwork, caps(NetworkCapabilities.TRANSPORT_WIFI))!!
        assertNull(oldAdapter.onLinkPropertiesChanged(oldNetwork, link("192.0.2.1")))
        oldAdapter.close()
        assertSame(newNetwork, newAdapter.networkFor(current))
        assertEquals(current, newAdapter.current(newNetwork))
    }

    @Test fun copiedLinkPropertiesIgnoreCallerMutation() {
        val adapter = AndroidPhysicalNetworkSnapshots(1, PhysicalNetworkSnapshotLedger())
        val network = ShadowNetwork.newInstance(105)
        adapter.onAvailable(network)
        adapter.onCapabilitiesChanged(network, caps(NetworkCapabilities.TRANSPORT_CELLULAR))
        val input = link("192.0.2.1")
        val first = adapter.onLinkPropertiesChanged(network, input)!!
        input.setDnsServers(listOf(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("192.0.2.2")))
        assertEquals(listOf("192.0.2.1"), first.dnsServers)
        assertEquals(listOf("192.0.2.1"), adapter.current(network)?.dnsServers)
        val changed = adapter.onLinkPropertiesChanged(network, input)!!
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), changed.dnsServers)
        assertEquals(first.revision + 1, changed.revision)
        assertFalse(changed.captive)
    }

    @Test fun equivalentNetworkHandleFromManagerUsesCallbackFacts() {
        val adapter = AndroidPhysicalNetworkSnapshots(1, PhysicalNetworkSnapshotLedger())
        val callbackNetwork = ShadowNetwork.newInstance(201)
        val managerNetwork = ShadowNetwork.newInstance(201)
        assertEquals(callbackNetwork, managerNetwork)
        assertNotSame(callbackNetwork, managerNetwork)
        adapter.onAvailable(callbackNetwork)
        adapter.onCapabilitiesChanged(callbackNetwork, caps(NetworkCapabilities.TRANSPORT_WIFI))
        val snapshot = adapter.onLinkPropertiesChanged(callbackNetwork, link("192.0.2.1"))!!

        assertEquals(snapshot, adapter.current(managerNetwork))
        adapter.onLost(managerNetwork)
        assertNull(adapter.current(callbackNetwork))
    }
}
