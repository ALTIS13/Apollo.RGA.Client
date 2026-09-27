package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.olcbox.app.data.datasource.XrayJsonSubscriptionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactXrayChainPlanTest {
    private val login = SocksLogin("local-user", "local-secret")
    private val regional = Routing.Rules("/data/rules", DirectDns.Servers(listOf("192.0.2.53")), "ru")

    private fun profile(body: String = XrayJsonSubscriptionFixtures.vless) =
        Json.parseToJsonElement(body).jsonObject

    @Test fun globalKeepsOneExactXrayCoreAndProviderGraph() {
        for (body in listOf(XrayJsonSubscriptionFixtures.vless, XrayJsonSubscriptionFixtures.hysteria)) {
            val stored = profile(body)
            val built = ExactXrayChainPlan.build(
                stored, ingressPort = 32001, backendPort = null,
                login = login, routing = Routing.Global, tunMode = true
            )
            assertNull(built.frontConfig)
            assertNull(built.backendPort)
            assertEquals(32001, built.ingressPort)
            assertEquals(32001, built.xrayConfig["inbounds"]!!.jsonArray.single().jsonObject["port"]!!.jsonPrimitive.content.toInt())
            for (key in listOf("dns", "routing", "outbounds")) assertEquals(stored[key], built.xrayConfig[key])
        }
    }

    @Test fun regionalModeFrontsExactXrayAndKeepsProviderRules() {
        val stored = profile()
        val built = ExactXrayChainPlan.build(
            stored, ingressPort = 32001, backendPort = 32002,
            login = login, routing = regional, tunMode = true
        )
        assertEquals(32001, built.ingressPort)
        assertEquals(32002, built.backendPort)
        assertEquals(32002, built.xrayConfig["inbounds"]!!.jsonArray.single().jsonObject["port"]!!.jsonPrimitive.content.toInt())
        for (key in listOf("dns", "routing", "outbounds")) assertEquals(stored[key], built.xrayConfig[key])
        val front = Json.parseToJsonElement(assertNotNull(built.frontConfig)).jsonObject
        val out = front["outbounds"]!!.jsonArray.first().jsonObject
        assertEquals("socks", out["type"]!!.jsonPrimitive.content)
        assertEquals(32002, out["server_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("local-user", out["username"]!!.jsonPrimitive.content)
        assertEquals("local-secret", out["password"]!!.jsonPrimitive.content)
        val route = front["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray.map { it.jsonObject }
        val fixed = rules.indexOfFirst { it["domain_suffix"]?.jsonArray?.map { suffix -> suffix.jsonPrimitive.content } == listOf("cc", "at") }
        val region = rules.indexOfFirst { it["rule_set"] != null && it["outbound"]?.jsonPrimitive?.content == "direct" }
        assertTrue(fixed >= 0 && region > fixed)
        assertEquals("out", rules[fixed]["outbound"]!!.jsonPrimitive.content)
        val dnsRules = front["dns"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("cc", "at"), dnsRules.first()["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("dns-remote", dnsRules.first()["server"]!!.jsonPrimitive.content)
        assertEquals("out", route["final"]!!.jsonPrimitive.content)
        assertFalse(built.toString().contains("SYNTHETIC_PUBLIC_KEY"))
        assertFalse(built.toString().contains("local-secret"))
    }

    @Test fun missingOrCollidingBackendPortRejectsInsteadOfFallingBack() {
        for (badPort in listOf(null, 32001)) {
            val error = assertFailsWith<IllegalArgumentException> {
                ExactXrayChainPlan.build(
                    profile(), ingressPort = 32001, backendPort = badPort,
                    login = login, routing = regional, tunMode = true
                )
            }
            assertFalse(error.toString().contains("SYNTHETIC_PUBLIC_KEY"))
            assertFalse(error.toString().contains("local-secret"))
        }
    }

    @Test fun providerDirectDomainRuleCannotUndoMandatoryProxyRouting() {
        val stored = profile()
        val unsafeRouting = Json.parseToJsonElement("""{"rules":[
            {"type":"field","domain":["domain:vk.cc"],"outboundTag":"direct"}
        ]}""")
        val changed = JsonObject(stored + ("routing" to unsafeRouting))
        val failure = assertFailsWith<ExactXrayChainPlan.UnsupportedProviderRouting> {
            ExactXrayChainPlan.build(
                changed, ingressPort = 32001, backendPort = 32002,
                login = login, routing = regional, tunMode = true
            )
        }
        assertFalse(failure.toString().contains("SYNTHETIC_PUBLIC_KEY"))
        assertFalse(failure.toString().contains("local-secret"))
    }
}
