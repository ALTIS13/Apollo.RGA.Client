package org.olcbox.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XrayXhttpExtraTest {
    private fun spec(extra: String): OutboundSpec.Vless = OutboundSpec.Vless(
        uuid = "11111111-1111-1111-1111-111111111111",
        host = "1.2.3.4",
        port = 443,
        sni = "sni.example",
        publicKey = "PUBLIC_KEY",
        shortId = "ab12",
        fingerprint = "chrome",
        flow = null,
        transport = TransportSpec.Xhttp(
            path = "/download",
            host = "sni.example",
            mode = "packet-up",
            extra = Json.parseToJsonElement(extra).jsonObject,
        ),
        tag = "Synthetic",
    )

    private fun xhttpSettings(spec: OutboundSpec.Vless): JsonObject =
        Json.parseToJsonElement(XrayConfig.buildXhttp(spec)).jsonObject
            .getValue("outbounds").jsonArray[0].jsonObject
            .getValue("streamSettings").jsonObject
            .getValue("xhttpSettings").jsonObject

    @Test fun passesNestedExtraToXrayWithoutDroppingOptions() {
        val settings = xhttpSettings(spec("""{"xPaddingBytes":"400-600","headers":{"X-Test":"1"}}"""))
        assertEquals("/download", settings.getValue("path").jsonPrimitive.content)
        assertEquals("sni.example", settings.getValue("host").jsonPrimitive.content)
        assertEquals("packet-up", settings.getValue("mode").jsonPrimitive.content)
        val extra = settings.getValue("extra").jsonObject
        assertEquals("400-600", extra.getValue("xPaddingBytes").jsonPrimitive.content)
        assertEquals("1", extra.getValue("headers").jsonObject.getValue("X-Test").jsonPrimitive.content)
        assertEquals("200000", extra.getValue("scMaxEachPostBytes").jsonPrimitive.content)
        assertNull(settings["scMaxEachPostBytes"], "the cap must live inside extra, which Xray uses in preference to top-level fields")
    }

    @Test fun keepsProviderUploadBufferWhenItIsWithinMobileLimit() {
        val extra = xhttpSettings(spec("""{"scMaxEachPostBytes":"100000-150000"}"""))
            .getValue("extra").jsonObject
        assertEquals("100000-150000", extra.getValue("scMaxEachPostBytes").jsonPrimitive.content)
    }

    @Test fun rejectsUploadBufferAboveMobileLimitOrInvalid() {
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildXhttp(spec("""{"scMaxEachPostBytes":"100000-250000"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildXhttp(spec("""{"scMaxEachPostBytes":"unknown"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildXhttp(spec("""{"scMaxEachPostBytes":8192}"""))
        }
    }

    @Test fun rejectsOversizedPaddingAndUnsupportedNestedTransport() {
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildXhttp(spec("""{"xPaddingBytes":50000000}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            XrayConfig.buildXhttp(spec("""{"downloadSettings":{}}"""))
        }
    }
}
