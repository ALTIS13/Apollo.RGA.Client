package org.olcbox.app.net

import kotlinx.serialization.json.*
import org.olcbox.app.data.datasource.XrayJsonSubscriptionFixtures
import kotlin.test.*

class XrayJsonRuntimeConfigTest {
    private val login = SocksLogin("local-user", "local-secret")
    private fun config(body: String = XrayJsonSubscriptionFixtures.vless) = Json.parseToJsonElement(body).jsonObject

    @Test fun runtimeOverridesOnlyLoggingWithoutMutatingPersistedProviderConfig() {
        for (providerLog in listOf(
            """{"loglevel":"warning","access":"../SYNTHETIC_SECRET_PATH"}""",
            """{"loglevel":"debug","error":"/private/SYNTHETIC_SECRET_PATH","dnsLog":true}""",
            """{"loglevel":"warning"}""",
            """{"unknownLoggingExtension":"SYNTHETIC_SECRET_PATH"}""",
            "[]", "null"
        )) {
            val log = Json.parseToJsonElement(providerLog)
            val stored = JsonObject(config() + ("log" to log))
            val adapted = XrayJsonRuntimeConfig.adapt(stored, 32001, login).config
            assertEquals(Json.parseToJsonElement("""{"access":"none","error":"none","loglevel":"none","dnsLog":false}"""), adapted["log"])
            assertEquals(log, stored["log"])
            assertEquals(stored.filterKeys { it !in setOf("log", "inbounds") }, adapted.filterKeys { it !in setOf("log", "inbounds") })
            assertFalse(adapted.toString().contains("SYNTHETIC_SECRET_PATH"))
        }
    }

    @Test fun preservesExactProfileAndAuthenticatesOnlyTunInbound() {
        for (body in listOf(XrayJsonSubscriptionFixtures.vless, XrayJsonSubscriptionFixtures.hysteria)) {
            val input = config(body)
            val result = XrayJsonRuntimeConfig.adapt(input, 32001, login).config
            assertEquals(input.filterKeys { it !in setOf("inbounds", "log") }, result.filterKeys { it !in setOf("inbounds", "log") })
            assertEquals(Json.parseToJsonElement("""{"access":"none","error":"none","loglevel":"none","dnsLog":false}"""), result["log"])
            val inbound = result.getValue("inbounds").jsonArray.single().jsonObject
            assertEquals("local-socks", inbound.getValue("tag").jsonPrimitive.content)
            assertEquals("127.0.0.1", inbound.getValue("listen").jsonPrimitive.content)
            assertEquals(32001, inbound.getValue("port").jsonPrimitive.int)
            assertEquals(input.getValue("inbounds").jsonArray[0].jsonObject["sniffing"], inbound["sniffing"])
            assertEquals(Json.parseToJsonElement("""{"udp":true,"auth":"password","accounts":[{"user":"local-user","pass":"local-secret"}]}"""), inbound["settings"])
        }
    }

    @Test fun acceptedFutureExtraAndPinValuesAreNotRebuilt() {
        for (body in listOf(
            XrayJsonSubscriptionFixtures.vless.replace("\"maxConcurrency\":8", "\"maxConcurrency\":13,\"maxConnections\":4"),
            XrayJsonSubscriptionFixtures.hysteria.replace("SYNTHETIC_PIN_NOT_REAL", "CHANGED_ACCEPTED_PIN")
        )) {
            val input = config(body)
            assertEquals(input["outbounds"], XrayJsonRuntimeConfig.adapt(input, 32001, login).config["outbounds"])
        }
    }

    @Test fun omittedBlackholeSettingsSurvivesRuntimeAdaptationForBothTunnelProtocols() {
        for (body in listOf(XrayJsonSubscriptionFixtures.vless, XrayJsonSubscriptionFixtures.hysteria)) {
            val original = config(body)
            val outbounds = original.getValue("outbounds").jsonArray.toMutableList()
            outbounds[2] = JsonObject(outbounds[2].jsonObject - "settings")
            val stored = JsonObject(original + ("outbounds" to JsonArray(outbounds)))

            val adapted = XrayJsonRuntimeConfig.adapt(stored, 32001, login).config
            assertEquals(stored.getValue("outbounds"), adapted.getValue("outbounds"))
            assertFalse("settings" in adapted.getValue("outbounds").jsonArray[2].jsonObject)
            assertFalse("settings" in stored.getValue("outbounds").jsonArray[2].jsonObject)
        }
    }

    @Test fun rejectsReferencedHttpInsteadOfChangingRuleMeaning() {
        val body = XrayJsonSubscriptionFixtures.vless.replace("\"inboundTag\":[\"local-socks\"]", "\"inboundTag\":[\"local-http\"]")
        val error = assertFailsWith<XrayJsonRuntimeConfig.Rejected> { XrayJsonRuntimeConfig.adapt(config(body), 32001, login) }
        assertEquals(XrayJsonRuntimeConfig.Reason.REFERENCED_HTTP_INBOUND, error.reason)
    }

