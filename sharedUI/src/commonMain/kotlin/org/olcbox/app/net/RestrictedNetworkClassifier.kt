package org.olcbox.app.net

internal enum class ProbeOutcome { Answered, Failed, Unknown }

internal enum class RestrictedAssessment {
    Inconclusive,
    OrdinaryAvailable,
    LocalBridgeFailure,
    RestrictedSuspect
}

internal data class DirectProbeRound(
    val generation: Long,
    val networkId: Long,
    val revision: Long,
    val completedAtMs: Long,
    val foreignA: ProbeOutcome,
    val foreignB: ProbeOutcome,
    val domestic: ProbeOutcome
)

/** Pure evidence reducer. It neither probes a network nor authorizes a transport switch. */
internal class RestrictedNetworkClassifier {
    private data class NetworkState(
        val generation: Long,
        val networkId: Long,
        val revision: Long,
        val cellular: Boolean,
        val captive: Boolean,
        val sinceMs: Long
    ) {
        fun matches(generation: Long, networkId: Long, revision: Long): Boolean =
            this.generation == generation && this.networkId == networkId && this.revision == revision
    }

    private data class Attempt(
        val generation: Long,
        val networkId: Long,
        val revision: Long,
        val startedAtMs: Long,
        val rounds: MutableList<DirectProbeRound> = mutableListOf()
    ) {
        fun matches(generation: Long, networkId: Long, revision: Long): Boolean =
            this.generation == generation && this.networkId == networkId && this.revision == revision
    }

    private data class Cooldown(val attempts: Int, val nextAtMs: Long)

    private var network: NetworkState? = null
    private var attempt: Attempt? = null
    private var latestGeneration = Long.MIN_VALUE
    private var cancelledThroughGeneration = Long.MIN_VALUE
    private val cooldowns = LinkedHashMap<Long, Cooldown>()

    fun onNetwork(
        generation: Long,
        networkId: Long,
        revision: Long,
        cellular: Boolean,
        captive: Boolean,
        nowMs: Long
    ) {
        if (generation <= cancelledThroughGeneration || generation < latestGeneration) return
        val current = network
        if (current != null && current.generation == generation &&
            current.networkId == networkId && revision < current.revision
        ) return
        if (current != null && current.matches(generation, networkId, revision) &&
            current.cellular == cellular && current.captive == captive
        ) return

        latestGeneration = generation
        network = NetworkState(generation, networkId, revision, cellular, captive, nowMs)
        attempt = null
    }

    fun begin(generation: Long, networkId: Long, revision: Long, nowMs: Long): Boolean {
        val current = network ?: return false
        if (!current.matches(generation, networkId, revision) || !current.cellular || current.captive ||
            attempt != null || nowMs < current.sinceMs || nowMs - current.sinceMs < SETTLE_MS
        ) return false
        val previous = cooldowns[networkId]
        if (previous != null && nowMs < previous.nextAtMs) return false

        val count = (previous?.attempts ?: 0) + 1
        val delay = when (count) {
            1 -> 60_000L
            2 -> 120_000L
            3 -> 240_000L
            else -> 300_000L
        }
        val nextAt = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay
        cooldowns.remove(networkId)
        cooldowns[networkId] = Cooldown(count, nextAt)
        if (cooldowns.size > MAX_REMEMBERED_NETWORKS) cooldowns.remove(cooldowns.keys.first())
        attempt = Attempt(generation, networkId, revision, nowMs)
        return true
    }

    fun record(round: DirectProbeRound): Boolean {
        val current = attempt ?: return false
        if (!current.matches(round.generation, round.networkId, round.revision) ||
            round.completedAtMs < current.startedAtMs || current.rounds.size >= 2
        ) return false
        val last = current.rounds.lastOrNull()
        if (last != null && (round.completedAtMs < last.completedAtMs ||
                round.completedAtMs - last.completedAtMs < ROUND_SPACING_MS)
        ) return false
        current.rounds += round
        return true
    }

    fun finish(
        generation: Long,
        networkId: Long,
        revision: Long,
        nowMs: Long,
        selectedTransport: ProbeOutcome,
        tunBridge: ProbeOutcome
    ): RestrictedAssessment {
        val current = attempt ?: return RestrictedAssessment.Inconclusive
        if (!current.matches(generation, networkId, revision)) return RestrictedAssessment.Inconclusive

        if (selectedTransport == ProbeOutcome.Answered && tunBridge == ProbeOutcome.Failed) {
            attempt = null
            return RestrictedAssessment.LocalBridgeFailure
        }
        if (current.rounds.size != 2 || nowMs < current.rounds.last().completedAtMs) {
            return RestrictedAssessment.Inconclusive
        }

        attempt = null
        if (selectedTransport == ProbeOutcome.Answered && tunBridge == ProbeOutcome.Answered) {
            return RestrictedAssessment.OrdinaryAvailable
        }
        val restricted = selectedTransport == ProbeOutcome.Failed && current.rounds.all {
            it.foreignA == ProbeOutcome.Failed && it.foreignB == ProbeOutcome.Failed &&
                it.domestic == ProbeOutcome.Answered
        }
        return if (restricted) RestrictedAssessment.RestrictedSuspect else RestrictedAssessment.Inconclusive
    }

    fun cancel(generation: Long) {
        if (generation <= cancelledThroughGeneration) return
        cancelledThroughGeneration = generation
        if (network?.generation?.let { it <= generation } == true) {
            network = null
            attempt = null
        }
    }

    private companion object {
        const val SETTLE_MS = 5_000L
        const val ROUND_SPACING_MS = 3_000L
        const val MAX_REMEMBERED_NETWORKS = 8
    }
}
