package org.olcbox.app.net

import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.SubscriptionMetadata

internal object SubscriptionTunnelAccess {
    fun denial(bundle: LocationBundleV4, storageId: String?, nowEpochMs: Long): String? {
        val entry = bundle.locations.firstOrNull { it.storageId == storageId } ?: return "No active location"
        return when (entry.metadata?.subscription?.accessStateAt(nowEpochMs)) {
            SubscriptionMetadata.AccessState.EXPIRED -> "Subscription expired"
            SubscriptionMetadata.AccessState.LIMITED -> "Subscription traffic limit reached"
            else -> null // Unknown headers are not server admission, nor invented expiry.
        }
    }
}
