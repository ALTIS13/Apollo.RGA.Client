package org.olcbox.app.vpn.service

internal object TunBridgePortPolicy {
    /** An existing TUN bridge may be retained only if its SOCKS endpoint stays the same. */
    fun canReuseBridge(bridgePort: Int?, currentCorePort: Int?, targetUsesCore: Boolean, proxyPort: Int): Boolean {
        if (!valid(bridgePort)) return false
        return if (targetUsesCore) {
            valid(currentCorePort) && bridgePort == currentCorePort
        } else {
            currentCorePort == null && bridgePort == proxyPort
        }
    }

    fun corePortForStart(tunMode: Boolean, reuseBridge: Boolean, bridgePort: Int?, freshPort: Int?, proxyPort: Int): Int? {
        val chosen = when {
            !tunMode -> proxyPort
            reuseBridge -> bridgePort
            else -> freshPort
        }
        return chosen?.takeIf(::valid)
    }

    /** Never report TUN Connected for a newly-started transport behind a stale bridge. */
    fun matchesActivePort(bridgePort: Int?, activeCorePort: Int?, proxyPort: Int): Boolean =
        valid(bridgePort) && bridgePort == (activeCorePort ?: proxyPort)

    private fun valid(port: Int?): Boolean = port != null && port in 1..65535
}
