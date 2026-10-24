package org.olcbox.app.vpn

import java.util.concurrent.atomic.AtomicBoolean

/** One-shot negotiation request; native Welcome and admission remain authoritative. */
class PersonalManagedUdpStartGate(
    private val isPersonalPackage: () -> Boolean,
    private val setNativeClaims: (String) -> Unit,
    private val setNativeContract: (Long) -> Unit,
    private val startNative: () -> Unit
) {
    private val pendingClaims = AtomicBoolean(false)

    fun setClaimsJSON(value: String) {
        pendingClaims.set(false)
        setNativeClaims(value)
        if (value.isNotBlank()) pendingClaims.set(true)
    }

    fun start() {
        val hasFreshClaims = pendingClaims.getAndSet(false)
        setNativeContract(if (hasFreshClaims && isPersonalPackage()) 2L else 0L)
        startNative()
    }
}
