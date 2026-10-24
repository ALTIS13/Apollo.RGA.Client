package org.olcbox.app.net

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneralBootstrapV1Test {
    @Test fun mtsReserveIsSessionBoundManagedAndExplicitTcpOnly() {
        val locator = "https://my.mts-link.ru/j/123/456/session/789?ice=tcp"
        val mts = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"mtslink\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", locator)
        val parsed = GeneralBootstrapV1.fromPrivateBytes(mts.toByteArray())
        assertNotNull("MTS managed session rejected", parsed)
        assertEquals("vp8channel", parsed!!.transport)
        assertTrue(parsed.matchesGeneralChannel(org.olcbox.app.vpn.OlcChannelRole.Reserve))
        assertFalse(parsed.matchesGeneralChannel(org.olcbox.app.vpn.OlcChannelRole.Primary))
        for (bad in listOf(
            mts.replace("vp8channel", "datachannel"),
            mts.replace("/session/789", ""),
            mts.replace("/session/789", "/session/0"),
            mts.replace("?ice=tcp", ""),
            mts.replace("?ice=tcp", "?ice=udp"),
            mts.replace("?ice=tcp", "?ice=tcp&token=secret"),
            mts.replace("my.mts-link.ru", "my.mts-link.ru.evil.invalid"),
            mts.replace("my.mts-link.ru", "user:secret@my.mts-link.ru"),
            mts.replace("\"schemaVersion\":3,\"admissionMode\":\"managed-lease-v2\",", "\"schemaVersion\":2,")
        )) assertNull("MTS locator/schema substitution", GeneralBootstrapV1.fromPrivateBytes(bad.toByteArray()))
    }
    @Test fun vkReserveUsesOnlyAnExplicitManagedExactProviderTuple() {
        val vk = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"vkcalls\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", "https://vk.ru/call/join/fixture-opaque_123")
        val parsed = GeneralBootstrapV1.fromPrivateBytes(vk.toByteArray())
        assertNotNull("VK managed metadata rejected", parsed)
        assertTrue(parsed!!.usesManagedLease)
        assertEquals("vp8channel", parsed.transport)
        for (bad in listOf(
            vk.replace("vp8channel", "datachannel"),
            vk.replace("vk.ru/call", "vk.ru.evil.invalid/call"),
            vk.replace("fixture-opaque_123", "fixture?token=secret"),
            vk.replace("fixture-opaque_123", "fixture#secret"),
            vk.replace("vk.ru/call", "user:secret@vk.ru/call"),
            vk.replace("vk.ru/call", "vk.ru:443/call"),
            vk.replace("fixture-opaque_123", "%2e%2e"),
            vk.replace("\"schemaVersion\":3,\"admissionMode\":\"managed-lease-v2\",", "\"schemaVersion\":2,"),
            vk.replace("\"provider\":\"vkcalls\"", "\"provider\":\"telemost\"")
        )) assertNull("No arbitrary locator or legacy reinterpretation", GeneralBootstrapV1.fromPrivateBytes(bad.toByteArray()))
    }
    @Test fun reserveProviderIsAnExactManagedTupleNotAnArbitraryEndpoint() {
        val reserve = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"salutejazz\"")
            .replace("\"transport\":\"vp8channel\"", "\"transport\":\"datachannel\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", "abc123:abcd1234")
        val parsed = GeneralBootstrapV1.fromPrivateBytes(reserve.toByteArray())
        assertNotNull("Managed SaluteJazz discovery must parse without changing admission", parsed)
        assertTrue(parsed!!.usesManagedLease)
        assertEquals("salutejazz", parsed.provider)
        assertEquals("datachannel", parsed.transport)
        assertEquals(GeneralTestFixture.room, parsed.roomId)
        assertFalse(parsed.toString().contains("abcd1234"))
        for (bad in listOf(
            reserve.replace("salutejazz", "telemost"),
            reserve.replace("salutejazz", "unknown"),
            reserve.replace("datachannel", "vp8channel"),
            reserve.replace("datachannel", "webrtc"),
            managedMetadata().replace("vp8channel", "datachannel"),
            reserve.replace("abc123:abcd1234", "https://salutejazz.ru/abc123?psw=abcd1234"),
            reserve.replace("abc123:abcd1234", "abc123:abcd1234:extra"),
            reserve.replace("abc123:abcd1234", "ABC123:abcd1234"),
            reserve.replace("abc123:abcd1234", "abc123:abcd1234\n"),
            reserve.replace("\"schemaVersion\":3,\"admissionMode\":\"managed-lease-v2\",", "\"schemaVersion\":2,"),
            managedMetadata().replace("https://telemost.yandex.ru/j/12345678901234", "abc123:abcd1234")
        )) assertNull("Reject provider/locator/schema mismatch", GeneralBootstrapV1.fromPrivateBytes(bad.toByteArray()))
    }
    @Test fun managedLeaseDiscoveryRequiresExplicitVersionAndMode() {
        val managed = GeneralTestFixture.metadata().replace("\"schemaVersion\":1",
            "\"schemaVersion\":3,\"admissionMode\":\"managed-lease-v2\",\"roomUrl\":\"https://telemost.yandex.ru/j/12345678901234\"")
        val parsed = GeneralBootstrapV1.fromPrivateBytes(managed.toByteArray())
        assertNotNull("Explicit managed discovery must parse", parsed)
        assertEquals(3, parsed!!.schemaVersion)
        assertTrue(parsed.isUsableAt(GeneralTestFixture.now))
        for (bad in listOf(
            managed.replace(",\"admissionMode\":\"managed-lease-v2\"", ""),
            managed.replace("managed-lease-v2", "general"),
            managed.replace("\"schemaVersion\":3", "\"schemaVersion\":1"),
            managed.replace("\"schemaVersion\":3", "\"schemaVersion\":2"),
            managed.replace("\"schemaVersion\":3", "\"schemaVersion\":4"),
            managed.replace("https://telemost.yandex.ru/j/12345678901234", "https://example.invalid/j/12345678901234")
        )) assertNull("No mode reinterpretation", GeneralBootstrapV1.fromPrivateBytes(bad.toByteArray()))
    }
    @Test fun metadataIsStrictFiniteAndNotAnAdmissionFlag() {
        val valid = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        assertTrue(valid.isUsableAt(GeneralTestFixture.now))
        assertFalse(valid.isUsableAt(valid.expiresAtEpochMs))
        assertFalse(valid.toString().contains("test-room"))
        for (bad in listOf(
            GeneralTestFixture.metadata().replace("\"userId\":801", "\"userId\":0"),
            GeneralTestFixture.metadata().replace("\"schemaVersion\":1", "\"schemaVersion\":true"),
            GeneralTestFixture.metadata().replace("\"country\":\"NL\"", "\"country\":\"nl\""),
            GeneralTestFixture.metadata().replace("\"roomKey\":\"${GeneralTestFixture.key}\"", "\"roomKey\":\"${"0".repeat(64)}\""),
            GeneralTestFixture.metadata().dropLast(1) + ",\"allow\":true}",
            GeneralTestFixture.metadata().replace("\"userId\":801", "\"userId\":801,\"userId\":802"),
            GeneralTestFixture.metadata().replace("\"userId\":801", "\"userId\":801.0"),
            GeneralTestFixture.metadata() + " trailing",
            GeneralTestFixture.metadata().replace("\"policyRevision\":7", "\"policyRevision\":0"),
            GeneralTestFixture.metadata().replace("\"provider\":\"telemost\"", "\"provider\":\"manual\""),
            GeneralTestFixture.metadata().replace("\"transport\":\"vp8channel\"", "\"transport\":\"personal\""),
            GeneralTestFixture.metadata().replace("\"panelInstance\":\"rga-main\"", "\"panelInstance\":\"RGA-main\""),
            GeneralTestFixture.metadata().replace(GeneralTestFixture.installation, "short"),
            GeneralTestFixture.metadata().replace(GeneralTestFixture.room, "short"),
            GeneralTestFixture.metadata().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")
        )) assertNull(bad, GeneralBootstrapV1.fromPrivateBytes(bad.toByteArray()))
        val tooFar = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata(GeneralTestFixture.now + 330_001).toByteArray())!!
        assertFalse(tooFar.isUsableAt(GeneralTestFixture.now))
        assertNull(GeneralBootstrapV1.fromPrivateBytes(ByteArray(8193)))
        assertNull(GeneralBootstrapV1.fromPrivateBytes(byteArrayOf(0xc3.toByte(), 0x28)))
    }
}
