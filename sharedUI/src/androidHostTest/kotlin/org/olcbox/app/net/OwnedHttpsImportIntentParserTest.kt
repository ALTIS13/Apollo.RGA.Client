package org.olcbox.app.net

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnedHttpsImportIntentParserTest {
    private val view = "android.intent.action.VIEW"
    private val host = "owned.example.test"
    private val path = "/import"
    private val payload = "https://subscription.example.test/sub?token=synthetic+value"
    private val encodedPayload = "https%3A%2F%2Fsubscription.example.test%2Fsub%3Ftoken%3Dsynthetic%2Bvalue"

    private fun parse(data: String?, action: String? = view): String? =
        OwnedHttpsImportIntentParser.payloadOf(action, data, host, path)

    @Test fun acceptsOnlyOneDecodedLiteralHttpsPayload() {
        assertEquals(payload, parse("https://$host$path#$encodedPayload"))
        assertEquals("https://subscription.example.test/sub?token=a+b",
            parse("https://$host$path#https%3A%2F%2Fsubscription.example.test%2Fsub%3Ftoken%3Da%2Bb"))
        assertEquals("https://subscription.example.test/sub?x=1&y=2",
            parse("https://$host$path#https%3A%2F%2Fsubscription.example.test%2Fsub%3Fx%3D1%26y%3D2"))
        assertEquals("https://subscription.example.test/sub?token=a%26mode=b",
            parse("https://$host$path#https%3A%2F%2Fsubscription.example.test%2Fsub%3Ftoken%3Da%2526mode%3Db"))
        assertEquals("https://subscription.example.test/sub?token=a%23mode",
            parse("https://$host$path#https%3A%2F%2Fsubscription.example.test%2Fsub%3Ftoken%3Da%2523mode"))
    }

    @Test fun acceptsRawSubscriptionLinkWithoutDecodingItsOwnEscapes() {
        assertEquals(payload, parse("https://$host$path#$payload"))
        assertEquals("https://subscription.example.test/sub?token=a%26mode=b",
            parse("https://$host$path#https://subscription.example.test/sub?token=a%26mode=b"))
        assertEquals("https://subscription.example.test/sub?token=a%23mode",
            parse("https://$host$path#https://subscription.example.test/sub?token=a%23mode"))
    }

    @Test fun rejectsRawNonHttpsOrCredentialBearingTarget() {
        assertNull(parse("https://$host$path#http://subscription.example.test/sub"))
        assertNull(parse("https://$host$path#https://user@subscription.example.test/sub"))
        assertNull(parse("https://$host$path#https://subscription.example.test/sub#nested"))
    }

    @Test fun rejectsWrongActionOrMissingData() {
        assertNull(parse("https://$host$path#$encodedPayload", "android.intent.action.SEND"))
        assertNull(parse("https://$host$path#$encodedPayload", null))
        assertNull(parse(null))
    }

    @Test fun rejectsForeignOrAmbiguousEnvelope() {
        val invalid = listOf(
            "http://$host$path#$encodedPayload",
            "HTTPS://$host$path#$encodedPayload",
            "https://Owned.example.test$path#$encodedPayload",
            "https://other.example.test$path#$encodedPayload",
            "https://оwned.example.test$path#$encodedPayload",
            "https://$host.evil.test$path#$encodedPayload",
            "https://evil$host$path#$encodedPayload",
            "https://$host.$path#$encodedPayload",
            "https://user@$host$path#$encodedPayload",
            "https://$host:443$path#$encodedPayload",
            "https://$host$path?source=external#$encodedPayload",
            "https://$host$path?#$encodedPayload",
            "https://$host$path/#$encodedPayload",
            "https://$host/Import#$encodedPayload",
            "https://$host/%69mport#$encodedPayload",
            "https://$host$path",
            "https://$host$path#",
            "https://$host$path#https://subscription.example.test/sub#nested",
        )
        invalid.forEach { assertNull(parse(it), "Unexpected envelope acceptance") }
    }

    @Test fun rejectsMalformedControlOversizedAndDoubleEncodedFragments() {
        val invalid = listOf(
            "https://$host$path#https%3A%2F%2Fsubscription.example.test%2F%ZZ",
            "https://$host$path#https%3A%2F%2Fsubscription.example.test%2F%C3%28",
            "https://$host$path#https%3A%2F%2Fsubscription.example.test%2F%0A",
            "https://$host$path#https%3A%2F%2Fsubscription.example.test%2F%00",
            "https://$host$path#%2568ttps%253A%252F%252Fsubscription.example.test%252Fsub",
            "https://$host$path#" + "a".repeat(9_000),
            "https://$host$path#" + "a".repeat(20_000),
        )
        invalid.forEach { assertNull(parse(it), "Unexpected fragment acceptance") }
    }

    @Test fun rejectsPayloadThatIsNotAnHttpsUrl() {
        val invalid = listOf(
            "http%3A%2F%2Fsubscription.example.test%2Fsub",
            "olcrtc%3A%2F%2Fsubscription.example.test%2Fsub",
            "https%3A%2F%2F",
            "https%3A%2F%2Fuser%40subscription.example.test%2Fsub",
            "https%3A%2F%2Fsubscription.example.test%2Fsub%20trailer",
        )
        invalid.forEach { assertNull(parse("https://$host$path#$it"), "Unexpected payload acceptance") }
    }

    @Test fun rejectsInvalidExpectedOwnershipConfiguration() {
        assertNull(OwnedHttpsImportIntentParser.payloadOf(view,
            "https://$host$path#$encodedPayload", "", path))
        assertNull(OwnedHttpsImportIntentParser.payloadOf(view,
            "https://$host$path#$encodedPayload", host, "import"))
        assertNull(OwnedHttpsImportIntentParser.payloadOf(view,
            "https://$host$path#$encodedPayload", host, "$path?unsafe"))
    }
}
