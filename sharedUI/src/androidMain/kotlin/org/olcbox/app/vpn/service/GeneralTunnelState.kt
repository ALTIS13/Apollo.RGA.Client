package org.olcbox.app.vpn.service

enum class GeneralTunnelPhase { Inactive, Checking, TransportVerified, Unavailable, Denied, Stopping }

/** TransportVerified is SOCKS body evidence, never independent-UID/full-TUN qualification. */
data class GeneralTunnelUiState(val phase: GeneralTunnelPhase = GeneralTunnelPhase.Inactive) {
    val fullTunQualificationPending: Boolean get() = phase != GeneralTunnelPhase.Inactive
}
