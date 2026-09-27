package org.olcbox.app.ui.components.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkSubscriptionSourceTest {
    private val ourUrl = "https://subscription.example.test/sub/" + "a".repeat(40) + "/olcrtc?crypt=1"
    private val partnerUrl = "https://partner.example.test/sub/j7k9e/" + "b".repeat(16) + "?name=partner"

    @Test
    fun anEncryptedSubscriptionNamesNeitherHostNorPath() {
        assertEquals(
            "Encrypted link",
            pkSubscriptionSourceLine(partnerUrl, originLink = "happ://crypt5/fixture-encrypted-link")
        )
    }

    @Test
    fun ourOwnCryptSubscriptionsAreRecognisedWithoutAnOriginLink() {
        // Installed before the origin link existed: ?crypt=1 is ours by definition.
        assertEquals("Encrypted link", pkSubscriptionSourceLine(ourUrl, originLink = null))
    }

    @Test
    fun aPlainSubscriptionShowsOnlyItsHost() {
        assertEquals("partner.example.test", pkSubscriptionSourceLine(partnerUrl.substringBefore('?')))
    }

    @Test
    fun theAdminGateRevealsTheMaskedUrl() {
        assertEquals(
            "https://subscription.example.test/sub/aaaaaa…aaaaa/olcrtc?…",
            pkSubscriptionSourceLine(ourUrl, originLink = null, revealed = true)
        )
    }

    @Test
    fun somethingUnparseableFallsBackToTheMaskRatherThanAnEmptyRow() {
        assertEquals("not a url", pkSubscriptionSourceLine("not a url"))
    }

    @Test
    fun hostParsing() {
        assertEquals("subscription.example.test", pkSubscriptionHost("https://subscription.example.test/sub/x"))
        assertEquals("subscription.example.test", pkSubscriptionHost("https://subscription.example.test"))
        assertNull(pkSubscriptionHost("garbage"))
    }

    @Test
    fun secrecyIsMarkerOnly() {
        assertTrue(pkSubscriptionIsSecret("https://example.test/sub/y", "olcrtc://crypt1/fixture-blob"))
        assertTrue(pkSubscriptionIsSecret("https://example.test/sub/y?crypt=1", null))
        assertTrue(!pkSubscriptionIsSecret("https://example.test/sub/y", null))
        assertTrue(!pkSubscriptionIsSecret("https://example.test/sub/y", "   "))
    }
}