    @Test fun sniffedHttpProtocolNameIsNotAReferenceToRemovedHttpInbound() {
        for (body in listOf(XrayJsonSubscriptionFixtures.vless, XrayJsonSubscriptionFixtures.hysteria)) {
            val input = config(body.replace("\"tag\":\"local-http\"", "\"tag\":\"http\""))
            val adapted = XrayJsonRuntimeConfig.adapt(input, 32001, login).config
            val originalSocks = input.getValue("inbounds").jsonArray[0].jsonObject
            val runtimeSocks = adapted.getValue("inbounds").jsonArray.single().jsonObject
            assertEquals(originalSocks.getValue("sniffing"), runtimeSocks.getValue("sniffing"))
            assertEquals("http", input.getValue("inbounds").jsonArray[1].jsonObject.getValue("tag").jsonPrimitive.content)
            assertEquals("local-socks", runtimeSocks.getValue("tag").jsonPrimitive.content)
            assertEquals(input.getValue("outbounds"), adapted.getValue("outbounds"))
        }
    }

    @Test fun opaqueRetainedValueMatchingRemovedHttpTagStillRejects() {
        val body = XrayJsonSubscriptionFixtures.vless
            .replace("\"tag\":\"local-http\"", "\"tag\":\"http\"")
            .replace("\"maxConcurrency\":8", "\"maxConcurrency\":8,\"opaqueInboundTag\":\"http\"")
        val error = assertFailsWith<XrayJsonRuntimeConfig.Rejected> {
            XrayJsonRuntimeConfig.adapt(config(body), 32001, login)
        }
        assertEquals(XrayJsonRuntimeConfig.Reason.REFERENCED_HTTP_INBOUND, error.reason)
    }

    @Test fun onlySniffingDestOverrideIsExcludedFromReferenceScan() {
        val body = XrayJsonSubscriptionFixtures.vless
            .replace("\"tag\":\"local-http\"", "\"tag\":\"http\"")
            .replace("\"destOverride\":[\"http\",\"tls\",\"quic\"]", "\"destOverride\":[\"http\",\"tls\",\"quic\"],\"domainsExcluded\":[\"http\"]")
        val error = assertFailsWith<XrayJsonRuntimeConfig.Rejected> {
            XrayJsonRuntimeConfig.adapt(config(body), 32001, login)
        }
        assertEquals(XrayJsonRuntimeConfig.Reason.REFERENCED_HTTP_INBOUND, error.reason)
    }

    @Test fun revalidatesTamperedStoredConfigAndMalformedInbounds() {
        for ((old, replacement) in listOf(
            "\"outboundTag\":\"proxy\"" to "\"outboundTag\":\"missing\"",
            "\"mode\":\"auto\"" to "\"mode\":\"unsupported\"",
            "\"udp\":true" to "\"udp\":\"true\"",
            "\"auth\":\"noauth\"" to "\"auth\":\"unsupported\"",
            "\"listen\":\"127.0.0.1\"" to "\"listen\":\"0.0.0.0\"",
            "\"settings\":{\"udp\"" to "\"streamSettings\":{\"network\":\"tcp\"},\"settings\":{\"udp\""
        )) {
            val body = XrayJsonSubscriptionFixtures.vless.replace(old, replacement)
            assertNotEquals(body, XrayJsonSubscriptionFixtures.vless)
            assertFailsWith<XrayJsonRuntimeConfig.Rejected> { XrayJsonRuntimeConfig.adapt(config(body), 32001, login) }
        }
        val badHy2 = XrayJsonSubscriptionFixtures.hysteria.replace("\"version\":2", "\"version\":3")
        assertFailsWith<XrayJsonRuntimeConfig.Rejected> { XrayJsonRuntimeConfig.adapt(config(badHy2), 32001, login) }
    }

    @Test fun rejectsBlankLoginInvalidPortAndProxyModeWithoutSecrets() {
        for (credentials in listOf(null, SocksLogin("", "secret"), SocksLogin("user", " "), SocksLogin("user\nname", "secret"))) {
            val error = assertFailsWith<XrayJsonRuntimeConfig.Rejected> { XrayJsonRuntimeConfig.adapt(config(), 32001, credentials) }
            assertFalse(error.toString().contains("secret"))
        }
        assertFailsWith<XrayJsonRuntimeConfig.Rejected> { XrayJsonRuntimeConfig.adapt(config(), 0, login) }
        assertFailsWith<XrayJsonRuntimeConfig.Rejected> { XrayJsonRuntimeConfig.adapt(config(), 32001, login, tunMode = false) }
        val error = assertFailsWith<XrayJsonRuntimeConfig.Rejected> {
            XrayJsonRuntimeConfig.adapt(config(XrayJsonSubscriptionFixtures.vless.replace("\"mode\":\"auto\"", "\"mode\":\"SECRET_URL_TOKEN\"")), 32001, login)
        }
        assertFalse(error.toString().contains("SECRET_URL_TOKEN"))
        assertFalse(XrayJsonRuntimeConfig.adapt(config(), 32001, login).toString().contains("local-secret"))
    }
}
