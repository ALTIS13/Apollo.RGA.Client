package org.olcbox.app.ui.components.kit

import org.olcbox.app.AppInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PkVersionLineTest {
    @Test
    fun formatsBrandVersionAndBuild() {
        assertEquals(
            "APOLLO.RGA · v1.0.209 · 9f3c1ab",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.209", build = "9f3c1ab"))
        )
    }

    @Test
    fun stripsLeadingVIfAlreadyPresent() {
        assertEquals(
            "APOLLO.RGA · v2.0.0 · 9f3c1ab",
            pkVersionLine(AppInfo(name = "olcbox", version = "v2.0.0", build = "9f3c1ab"))
        )
    }

    @Test
    fun aBuildWithNoIdLeavesNoDanglingSeparator() {
        assertEquals(
            "APOLLO.RGA · v1.0.209",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.209", build = "  "))
        )
    }

    @Test
    fun aDirtyTreeIsPartOfTheBuildId() {
        assertEquals(
            "APOLLO.RGA · v1.0.273 · 9f3c1ab*",
            pkVersionLine(AppInfo(name = "olcbox", version = "1.0.273", build = "9f3c1ab*"))
        )
    }

    @Test
    fun masksTheSubscriptionToken() {
        assertEquals(
            "https://subscription.example.test/sub/bbbbbb…bbbbb",
            pkMaskSubscriptionUrl(
                "https://subscription.example.test/sub/" + "b".repeat(64)
            )
        )
    }

    @Test
    fun maskingKeepsShortPathsReadable() {
        // nothing secret to hide, and truncating would only make it harder to read
        assertEquals("https://example.test/sub", pkMaskSubscriptionUrl("https://example.test/sub"))
    }

    @Test
    fun maskingHidesQueryParameters() {
        assertEquals(
            "https://subscription.example.test/sub/bbbbbb…bbbbb?…",
            pkMaskSubscriptionUrl(
                "https://subscription.example.test/sub/" + "b".repeat(64) + "?crypt=1"
            )
        )
    }

    @Test
    fun masksATokenThatIsNotTheLastSegment() {
        // Our own subscription URL. The last segment is the literal "olcrtc", so
        // the old mask returned the string untouched and the row ellipsised it —
        // which looks like masking and is not.
        assertEquals(
            "https://subscription.example.test/sub/bbbbbb…bbbbb/olcrtc?…",
            pkMaskSubscriptionUrl(
                "https://subscription.example.test/sub/" + "b".repeat(64) +
                    "/olcrtc?crypt=1"
            )
        )
    }

    @Test
    fun maskingNeverTouchesTheHost() {
        // A host is longer than 12 characters often enough that masking by length
        // alone would mangle it into something nobody can identify.
        assertEquals(
            "https://partner.example.test/sub/j7k9e/cccccc…ccccc?…",
            pkMaskSubscriptionUrl("https://partner.example.test/sub/j7k9e/" + "c".repeat(16) + "?name=partner")
        )
    }
}
