package org.olcbox.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Foundation-only redacted parser, with no network fetch or UI wiring yet. Byte fields
 * must not be presented as a shared Xray+OlcRTC quota until cycle accounting exists.
 * It is neither an OlcRTC grant nor server admission.
 */
@Serializable
internal data class AccessPolicySnapshotV1(
    val schemaVersion: Int,
    val opaqueSubject: String,
    val revision: Long,
    val serverTimeEpochMs: Long,
    val expiresAtEpochMs: Long,
    val refreshAfterEpochMs: Long,
    val status: SubscriptionStatus,
    val totalLimitBytes: Long,
    val confirmedUsedBytes: Long,
    val remainingBytes: Long?,
    val olcrtcEnabled: Boolean,
    val ordinaryAllowed: Boolean,
    val alternateAllowed: Boolean,
    val reason: AccessReason
) {
    @Serializable
    internal enum class SubscriptionStatus { ACTIVE, DISABLED, LIMITED, EXPIRED, UNAVAILABLE }

    @Serializable
    internal enum class AccessReason {
        NONE,
        OLCRTC_DISABLED,
        SUBSCRIPTION_DISABLED,
        SUBSCRIPTION_EXPIRED,
        SUBSCRIPTION_LIMITED,
        TRAFFIC_EXHAUSTED,
        SUBSCRIPTION_UNAVAILABLE,
        ENFORCEMENT_UNAVAILABLE
    }

    fun isFreshAt(nowEpochMs: Long): Boolean =
        nowEpochMs >= serverTimeEpochMs - MAX_CLOCK_SKEW_MS &&
            nowEpochMs < refreshAfterEpochMs && nowEpochMs < expiresAtEpochMs

    fun mayShowAlternate(policy: RuntimeLocationPolicy, nowEpochMs: Long): Boolean =
        policy.permits(LocationKind.Olcrtc) && olcrtcEnabled && alternateAllowed &&
            status == SubscriptionStatus.ACTIVE && reason == AccessReason.NONE && isFreshAt(nowEpochMs)

    private fun isValid(): Boolean =
        schemaVersion == 1 && OPAQUE_SUBJECT.matches(opaqueSubject) && revision > 0 &&
            serverTimeEpochMs > 0 && expiresAtEpochMs > 0 &&
            refreshAfterEpochMs > serverTimeEpochMs &&
            totalLimitBytes >= 0 && confirmedUsedBytes >= 0 &&
            (remainingBytes == null || remainingBytes >= 0) &&
            quotaConsistent() &&
            (!alternateAllowed || (olcrtcEnabled && status == SubscriptionStatus.ACTIVE &&
                reason == AccessReason.NONE && (remainingBytes == null || remainingBytes > 0)))

    private fun quotaConsistent(): Boolean = when {
        totalLimitBytes == 0L -> remainingBytes == null
        remainingBytes == null -> !alternateAllowed
        else -> remainingBytes <= (totalLimitBytes - confirmedUsedBytes).coerceAtLeast(0L)
    }

    companion object {
        private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1_000L
        private val OPAQUE_SUBJECT = Regex("[A-Za-z0-9_-]{16,128}")
        private val decoder = Json { ignoreUnknownKeys = false; isLenient = false }

        /** Never include the input or parser exception in logs: it may carry a bearer URL. */
        fun decode(json: String): AccessPolicySnapshotV1? = try {
            decoder.decodeFromString<AccessPolicySnapshotV1>(json).takeIf { it.isValid() }
        } catch (_: Exception) {
            null
        }
    }
}
