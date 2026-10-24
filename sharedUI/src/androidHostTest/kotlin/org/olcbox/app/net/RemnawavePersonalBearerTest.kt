package org.olcbox.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemnawavePersonalBearerTest {
    @Test fun literalProductionSubscriptionLinkYieldsOnlyRedactedShortUuid() {
        val importedUrl = "https://pan.gate-altas.tech/Abc_1234-Z"
        val bearer = RemnawavePersonalBearer.parse(importedUrl)
        assertNotNull(bearer)
        assertEquals("Abc_1234-Z", bearer!!.shortUuidForBridgeRequest())
        assertFalse(bearer.toString().contains("Abc_1234-Z"))
        assertFalse(bearer.toString().contains(importedUrl))
    }

    @Test fun parserRejectsEveryNonliteralAuthorityPathOrSuffix() {
        val longToken = "A".repeat(129)
        val invalid = listOf(
            "http://pan.gate-altas.tech/Abc_1234-Z",
            "HTTPS://pan.gate-altas.tech/Abc_1234-Z",
            "https://PAN.gate-altas.tech/Abc_1234-Z",
            "https://pan.gate-altas.tech.evil.test/Abc_1234-Z",
            "https://user@pan.gate-altas.tech/Abc_1234-Z",
            "https://pan.gate-altas.tech:443/Abc_1234-Z",
            "https://pan.gate-altas.tech/Abc_1234-Z?format=json",
            "https://pan.gate-altas.tech/Abc_1234-Z#fragment",
            "https://pan.gate-altas.tech/sub/Abc_1234-Z",
            "https://pan.gate-altas.tech/Abc_1234-Z/",
            "https://pan.gate-altas.tech//Abc_1234-Z",
            "https://pan.gate-altas.tech/%41bc_1234-Z",
            "https://pan.gate-altas.tech/Abc_1234%2fZ",
            "https://pan.gate-altas.tech/Abc.1234-Z",
            "https://pan.gate-altas.tech/short7",
            "https://pan.gate-altas.tech/$longToken",
            "https://pan.gate-altas.tech/Abc_1234-Z\n"
        )
        invalid.forEach { url ->
            assertNull("unexpectedly accepted URL shape", RemnawavePersonalBearer.parse(url))
        }
    }
}
