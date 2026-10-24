package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeneralRoomLocatorTest {
    private val locator = "https://telemost.yandex.ru/j/12345678901234"
    private fun wire(url: String = locator, version: Int = 2) = """{"schemaVersion":$version,"panelInstance":"synthetic-panel","userId":41,"installationId":"synthetic-installation-0001","provider":"telemost","transport":"vp8channel","roomId":"synthetic-room-0001","country":"FI","roomKey":"${"ab".repeat(32)}","expiresAtEpochMs":1791408900000,"policyRevision":7,"roomUrl":"$url"}""".encodeToByteArray()

    @Test fun distinctProviderLocatorParsesWithoutReplacingLogicalAuthority() {
        val metadata = assertNotNull(GeneralBootstrapV1.fromPrivateBytes(wire()))
        assertEquals(2, metadata.schemaVersion)
        assertEquals("synthetic-room-0001", metadata.roomId)
        assertEquals(41L, metadata.userId)
        assertEquals(7L, metadata.policyRevision)
        assertFalse(metadata.toString().contains(locator))
    }

    @Test fun unsafeProviderLocatorCannotBecomeNativeDiscovery() {
        for (url in listOf("http://telemost.yandex.ru/j/12345678901234",
            "https://evil.invalid/j/12345678901234", "https://telemost.yandex.ru.evil.invalid/j/12345678901234",
            "https://user@telemost.yandex.ru/j/12345678901234", "https://telemost.yandex.ru:443/j/12345678901234",
            "https://telemost.yandex.ru/j/12345678901234?token=private", "synthetic-room-0001")) {
            assertNull(GeneralBootstrapV1.fromPrivateBytes(wire(url)))
        }
    }

    @Test fun missingLocatorAndUnknownVersionsDeny() {
        assertNull(GeneralBootstrapV1.fromPrivateBytes(wire().decodeToString().replace(",\"roomUrl\":\"$locator\"", "").encodeToByteArray()))
        assertNull(GeneralBootstrapV1.fromPrivateBytes(wire(version = 3)))
        assertNull(GeneralBootstrapV1.fromPrivateBytes(wire(version = 1)))
    }
}
