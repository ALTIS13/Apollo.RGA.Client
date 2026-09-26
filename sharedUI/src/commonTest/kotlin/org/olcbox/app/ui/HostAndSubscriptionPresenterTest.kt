package org.olcbox.app.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.net.LocationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HostAndSubscriptionPresenterTest {
    @Test fun destinationFlagAndCountryLeadWhileLongNameAndPurposeRemainSeparate() {
        val longName = "Netherlands via RU | A long descriptive host name that should not swallow the purpose"
        val purpose = "For ordinary browsing on an unstable mobile connection"
        val host = LocationEntry.from(
            "exit-nl",
            LocationConfig(name = "🇳🇱 $longName", kind = LocationKind.Vless, xrayConfig = buildJsonObject { }),
            metadata = LocationMetadata(comment = purpose)
        )

        val row = HostPresenter.present(host)!!
        assertEquals("🇳🇱", row.flag)
        assertEquals("Netherlands", row.country)
        assertEquals(longName, row.name)
        assertEquals(purpose, row.purpose)
        assertEquals("через RU Relay", row.relay)
        assertEquals("Устройство → RU Relay → Netherlands", row.routeLine)
        assertEquals("VLESS", row.protocol)
    }

    @Test fun hostPresentationOmitsSecretsAndNeverPresentsOlcrtcAsOrdinary() {
        val secret = "https://example.invalid/sub/SYNTHETIC_TOKEN"
        val host = LocationEntry.from(
            "ordinary",
            LocationConfig(name = "🇳🇱 Netherlands $secret", kind = LocationKind.Vless, xrayConfig = buildJsonObject { }),
            metadata = LocationMetadata(comment = "Purpose $secret")
        )
        val rendered = HostPresenter.present(host).toString()
        assertFalse(secret in rendered)
        assertNull(HostPresenter.present(LocationEntry.from("room", LocationConfig(name = "Room", id = "r", key = "k"))))
        val bearer = "SYNTHETIC_BEARER_0123456789abcdefghijklmnop"
        val bareUrl = "example.com/private/path"
        val unsafe = host.copy(metadata = LocationMetadata(comment = "Purpose $bareUrl $bearer"))
        val unsafeRendered = HostPresenter.present(unsafe).toString()
        assertFalse(bareUrl in unsafeRendered)
        assertFalse(bearer in unsafeRendered)
    }

    @Test fun liveDestinationCodesAndLayeredProtocolRemainExplicit() {
        val layered = Json.parseToJsonElement(
            """{"outbounds":[{"streamSettings":{"network":"xhttp","security":"reality"}}]}"""
        ).jsonObject
        listOf("FI" to "Finland", "SG" to "Singapore", "BY" to "Belarus").forEach { (code, country) ->
            val host = LocationEntry.from(
                "exit-$code", LocationConfig(name = "$code via RU", kind = LocationKind.Vless, xrayConfig = layered)
            )
            val row = HostPresenter.present(host)!!
            assertEquals(country, row.country)
            assertEquals("VLESS · XHTTP · Reality", row.protocol)
        }
    }

    @Test fun typedSubscriptionStateKeepsExpiryLimitAndRefreshFailureDistinct() {
        val now = 10_000_000L
        val active = SubscriptionMetadata(
            expiresAtEpochMs = now + 5 * DAY,
            expiryKnown = true,
            usedBytes = 20,
            totalBytes = 100,
            quotaKnown = true
        )
        assertEquals(SubscriptionDisplayState.ACTIVE, SubscriptionPresenter.present(active, now).state)
        assertEquals(80L, SubscriptionPresenter.present(active, now).remainingBytes)
        assertEquals(SubscriptionDisplayState.EXPIRING,
            SubscriptionPresenter.present(active.copy(expiresAtEpochMs = now + DAY), now).state)
        assertEquals(SubscriptionDisplayState.EXPIRED,
            SubscriptionPresenter.present(active.copy(expiresAtEpochMs = now), now, SubscriptionRefreshError.Unreachable).state)
        assertEquals(SubscriptionDisplayState.LIMITED,
            SubscriptionPresenter.present(active.copy(usedBytes = 100), now, SubscriptionRefreshError.Unreachable).state)
        assertEquals(SubscriptionDisplayState.REFRESH_FAILED,
            SubscriptionPresenter.present(active, now, SubscriptionRefreshError.Unreachable).state)
        assertEquals(SubscriptionDisplayState.UNKNOWN,
            SubscriptionPresenter.present(active.copy(quotaKnown = false), now).state)
        assertEquals(SubscriptionDisplayState.UNKNOWN,
            SubscriptionPresenter.present(null, now).state)
        assertEquals(SubscriptionMetadata.AccessState.ALLOWED,
            SubscriptionPresenter.present(active, now, SubscriptionRefreshError.Unreachable).accessState)
        assertEquals(SubscriptionMetadata.AccessState.EXPIRED,
            SubscriptionPresenter.present(active.copy(expiresAtEpochMs = now), now).accessState)
    }

    private companion object { const val DAY = 86_400_000L }
}
