package org.olcbox.app.vpn

import org.junit.Assert.*
import org.junit.Test

class GeneralTransportDiagnosticsTest {
    @Test fun vkBoundaryRejectsUnknownOversizedAndAmbiguousMarkers() {
        val valid = "vkcalls: RGA_VK_STARTUP stage=AUTH_ANON class=DNS code=0"
        for (value in listOf(valid.replace("DNS", "PRIVATE_SECRET"), valid.replace("AUTH_ANON", "PRIVATE_SECRET"),
            valid.replace("code=0", "code=123456"), valid.replace("code=0", "code=-1"),
            valid.replace("code=0", "code=00"), "$valid $valid", "x".repeat(8193) + valid)) {
            val safe = GeneralNativeFailure.from(IllegalStateException(value))
            assertNull(safe.providerBoundary)
            assertNull(safe.cause)
            assertEquals(GENERAL_RUNTIME_DENIED, safe.message)
        }
        val safe = GeneralNativeFailure.from(IllegalStateException("run public client: $valid private-secret"))
        assertEquals("providerStage=AUTH_ANON providerClass=DNS providerCode=0", safe.providerBoundary!!.diagnostic())
        assertFalse(safe.toString().contains("private-secret"))
    }
    @Test fun nativeStartupCauseSurvivesRedactionWithoutRetainingRawFailure() {
        for ((message, expected) in listOf(
            "open engine session: preconnect: failed for https://private.invalid/secret" to "PROVIDER_BOOTSTRAP",
            "salutejazz signaling websocket closed token=secret" to "PROVIDER_SIGNAL",
            "provider does not support video tracks secret" to "CAPABILITY",
            "lookup private.invalid: no such host secret" to "DNS",
            "dial tcp private.invalid: i/o timeout secret" to "TIMEOUT",
            "invalid mobile runtime configuration secret" to "CONFIGURATION",
            "context canceled secret" to "CANCELLED",
            "unrecognized controlled-secret" to "OTHER"
        )) {
            val safe = GeneralNativeFailure.from(IllegalStateException(message))
            assertEquals(expected, safe.code.name)
            assertEquals(GENERAL_RUNTIME_DENIED, safe.message)
            assertNull(safe.cause)
            assertFalse(safe.toString().contains("secret"))
            assertFalse(safe.toString().contains("private.invalid"))
        }
        assertEquals("OTHER", GeneralNativeFailure.from(IllegalStateException("x".repeat(8193))).code.name)
    }
    // Removing/merging the real CONNECT stages hides whether the peer denied or the carrier failed.
    @Test fun distinguishesOpenWriteReadAndPeerAckWithoutExposingPayload() {
        val examples = listOf(
            "OpenStream failed: controlled-secret" to "OPEN_STREAM_FAILED",
            "sid=13 connect failed: sid=13 write connect req: controlled-secret" to "CONNECT_WRITE_FAILED",
            "sid=15 connect failed: sid=15: remote not ready (read_err=controlled-secret)" to "CONNECT_ACK_READ_FAILED",
            "sid=17 connect failed: sid=17: remote not ready (connect ack=0x04)" to "CONNECT_ACK_REJECTED"
        )
        for ((input, expected) in examples) {
            val actual = GeneralTransportDiagnostics.native("2026/10/08 02:14:00 " + input).name
            assertEquals(expected, actual)
            assertFalse(actual.contains("controlled-secret"))
            assertFalse(actual.contains("sid="))
        }
    }
    @Test fun arbitraryAndOversizedLogsRemainOpaque() {
        for (input in listOf("private.invalid bearer=controlled-secret", "connect failed:", "read_err=controlled-secret", "x".repeat(4097) + "OpenStream failed:"))
            assertEquals("OTHER", GeneralTransportDiagnostics.native(input).name)
    }
    @Test fun ackReadWitnessAcceptsOnlyTheExactNativeGrammar() {
        for (kind in GeneralTransportDiagnostics.AckReadClass.entries) {
            for (expired in listOf(false, true)) {
                for (prefix in listOf("", "2026/10/08 07:30:00 ")) {
                    for (ending in listOf("", "\n", "\r\n")) {
                        val actual = GeneralTransportDiagnostics.ackRead("${prefix}RGA_TASK27_ACK_READ class=${kind.name} ownEndExpired=$expired$ending")!!
                        assertEquals(kind, actual.classification)
                        assertEquals(expired, actual.ownEndExpired)
                    }
                }
            }
        }
        for (invalid in listOf(
            "RGA_TASK27_ACK_READ class=EOF ownEndExpired=FALSE",
            "RGA_TASK27_ACK_READ class=EOF ownEndExpired=false target=private.invalid",
            "controlled-secret RGA_TASK27_ACK_READ class=EOF ownEndExpired=false",
            "RGA_TASK27_ACK_READ class=controlled-secret ownEndExpired=true",
            "RGA_TASK27_ACK_READ class=EOF ownEndExpired=false\nprivate.invalid",
            "RGA_TASK27_ACK_READ class=EOF ownEndExpired=false\n\n",
            "x".repeat(161),
            "sid=1 connect failed: read_err=EOF"
        )) assertNull(GeneralTransportDiagnostics.ackRead(invalid))
    }
    @Test fun lifecycleProjectionRejectsUnknownMultilineAndOversizedLogs() {
        for (invalid in listOf(
            "control stream ended role=server session=privateSession: control: EOF",
            "private-prefix control stream ended role=client session=privateSession: control: EOF",
            "control stream ended role=client session=privateSession: control: EOF\nprivate.invalid",
            "control stream ended role=client session=privateSession: control: EOF\n\n",
            "client reconnect reason=privateReason - tearing down smux session",
            "Client link reported conference end: " + "x".repeat(513),
            "control missed pong role=client session=privateSession missed=privateValue",
            "control healthy role=client session=privateSession but the data plane has stopped sending — tearing the session down\rprivate"
        )) assertEquals("OTHER", GeneralTransportDiagnostics.native(invalid).name)
        for (ending in listOf("", "\n", "\r\n"))
            assertEquals("CONTROL_EOF", GeneralTransportDiagnostics.native("control stream ended role=client session=<id>: control: EOF$ending").name)
    }

    // Conflating SOCKS success with rejection would make transport evidence misleading.
    @Test fun classifiesSocksRejectionFromProtocolBytesOnly() {
        for ((rep, expected) in listOf(0 to "SUCCESS", 3 to "NETWORK_UNREACHABLE", 4 to "HOST_UNREACHABLE", 5 to "REFUSED", 9 to "OTHER_REJECTION"))
            assertEquals(expected, GeneralTransportDiagnostics.socksReply(byteArrayOf(5, rep.toByte(), 0, 1)).name)
    }
    @Test fun malformedHeadersNeverClassifyAsSuccess() {
        for (reply in listOf(byteArrayOf(), byteArrayOf(5, 0), byteArrayOf(4, 0, 0, 1), byteArrayOf(5, 0, 1, 1)))
            assertEquals("INVALID", GeneralTransportDiagnostics.socksReply(reply).name)
    }
}
