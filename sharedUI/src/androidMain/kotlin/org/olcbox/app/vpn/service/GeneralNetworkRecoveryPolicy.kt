package org.olcbox.app.vpn.service

import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.vpn.GeneralTransportHealth33

/** Pure network-health decision; never grants access or substitutes for owner/bridge proof. */
internal object GeneralNetworkRecoveryPolicy {
    enum class Reason { Healthy, NetworkChanged, SnapshotChanged, RuntimeStopped, BridgeStopped,
        TransportMissing, TransportStale, TransportClosed, TransportUnknown }

    /** Only for an already admitted native owner; never probe/cache freshness.
     * Android validation is a reachability observation, not a new socket path.
     * Unknown link facts or a real LinkProperties change keep the strict fence. */
    fun sameActivePath(bound: PhysicalNetworkSnapshot, current: PhysicalNetworkSnapshot?): Boolean {
        if (bound == current) return true
        return current != null && bound.linkRevision != null && bound.linkRevision == current.linkRevision &&
            bound.generation == current.generation && bound.networkId == current.networkId &&
            current.revision >= bound.revision && bound.transport != null && bound.transport == current.transport &&
            bound.usable && current.usable && !bound.captive && !current.captive &&
            bound.dnsServers == current.dnsServers
    }

    enum class SnapshotDelta { NONE, MISSING, GENERATION, NETWORK, LINK, DNS, TRANSPORT, USABLE, CAPTIVE, VALIDATION, OBSERVATION }
    fun snapshotDelta(bound: PhysicalNetworkSnapshot, current: PhysicalNetworkSnapshot?): SnapshotDelta = when {
        current == null -> SnapshotDelta.MISSING
        bound.generation != current.generation -> SnapshotDelta.GENERATION
        bound.networkId != current.networkId -> SnapshotDelta.NETWORK
        bound.linkRevision != current.linkRevision -> SnapshotDelta.LINK
        bound.dnsServers != current.dnsServers -> SnapshotDelta.DNS
        bound.transport != current.transport -> SnapshotDelta.TRANSPORT
        bound.usable != current.usable -> SnapshotDelta.USABLE
        bound.captive != current.captive -> SnapshotDelta.CAPTIVE
        bound.validated != current.validated -> SnapshotDelta.VALIDATION
        bound.revision != current.revision -> SnapshotDelta.OBSERVATION
        else -> SnapshotDelta.NONE
    }

    fun assess(boundNetwork: Any, boundSnapshot: PhysicalNetworkSnapshot,
        currentNetwork: Any?, currentSnapshot: PhysicalNetworkSnapshot?,
        runtimeRunning: Boolean, bridgeReady: Boolean = true,
        transportHealth: GeneralTransportHealth33 = GeneralTransportHealth33.UNKNOWN): Reason = when {
        // Android recreates Network wrappers from Binder/Parcel. Network.equals represents
        // the logical framework network; wrapper identity is not a path change.
        boundNetwork != currentNetwork -> Reason.NetworkChanged
        !sameActivePath(boundSnapshot, currentSnapshot) -> Reason.SnapshotChanged
        !runtimeRunning -> Reason.RuntimeStopped
        !bridgeReady -> Reason.BridgeStopped
        transportHealth == GeneralTransportHealth33.HEALTHY -> Reason.Healthy
        transportHealth == GeneralTransportHealth33.MISSING -> Reason.TransportMissing
        transportHealth == GeneralTransportHealth33.STALE -> Reason.TransportStale
        transportHealth == GeneralTransportHealth33.CLOSED -> Reason.TransportClosed
        transportHealth == GeneralTransportHealth33.STOPPING || transportHealth == GeneralTransportHealth33.STOPPED -> Reason.RuntimeStopped
        else -> Reason.TransportUnknown
    }

    fun recoveryRequired(reason: Reason?, nativeRecoveryDue: Boolean): Boolean = when (reason) {
        Reason.Healthy -> false
        Reason.TransportMissing, Reason.TransportStale -> nativeRecoveryDue
        else -> true // Network/key/bridge/unsupported/custody failures receive no allowance.
    }
}
