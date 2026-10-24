package org.olcbox.app.vpn

/** Observation only: no admission, Connected, full-TUN or cleanup capability. */
internal enum class GeneralTransportHealth33 { HEALTHY, MISSING, STALE, CLOSED, STOPPING, STOPPED, UNKNOWN;
    companion object {
        fun fromNative(value: String?): GeneralTransportHealth33 = when (value) {
            "HEALTHY" -> HEALTHY; "MISSING" -> MISSING; "STALE" -> STALE; "CLOSED" -> CLOSED
            "STOPPING" -> STOPPING; "STOPPED" -> STOPPED; else -> UNKNOWN
        }
    }
}

internal data class GeneralTransportObservation33(val health: GeneralTransportHealth33, val recoveryDue: Boolean)

/** One finite episode attached to one captured consumer entry, never a grant end.
 * Call under that consumer's lock. Only an actual Healthy observation resets it.
 * Missing/Stale changes and diagnostics never renew its original start. */
internal class GeneralTransportLossEpisode33 {
    private var began: Long? = null
    fun observe(health: GeneralTransportHealth33, now: Long): Boolean {
        if (health == GeneralTransportHealth33.HEALTHY) { began = null; return false }
        if (health != GeneralTransportHealth33.MISSING && health != GeneralTransportHealth33.STALE) return true
        if (now < 0) return true
        val first = began ?: now.also { began = it }
        return now < first || now - first >= 60_000
    }
}
