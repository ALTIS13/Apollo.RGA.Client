package org.olcbox.app.net

import org.olcbox.app.data.model.*
import kotlin.test.*

class SubscriptionTunnelAccessTest {
    @Test fun releaseDeniesStoredOlcrtcBeforeServiceCanStartOrRecoverIt() {
        val room = LocationEntry.from("room", LocationConfig(name = "Private room", id = "r", key = "k"))
        val data = LocationBundleV4(activeLocationId = "room", locations = listOf(room))
        assertEquals("Experimental mode unavailable", SubscriptionTunnelAccess.denial(data, "room", 1000))
    }

    private fun bundle(metadata: SubscriptionMetadata) = LocationBundleV4(
        activeLocationId = "selected-other",
        locations = listOf(LocationEntry.from(
            "connected",
            LocationConfig(name = "Ordinary exit", kind = LocationKind.Vless,
                xrayConfig = kotlinx.serialization.json.buildJsonObject { }),
            metadata = LocationMetadata(subscription = metadata)
        ))
    )

    @Test fun connectedTunnelAndBenignRecoveryCannotOutliveKnownExpiry() {
        val data = bundle(SubscriptionMetadata(expiresAtEpochMs = 2000, expiryKnown = true))
        assertNull(SubscriptionTunnelAccess.denial(data, "connected", 1999))
        assertEquals("Subscription expired", SubscriptionTunnelAccess.denial(data, "connected", 2000))
        assertEquals("Subscription expired", SubscriptionTunnelAccess.denial(data, "connected", 5000))
    }

    @Test fun refreshedQuotaDeniesConnectedIdentityEvenWhenSelectionChanges() {
        assertNull(SubscriptionTunnelAccess.denial(bundle(SubscriptionMetadata(usedBytes = 90, totalBytes = 100, quotaKnown = true)), "connected", 1000))
        assertEquals("Subscription traffic limit reached", SubscriptionTunnelAccess.denial(bundle(SubscriptionMetadata(usedBytes = 100, totalBytes = 100, quotaKnown = true)), "connected", 1000))
    }

    @Test fun explicitDeviceDenialBlocksCachedTunnelWithoutDiscardingItsProfile() {
        assertEquals("This device is not allowed to use the subscription",
            SubscriptionTunnelAccess.denial(bundle(SubscriptionMetadata(deviceDenied = true)), "connected", 1000))
        assertNull(SubscriptionTunnelAccess.denial(bundle(SubscriptionMetadata(deviceDenied = false)), "connected", 1000))
    }

    @Test fun unknownAccessIsNotInventedExpiryAndMissingEntryIsDistinct() {
        assertNull(SubscriptionTunnelAccess.denial(bundle(SubscriptionMetadata()), "connected", 1000))
        assertEquals("No active location", SubscriptionTunnelAccess.denial(bundle(SubscriptionMetadata()), "deleted", 1000))
    }
}
