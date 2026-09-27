package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LinkParserTest {
    @Test fun parsesVlessReality() {
        val link = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443" +
            "?security=reality&encryption=none&pbk=PUBKEY&sid=ab12&fp=chrome&sni=www.example.com&flow=xtls-rprx-vision&type=tcp#DE"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(s)
        assertEquals("11111111-1111-1111-1111-111111111111", s.uuid)
        assertEquals("1.2.3.4", s.host); assertEquals(443, s.port)
        assertEquals("www.example.com", s.sni)
        assertEquals("PUBKEY", s.publicKey); assertEquals("ab12", s.shortId)
        assertEquals("xtls-rprx-vision", s.flow)
        assertEquals(TransportSpec.Tcp, s.transport)
        assertEquals("DE", s.tag)
    }

    @Test fun parsesVlessXhttp() {
        val link = "vless://22222222-2222-2222-2222-222222222222@1.2.3.4:443" +
            "?type=xhttp&security=reality&encryption=none&pbk=PBK&sid=cd34&fp=chrome&sni=sni.example&path=%2Fdownload&host=sni.example&mode=packet-up#FI"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(s)
        val t = s.transport
        assertIs<TransportSpec.Xhttp>(t)
        assertEquals("/download", t.path)
        assertEquals("sni.example", t.host)
        assertEquals("packet-up", t.mode)
        assertNull(s.flow) // xhttp is incompatible with flow
    }

    @Test fun parsesVlessXhttpExtraWithoutLosingOptions() {
        val link = "vless://22222222-2222-2222-2222-222222222222@1.2.3.4:443" +
            "?type=xhttp&security=reality&pbk=PBK&sni=sni.example&path=%2Fdownload&mode=packet-up" +
            "&extra=%7B%22xPaddingBytes%22%3A%22400-600%22%2C%22headers%22%3A%7B%22X-Test%22%3A%221%22%7D%7D#FI"
        val parsed = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(parsed)
        val transport = assertIs<TransportSpec.Xhttp>(parsed.transport)
        assertEquals("400-600", transport.extra?.get("xPaddingBytes")?.jsonPrimitive?.content)
        assertEquals("1", transport.extra?.get("headers")?.jsonObject?.get("X-Test")?.jsonPrimitive?.content)
    }

    @Test fun rejectsMalformedOrNonObjectXhttpExtra() {
        val prefix = "vless://22222222-2222-2222-2222-222222222222@1.2.3.4:443?type=xhttp&path=%2Fdownload&extra="
        assertNull(LinkParser.parse(prefix + "%7Bnot-json%7D#FI"))
        assertNull(LinkParser.parse(prefix + "%5B1%5D#FI"))
    }

    @Test fun rejectsUnsafeXhttpExtraBeforeAHostCanBeImported() {
        val prefix = "vless://22222222-2222-2222-2222-222222222222@1.2.3.4:443?type=xhttp&path=%2Fdownload&extra="
        // The pinned Xray panics on packet-up when the upload chunk is <= 8192.
        assertNull(LinkParser.parse(prefix + "%7B%22scMaxEachPostBytes%22%3A8192%7D"))
        // Padding is allocated for every request, so a small JSON value can exhaust a mobile tunnel.
        assertNull(LinkParser.parse(prefix + "%7B%22xPaddingBytes%22%3A50000000%7D"))
        // This fork does not implement a second download transport within one link.
        assertNull(LinkParser.parse(prefix + "%7B%22downloadSettings%22%3A%7B%7D%7D"))
    }

    @Test fun parsesVlessGrpcAndDropsTcpOnlyFlow() {
        val link = "vless://33333333-3333-3333-3333-333333333333@grpc.example:2053" +
            "?type=grpc&serviceName=rutube&security=reality&pbk=PBK&flow=xtls-rprx-vision#KZ"
        val spec = LinkParser.parse(link)
        assertIs<OutboundSpec.Vless>(spec)
        assertEquals(TransportSpec.Grpc("rutube"), spec.transport)
        assertNull(spec.flow)
    }

    @Test fun emptyTransportDefaultsToTcpButUnknownIsRejected() {
        val prefix = "vless://44444444-4444-4444-4444-444444444444@host:443"
        val empty = LinkParser.parse("$prefix?type=&sni=host")
        assertIs<OutboundSpec.Vless>(empty)
        assertEquals(TransportSpec.Tcp, empty.transport)

        // An unsupported transport must not become a believable but broken TCP host.
        val unknown = "$prefix?type=kcp&sni=host"
        assertNull(LinkParser.parse(unknown))
    }

    @Test fun parsesHysteria2() {
        val link = "hysteria2://PASSWORD@1.2.3.4:443?sni=h.example&obfs=salamander&obfs-password=OBFS&insecure=1#RU"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals("PASSWORD", s.password)
        assertEquals("h.example", s.sni)
        assertEquals("OBFS", s.obfsPassword)
        assertEquals(true, s.insecure)
        assertEquals("RU", s.tag)
    }

    @Test fun hy2AliasAndNoObfs() {
        val s = LinkParser.parse("hy2://PW@host:8443?sni=x#T") as OutboundSpec.Hysteria2
        assertNull(s.obfsPassword)
        assertEquals(8443, s.port)
    }

    @Test fun rejectsOlcrtcAndGarbage() {
        assertNull(LinkParser.parse("olcrtc://telemost?vp8channel@room#key"))
        assertNull(LinkParser.parse("https://example.com/sub"))
        assertNull(LinkParser.parse("not a link"))
        assertNull(LinkParser.parse("vless://missing-host"))
    }

    /**
     * The exact name a partner subscription serves. Every character above
     * U+007F here is more than one byte, and decoding each `%XX` into its own
     * Char turned this into `ð\u009F‡· EKB Â· Hy2 â\u0086\u2019 ð\u009F\u008C`
     * on a real device — a whole subscription's worth of unreadable rows.
     */
    @Test fun decodesMultiByteTagsAsUtf8() {
        val link = "hysteria2://11111111-1111-1111-1111-111111111111@1.2.3.4:38445" +
            "?sni=example.org&obfs=salamander&obfs-password=pw" +
            "#%F0%9F%87%B7%F0%9F%87%BA%20EKB%20%C2%B7%20Hy2%20%E2%86%92%20%F0%9F%8C%90"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals("\uD83C\uDDF7\uD83C\uDDFA EKB \u00B7 Hy2 \u2192 \uD83C\uDF10", s.tag)
    }

    /** A run of escapes that is not valid UTF-8 must not lose the rest of the name. */
    @Test fun survivesInvalidPercentSequences() {
        val link = "hysteria2://11111111-1111-1111-1111-111111111111@1.2.3.4:38445" +
            "?sni=example.org#%FF%FE%20ok"
        val s = LinkParser.parse(link)
        assertIs<OutboundSpec.Hysteria2>(s)
        assertEquals(" ok", s.tag.takeLast(3))
    }
}
