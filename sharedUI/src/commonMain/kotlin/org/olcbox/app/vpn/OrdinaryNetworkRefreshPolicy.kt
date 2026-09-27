package org.olcbox.app.vpn

internal enum class NetworkRefreshDecision { Ignore, Keep, Recover, AwaitFacts }

/** A decision about the selected ordinary transport, never alternate admission. */
internal object OrdinaryNetworkRefreshPolicy {
    fun decide(
        eventIsSelected: Boolean,
        sameNetwork: Boolean,
        previousTransport: UpstreamTransport?,
        nextTransport: UpstreamTransport?,
        previousDns: List<String>?,
        nextDns: List<String>?,
        status: VpnStatus
    ): NetworkRefreshDecision {
        if (!eventIsSelected) return NetworkRefreshDecision.Ignore
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) {
            return NetworkRefreshDecision.Ignore
        }
        if (nextTransport == null || nextDns.isNullOrEmpty() || previousDns.isNullOrEmpty()) {
            return NetworkRefreshDecision.AwaitFacts
        }
        if (status is VpnStatus.Connected && sameNetwork &&
            previousTransport == UpstreamTransport.Wifi && nextTransport == UpstreamTransport.Wifi &&
            previousDns == nextDns
        ) return NetworkRefreshDecision.Keep
        return NetworkRefreshDecision.Recover
    }
}
