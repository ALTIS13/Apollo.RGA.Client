package org.olcbox.app.vpn

import org.olcbox.app.net.GeneralBootstrapV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class GeneralNativeRoomBindingTest {
    private fun metadata(version: Int): GeneralBootstrapV1 {
        val locator = if (version == 2) ",\"roomUrl\":\"https://telemost.yandex.ru/j/12345678901234\"" else ""
        return assertNotNull(GeneralBootstrapV1.fromPrivateBytes("""{"schemaVersion":$version,"panelInstance":"synthetic-panel","userId":41,"installationId":"synthetic-installation-0001","provider":"telemost","transport":"vp8channel","roomId":"synthetic-room-0001","country":"NL","roomKey":"${"ab".repeat(32)}","expiresAtEpochMs":1791408900000,"policyRevision":7$locator}""".encodeToByteArray()))
    }

    // A missing/URL-valued channel must not silently use the provider locator's
    // frame binding: the carrier binds frames to the logical authority room.
    @Test fun nativeGetsProviderLocatorAndDistinctLogicalChannel() {
        val actual = mutableListOf<Pair<String, String>>()
        configureGeneralNativeRoom(metadata(2), { actual += "room" to it }, { actual += "channel" to it })
        assertEquals(listOf("room" to "https://telemost.yandex.ru/j/12345678901234",
            "channel" to "synthetic-room-0001"), actual)
    }

    @Test fun legacyDiscoveryCannotInvokeEitherNativeSetter() {
        val actual = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            configureGeneralNativeRoom(metadata(1), { actual += it }, { actual += it })
        }
        assertEquals(emptyList(), actual)
    }
}
