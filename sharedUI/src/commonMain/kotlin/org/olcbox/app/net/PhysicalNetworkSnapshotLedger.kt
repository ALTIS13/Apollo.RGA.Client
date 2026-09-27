package org.olcbox.app.net

import org.olcbox.app.vpn.UpstreamTransport

internal data class PhysicalNetworkSnapshot(
    val generation: Long,
    val networkId: Long,
    val revision: Long,
    val transport: UpstreamTransport?,
    val usable: Boolean,
    val validated: Boolean,
    val captive: Boolean,
    val dnsServers: List<String>?
) {
    val isProbeReady: Boolean
        get() = transport == UpstreamTransport.Cellular && usable && !captive && !dnsServers.isNullOrEmpty()
}

/** Retains only callback-supplied facts. It does not probe or choose a transport. */
internal class PhysicalNetworkSnapshotLedger {
    private data class Entry(
        var snapshot: PhysicalNetworkSnapshot,
        var capabilitiesReceived: Boolean = false,
        var linkRevision: Long? = null,
        var lost: Boolean = false
    )

    private var latestGeneration = Long.MIN_VALUE
    private var cancelledThroughGeneration = Long.MIN_VALUE
    private val entries = LinkedHashMap<Long, Entry>()

    fun available(generation: Long, networkId: Long): PhysicalNetworkSnapshot? {
        if (!acceptGeneration(generation)) return null
        entries[networkId]?.let { return if (it.lost) null else it.snapshot }
        val snapshot = PhysicalNetworkSnapshot(generation, networkId, 0, null, false, false, false, null)
        entries[networkId] = Entry(snapshot)
        if (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        return snapshot
    }

    fun capabilities(
        generation: Long,
        networkId: Long,
        transport: UpstreamTransport?,
        usable: Boolean,
        validated: Boolean,
        captive: Boolean
    ): PhysicalNetworkSnapshot? {
        val entry = entry(generation, networkId) ?: return null
        val previous = entry.snapshot
        if (entry.capabilitiesReceived && previous.transport == transport && previous.usable == usable &&
            previous.validated == validated && previous.captive == captive
        ) return previous
        entry.capabilitiesReceived = true
        return previous.copy(
            revision = previous.revision + 1,
            transport = transport,
            usable = usable,
            validated = validated,
            captive = captive
        ).also { entry.snapshot = it }
    }

    fun linkProperties(
        generation: Long,
        networkId: Long,
        linkRevision: Long,
        dnsServers: List<String>
    ): PhysicalNetworkSnapshot? {
        val entry = entry(generation, networkId) ?: return null
        val previous = entry.snapshot
        val lastLinkRevision = entry.linkRevision
        if (lastLinkRevision != null && linkRevision < lastLinkRevision) return previous
        if (lastLinkRevision == linkRevision && previous.dnsServers == dnsServers) return previous
        entry.linkRevision = linkRevision
        return previous.copy(
            revision = previous.revision + 1,
            dnsServers = ArrayList(dnsServers)
        ).also { entry.snapshot = it }
    }

    fun lost(generation: Long, networkId: Long) {
        entry(generation, networkId)?.lost = true
    }

    fun current(generation: Long, networkId: Long): PhysicalNetworkSnapshot? =
        if (generation == latestGeneration && generation > cancelledThroughGeneration) {
            entries[networkId]?.takeUnless { it.lost }?.snapshot
        } else null

    fun cancel(generation: Long) {
        if (generation > cancelledThroughGeneration) cancelledThroughGeneration = generation
        if (latestGeneration <= generation) entries.clear()
    }

    private fun entry(generation: Long, networkId: Long): Entry? =
        if (generation == latestGeneration && generation > cancelledThroughGeneration) {
            entries[networkId]?.takeUnless { it.lost }
        } else null

    private fun acceptGeneration(generation: Long): Boolean {
        if (generation <= cancelledThroughGeneration || generation < latestGeneration) return false
        if (generation > latestGeneration) {
            entries.clear()
            latestGeneration = generation
        }
        return true
    }

    private companion object {
        const val MAX_ENTRIES = 16
    }
}
