package org.olcbox.app.net

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Parcel
import java.util.IdentityHashMap
import org.olcbox.app.vpn.UpstreamTransport

/** Translates ordered callback values without re-querying ConnectivityManager. */
internal class AndroidPhysicalNetworkSnapshots(
    private val generation: Long,
    private val ledger: PhysicalNetworkSnapshotLedger
) {
    private data class NetworkEntry(
        val id: Long,
        var link: LinkProperties? = null,
        var linkRevision: Long = 0
    )

    private val networks = IdentityHashMap<Network, NetworkEntry>()
    private var nextNetworkId = 1L
    private var closed = false

    @Synchronized
    fun onAvailable(network: Network): PhysicalNetworkSnapshot? {
        if (closed) return null
        val existing = networks[network]
        if (existing != null) return ledger.current(generation, existing.id)
        val id = nextNetworkId++
        val snapshot = ledger.available(generation, id) ?: return null
        networks[network] = NetworkEntry(id)
        return snapshot
    }

    @Synchronized
    fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities): PhysicalNetworkSnapshot? {
        if (closed) return null
        val entry = networks[network] ?: return null
        val usable = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
        return ledger.capabilities(
            generation,
            entry.id,
            transport,
            usable,
            usable && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        )
    }

    @Synchronized
    fun onLinkPropertiesChanged(network: Network, link: LinkProperties): PhysicalNetworkSnapshot? {
        if (closed) return null
        val entry = networks[network] ?: return null
        val copied = copyOf(link)
        if (entry.link != copied) {
            entry.link = copied
            entry.linkRevision++
        }
        val dns = copied.dnsServers.mapNotNull { it.hostAddress }
        return ledger.linkProperties(generation, entry.id, entry.linkRevision, dns)
    }

    @Synchronized
    fun onLost(network: Network) {
        if (closed) return
        networks.remove(network)?.let { ledger.lost(generation, it.id) }
    }

    @Synchronized
    fun current(network: Network): PhysicalNetworkSnapshot? {
        if (closed) return null
        val entry = networks[network] ?: return null
        return ledger.current(generation, entry.id)
    }

    @Synchronized
    fun networkFor(snapshot: PhysicalNetworkSnapshot): Network? {
        if (closed || snapshot.generation != generation) return null
        if (ledger.current(generation, snapshot.networkId)?.revision != snapshot.revision) return null
        return networks.entries.firstOrNull { it.value.id == snapshot.networkId }?.key
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        networks.clear()
        ledger.cancel(generation)
    }

    private fun copyOf(link: LinkProperties): LinkProperties {
        val parcel = Parcel.obtain()
        return try {
            link.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            LinkProperties.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
