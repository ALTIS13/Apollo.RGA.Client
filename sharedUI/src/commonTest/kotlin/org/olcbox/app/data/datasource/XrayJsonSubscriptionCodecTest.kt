package org.olcbox.app.data.datasource

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class XrayJsonSubscriptionCodecTest {
    private val fixture = XrayJsonSubscriptionFixtures
    private fun reject(body: String) = assertIs<XrayJsonSubscriptionCodec.Rejected>(XrayJsonSubscriptionCodec.decode(body))
    private fun String.mutate(from: String, to: String): String {
        assertTrue(contains(from), "Fixture mutation target must exist")
        assertNotEquals(from, to, "Fixture mutation must change the input")
        return replace(from, to)
    }
    private fun withoutOutboundSettings(body: String, index: Int): String {
        val original = Json.parseToJsonElement(body).jsonObject
        val outbounds = original.getValue("outbounds").jsonArray.toMutableList()
        outbounds[index] = JsonObject(outbounds[index].jsonObject - "settings")
        return JsonObject(original + ("outbounds" to JsonArray(outbounds))).toString()
    }

    @Test fun rejectsUnvalidatedHysteriaUp() = rejectsHysteriaExtension("up")
    @Test fun rejectsUnvalidatedHysteriaDown() = rejectsHysteriaExtension("down")
    @Test fun rejectsUnvalidatedHysteriaUdpHop() = rejectsHysteriaExtension("udphop")
    @Test fun rejectsUnvalidatedHysteriaCongestion() = rejectsHysteriaExtension("congestion")
    private fun rejectsHysteriaExtension(key: String) {
        reject("[${fixture.hysteria.mutate("\"auth\":\"SYNTHETIC_AUTH_NOT_REAL\"", "\"auth\":\"SYNTHETIC_AUTH_NOT_REAL\",\"$key\":[]")}]")
    }

    @Test fun rejectsMistypedTlsFingerprint() {
        reject("[${fixture.hysteria.mutate("\"fingerprint\":\"chrome\"", "\"fingerprint\":[]")}]")
    }

    @Test fun rejectsMistypedDnsTimeout() {
        val config = fixture.vless.mutate("\"192.0.2.53\"", """{"address":"192.0.2.53","timeoutMs":[]} """.trim())
        assertEquals(XrayJsonSubscriptionCodec.Reason.INVALID_DNS, reject("[$config]").reason)
    }

    @Test fun rejectsMistypedRuleDomainMatcher() {
        val config = fixture.vless.mutate("\"outboundTag\":\"direct\"", "\"outboundTag\":\"direct\",\"domainMatcher\":[]")
        assertEquals(XrayJsonSubscriptionCodec.Reason.INVALID_ROUTING, reject("[$config]").reason)
    }

    @Test fun rejectsUnknownFreedomSettings() {
        reject("[${fixture.vless.mutate("\"domainStrategy\":\"UseIP\"", "\"requiredFutureField\":true")}]")
    }

    @Test fun rejectsMistypedFreedomDomainStrategy() {
        reject("[${fixture.vless.mutate("\"domainStrategy\":\"UseIP\"", "\"domainStrategy\":[]")}]")
    }

    @Test fun rejectsUnknownBlackholeSettings() {
        reject("[${fixture.vless.mutate("\"protocol\":\"blackhole\",\"settings\":{}", "\"protocol\":\"blackhole\",\"settings\":{\"requiredFutureField\":true}")}]")
    }

    @Test fun omittedBlackholeSettingsAdmitsBothTunnelProtocolsWithoutSynthesizingAField() {
        for ((body, protocol) in listOf(
            fixture.vless to XrayJsonSubscriptionCodec.Protocol.VLESS,
            fixture.hysteria to XrayJsonSubscriptionCodec.Protocol.HYSTERIA2
        )) {
            val original = body.mutate("\"protocol\":\"blackhole\",\"settings\":{}", "\"protocol\":\"blackhole\"")
            val result = assertIs<XrayJsonSubscriptionCodec.Success>(XrayJsonSubscriptionCodec.decode("[$original]"))
            assertEquals(protocol, result.profiles.single().protocol)
            assertEquals(Json.parseToJsonElement(original).jsonObject, result.profiles.single().effectiveConfig)
            assertFalse("settings" in result.profiles.single().effectiveConfig.getValue("outbounds").jsonArray[2].jsonObject)
        }
    }

    @Test fun absentSettingsIsOnlyAllowedForBlackhole() {
        for (body in listOf(fixture.vless, fixture.hysteria)) {
            for (invalid in listOf(
                body.mutate("\"protocol\":\"blackhole\",\"settings\":{}", "\"protocol\":\"blackhole\",\"settings\":null"),
                body.mutate("\"protocol\":\"blackhole\",\"settings\":{}", "\"protocol\":\"blackhole\",\"settings\":[]"),
                body.mutate("\"protocol\":\"blackhole\",\"settings\":{}", "\"protocol\":\"blackhole\",\"settings\":{\"unknown\":true}"),
                withoutOutboundSettings(body, 1),
                withoutOutboundSettings(body, 0)
            )) {
                assertEquals(XrayJsonSubscriptionCodec.Reason.INVALID_PROFILE, reject("[$invalid]").reason)
            }
        }
    }

    @Test fun preservesEntireConfigsAndOrderWithoutRebuildingDnsRoutingOrSecrets() {
        val result = assertIs<XrayJsonSubscriptionCodec.Success>(XrayJsonSubscriptionCodec.decode(fixture.body))
        assertEquals(listOf("Synthetic exit A", "Synthetic exit B"), result.profiles.map { it.displayName })
        assertEquals(listOf(XrayJsonSubscriptionCodec.Protocol.VLESS, XrayJsonSubscriptionCodec.Protocol.HYSTERIA2), result.profiles.map { it.protocol })
        val original = Json.parseToJsonElement(fixture.body).jsonArray
        assertEquals(original.map { it.jsonObject }, result.profiles.map { it.effectiveConfig })
        assertFalse(result.toString().contains("Synthetic"))
        assertFalse(result.profiles.toString().contains("SYNTHETIC_AUTH"))
    }

    @Test fun rejectsUnknownTransportInsteadOfReturningAWorkingLookingProfile() {
        assertEquals(XrayJsonSubscriptionCodec.Reason.UNSUPPORTED_TRANSPORT, reject("[${fixture.vless.replace("\"network\":\"xhttp\"", "\"network\":\"future-transport\"")}]").reason)
    }

    @Test fun rejectsWholeVersionWhenOneEntryIsInvalid() {
        val bad = fixture.hysteria.replace("\"auth\":\"SYNTHETIC_AUTH_NOT_REAL\"", "\"auth\":\"\"")
        val result = reject("[${fixture.vless},$bad]")
        assertEquals(1, result.profileIndex)
        assertFalse(result.toString().contains("SYNTHETIC"))
        assertFalse(result.toString().contains("exit-"))
    }

    @Test fun rejectsMissingAndMistypedRequiredProtocolFields() {
        for ((from, to) in listOf(
            "\"publicKey\":\"SYNTHETIC_PUBLIC_KEY_NOT_REAL\"" to "\"publicKey\":null",
            "\"id\":\"00000000-0000-4000-8000-000000000000\"" to "\"id\":17",
            "\"port\":443" to "\"port\":0",
            "\"encryption\":\"none\"" to "\"encryption\":\"unknown\"",
            "\"xhttpSettings\":" to "\"unknownSettings\":"
        )) reject("[${fixture.vless.replace(from,to)}]")
        reject("[${fixture.hysteria.replace("\"version\":2", "\"version\":1")}]")
        reject("[${fixture.hysteria.replace("\"security\":\"tls\"", "\"security\":\"none\"")}]")
    }

    @Test fun rejectsMalformedJsonAndNonArrayWithoutLeakingParserInput() {
        for (body in listOf("[{secret-token", fixture.vless, "[]", "[null]", "[17]", "{\"outbounds\":[]}")) {
            val result = reject(body)
            assertFalse(result.toString().contains("secret-token"))
            assertFalse(result.toString().contains("outbounds"))
        }
    }

    @Test fun validatesDnsRoutingAndTagRelationships() {
        for ((from, to) in listOf(
            "\"servers\":[\"192.0.2.53\",\"198.51.100.53\",\"localhost\"]" to "\"servers\":[]",
            "\"queryStrategy\":\"UseIPv4\"" to "\"queryStrategy\":\"future\"",
            "\"outboundTag\":\"direct\"" to "\"outboundTag\":\"missing\"",
            "\"inboundTag\":[\"local-socks\"]" to "\"inboundTag\":[\"missing\"]",
            "\"type\":\"field\"" to "\"type\":\"unknown\"",
            "\"protocol\":[\"bittorrent\"]" to "\"futureRule\":[\"bittorrent\"]",
            "\"tag\":\"block\"" to "\"tag\":\"direct\"",
            "\"protocol\":\"vless\"" to "\"protocol\":\"unknown\""
        )) reject("[${fixture.vless.replace(from,to)}]")
    }

    @Test fun rejectsUnknownRequiredFieldsAndMistypedOptionalControls() {
        for ((from, to) in listOf(
            "\"encryption\":\"none\"" to "\"encryption\":\"none\",\"requiredFutureField\":true",
            "\"publicKey\":\"SYNTHETIC_PUBLIC_KEY_NOT_REAL\"" to "\"publicKey\":\"SYNTHETIC_PUBLIC_KEY_NOT_REAL\",\"requiredFutureField\":true",
            "\"queryStrategy\":\"UseIPv4\"" to "\"queryStrategy\":\"UseIPv4\",\"disableCache\":\"yes\"",
            "\"protocol\":[\"bittorrent\"]" to "\"network\":\"invalid-network\""
        )) reject("[${fixture.vless.replace(from,to)}]")
        reject("[${fixture.hysteria.replace("\"enableSessionResumption\":true", "\"enableSessionResumption\":17")}]")
    }
}
