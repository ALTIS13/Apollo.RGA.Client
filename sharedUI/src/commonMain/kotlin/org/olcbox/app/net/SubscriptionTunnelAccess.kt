package org.olcbox.app.net

import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.SubscriptionMetadata

internal object SubscriptionTunnelAccess {
    fun denial(
        bundle: LocationBundleV4,
        storageId: String?,
        nowEpochMs: Long,
        policy: RuntimeLocationPolicy = RuntimeLocationPolicy.OrdinaryOnly
    ): String? {
        val entry = bundle.locations.firstOrNull { it.storageId == storageId } ?: return "No active location"
        policy.denial(entry.location.kind)?.let { return it }
        return when (entry.metadata?.subscription?.accessStateAt(nowEpochMs)) {
            SubscriptionMetadata.AccessState.DEVICE_DENIED -> "This device is not allowed to use the subscription"
            SubscriptionMetadata.AccessState.EXPIRED -> "Subscription expired"
            SubscriptionMetadata.AccessState.LIMITED -> "Subscription traffic limit reached"
            else -> null // Unknown headers are not server admission, nor invented expiry.
        }
    }
}
