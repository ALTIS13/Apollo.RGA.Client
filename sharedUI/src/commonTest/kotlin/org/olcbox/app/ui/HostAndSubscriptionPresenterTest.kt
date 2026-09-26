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
    @Test fun liveDestinationNameFollowedByItsFlagIsPresentedAsTheExitCountry() {
        listOf(
            "Netherlands 🇳🇱 · 🚀 RU Relay · VLESS" to ("🇳🇱" to "Netherlands"),
            "Finland 🇫🇮 · 🎮 RU Relay · Hysteria 2" to ("🇫🇮" to "Finland"),
            "Germany 🇩🇪 · 🚀 RU Relay · VLESS" to ("🇩🇪" to "Germany"),
            "Singapore 🇸🇬 · Layer · 🎮 RU Relay · Hysteria 2" to ("🇸🇬" to "Singapore")
        ).forEach { (name, expected) ->
            val host = LocationEntry.from(
                "host-${expected.second}", LocationConfig(name = name, kind = LocationKind.Vless)
            )
            val row = HostPresenter.present(host)!!
            assertEquals(expected.first, row.flag, name)
            assertEquals(expected.second, row.country, name)
            assertEquals(name, row.name, name)
        }
    }

    @Test fun conflictingOrTransitFlagIsNotMistakenForTheExitCountry() {
        listOf(
            "Netherlands 🇫🇮 · RU Relay · VLESS",
            "Transit through Russia 🇷🇺 · Netherlands 🇳🇱",
            "Unknown 🇳🇱 · RU Relay · VLESS"
        ).forEach { name ->
            val host = LocationEntry.from("host", LocationConfig(name = name, kind = LocationKind.Vless))
            val row = HostPresenter.present(host)!!
            assertEquals("Страна не указана", row.country, name)
            assertEquals("", row.flag, name)
        }
    }

    @Test fun destinationFlagAndCountryLeadWhileLongNameAndPurposeRemainSeparate() {
        val longName = "Netherlands via RU | A long descriptive host name that should not swallow the purpose"
        val purpose = "For ordinary browsing on an unstable mobile connection"
        val host = LocationEntry.from(
            "exit-nl",
            LocationConfig(name = "🇳🇱 $longName", kind = LocationKind.Vless, xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"direct.example.net"}]}}]}"""
            ).jsonObject),
            metadata = LocationMetadata(comment = purpose)
        )

        val row = HostPresenter.present(host)!!
        assertEquals("🇳🇱", row.flag)
        assertEquals("Netherlands", row.country)
        assertEquals(longName, row.name)
        assertEquals(purpose, row.purpose)
        assertNull(row.relay)
        assertEquals("Маршрут не подтверждён", row.routeLine)
        assertEquals("VLESS", row.protocol)
    }

    @Test fun directWordInNameIsNotRouteEvidence() {
        val host = LocationEntry.from("exit-nl", LocationConfig(
            name = "🇳🇱 NL Direct", kind = LocationKind.Vless,
            xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"direct.example.net"}]}}]}"""
            ).jsonObject
        ))
        val row = HostPresenter.present(host)!!
        assertNull(row.relay)
        assertEquals("Маршрут не подтверждён", row.routeLine)
    }

    @Test fun exactConfiguredOwnedRelayEndpointCanBeLabeledWithoutClaimingPhysicalEgress() {
        val host = LocationEntry.from("exit-nl", LocationConfig(
            name = "🇳🇱 Netherlands", kind = LocationKind.Vless,
            xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"rw-nl-relay.gate-altas.tech","port":443}]}}]}"""
            ).jsonObject
        ))
        val row = HostPresenter.present(host)!!
        assertEquals("через RU Relay", row.relay)
        assertEquals("Настроенный вход: RU Relay · назначение Netherlands", row.routeLine)
    }

    @Test fun multiServerVlessStaysNeutralRegardlessOfAlternateOwnership() {
        listOf("direct.example.net", "rw-de-relay.gate-altas.tech").forEach { alternate ->
            val host = LocationEntry.from("exit-nl", LocationConfig(
                name = "🇳🇱 Netherlands via RU", kind = LocationKind.Vless,
                xrayConfig = Json.parseToJsonElement(
                    """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"rw-nl-relay.gate-altas.tech","port":443,"users":[{"id":"00000000-0000-0000-0000-000000000000","encryption":"none"}]},{"address":"$alternate","port":443,"users":[{"id":"00000000-0000-0000-0000-000000000000","encryption":"none"}]}]}}]}"""
                ).jsonObject
            ))

            val row = HostPresenter.present(host)!!
            assertNull(row.relay, "multiple configured endpoints cannot substantiate a single ingress: $alternate")
            assertEquals("Маршрут не подтверждён", row.routeLine)
        }
    }

    @Test fun hysteriaV2OwnedIngressUsesItsActualXrayProtocolShape() {
        val host = LocationEntry.from("exit-fi", LocationConfig(
            name = "🇫🇮 Finland", kind = LocationKind.Hysteria2,
            xrayConfig = Json.parseToJsonElement(
                """{"outbounds":[{"protocol":"hysteria","settings":{"address":"rw-fin-relay.gate-altas.tech","port":443,"version":2}}]}"""
            ).jsonObject
        ))
        val row = HostPresenter.present(host)!!
        assertEquals("через RU Relay", row.relay)
        assertEquals("Настроенный вход: RU Relay · назначение Finland", row.routeLine)
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

    @Test fun shortWhitespaceDelimitedCredentialLabelsNeverReachNameOrPurpose() {
        val shortValue = "ABC1234567890123"
        val host = LocationEntry.from("ordinary", LocationConfig(
            name = "🇳🇱 token $shortValue", kind = LocationKind.Vless, xrayConfig = buildJsonObject { }
        ), metadata = LocationMetadata(comment = "password $shortValue"))

        val row = HostPresenter.present(host)!!
        assertFalse(shortValue in row.toString())
        assertNull(row.purpose)
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
        assertEquals(SubscriptionDisplayState.DEVICE_DENIED,
            SubscriptionPresenter.present(active.copy(deviceDenied = true), now, SubscriptionRefreshError.DeviceDenied).state)
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

    @Test fun knownImminentExpiryRemainsVisibleWithoutUsageHeaders() {
        val now = 10_000_000L
        val expiryOnly = SubscriptionMetadata(expiresAtEpochMs = now + DAY, expiryKnown = true, quotaKnown = false)

        val presented = SubscriptionPresenter.present(expiryOnly, now)
        assertEquals(SubscriptionMetadata.AccessState.UNKNOWN, presented.accessState)
        assertEquals(SubscriptionDisplayState.EXPIRING, presented.state)
    }

    @Test fun zeroTotalQuotaIsUnmeteredRatherThanZeroRemaining() {
        val now = 10_000_000L
        val unmetered = SubscriptionMetadata(
            expiresAtEpochMs = now + 5 * DAY, expiryKnown = true,
            usedBytes = 10, totalBytes = 0, quotaKnown = true
        )

        val presented = SubscriptionPresenter.present(unmetered, now)
        assertEquals(SubscriptionDisplayState.ACTIVE, presented.state)
        assertNull(presented.remainingBytes)
    }

    private companion object { const val DAY = 86_400_000L }
}
