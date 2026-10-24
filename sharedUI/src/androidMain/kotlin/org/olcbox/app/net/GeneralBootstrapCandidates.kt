package org.olcbox.app.net

import org.olcbox.app.vpn.OlcChannelRole

/** Full immutable tuple; discovery is still not a lease. */
internal class GeneralBootstrapCandidate(val channel: OlcChannelRole, val selection: GeneralBootstrapSelection) {
    override fun toString() = "GeneralBootstrapCandidate([redacted])"
}

internal enum class GeneralCandidateOutcome { Handled, Skipped, Retired }

/** Channel policy is shared by discovery, encrypted cache and selection. A
 * reserve is still one exact managed tuple, not a wildcard provider slot. */
internal fun GeneralBootstrapV1.matchesGeneralChannel(channel: OlcChannelRole): Boolean = when (channel) {
    OlcChannelRole.Primary -> provider == "telemost"
    OlcChannelRole.Reserve -> usesManagedLease && (provider == "salutejazz" || provider == "vkcalls" || provider == "mtslink")
}

/** A serial transition owns convergence even if the next candidate never starts.
 * Cancellation is deliberately left to the existing service cleanup owner. */
internal suspend fun runGeneralBootstrapCandidates(
    candidates: List<GeneralBootstrapCandidate>,
    current: suspend () -> Boolean,
    beforeAttempt: (GeneralBootstrapCandidate, Boolean) -> Boolean,
    attempt: suspend (GeneralBootstrapCandidate, Boolean) -> GeneralCandidateOutcome,
    finishRetired: suspend () -> Boolean
): Boolean {
    require(candidates.size <= 2)
    var retired = false
    for ((index, candidate) in candidates.withIndex()) {
        if (!current() || !beforeAttempt(candidate, index > 0)) break
        when (attempt(candidate, index < candidates.lastIndex)) {
            GeneralCandidateOutcome.Handled -> return true
            GeneralCandidateOutcome.Skipped -> Unit
            GeneralCandidateOutcome.Retired -> retired = true
        }
    }
    return if (retired) finishRetired() else false
}

internal fun generalBootstrapCandidates(
    primary: GeneralBootstrapSelection?, reserve: GeneralBootstrapSelection?,
    key: PersonalJoinSigningKey, now: Long
): List<GeneralBootstrapCandidate> {
    fun usable(value: GeneralBootstrapSelection?, channel: OlcChannelRole) = value?.takeIf {
        it.metadata.usesManagedLease && it.metadata.matchesGeneralChannel(channel) && it.metadata.hasValidProviderLocator &&
            it.matchesKeyAndIsUsableAt(key, now)
    }
    val first = usable(primary, OlcChannelRole.Primary)
    val backup = usable(reserve, OlcChannelRole.Reserve)?.takeIf {
        if (first == null) true else {
            val a = first.metadata; val b = it.metadata
            a.panelInstance == b.panelInstance && a.userId == b.userId && a.installationId == b.installationId &&
                a.country == b.country && a.policyRevision == b.policyRevision &&
                a.roomId != b.roomId && a.roomKey != b.roomKey
        }
    }
    return listOfNotNull(first?.let { GeneralBootstrapCandidate(OlcChannelRole.Primary, it) },
        backup?.let { GeneralBootstrapCandidate(OlcChannelRole.Reserve, it) })
}
