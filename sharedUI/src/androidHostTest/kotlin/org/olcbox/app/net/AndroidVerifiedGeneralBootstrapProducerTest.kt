package org.olcbox.app.net

import android.util.Base64
import java.security.Signature
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidVerifiedGeneralBootstrapProducerTest {
    @Test fun issuerFiveMinuteMetadataSurvivesBoundedReceiverClockSkew(): Unit = runBlocking {
        // Literal server expiry; phone clock can trail by the observed1631ms or at most30s.
        for (receiverNow in listOf(1_780_000_000_000L, 1_779_999_998_369L, 1_779_999_970_000L)) {
            val signer = GeneralTestFixture.signer()
            var calls = 0
            val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
                GeneralTestFixture.response(if (++calls == 1) GeneralTestFixture.challenge()
                    else GeneralTestFixture.metadata(1_780_000_300_000L))
            }) { receiverNow }
            val result = producer.completeLinkAndPrefetch(GeneralTestFixture.link, signer)
            assertNotNull("Authentic metadata was dropped solely by clock skew", result)
            assertEquals(2, calls)
            assertEquals(1_780_000_300_000L, result!!.expiresAtEpochMs)
            val selection = GeneralBootstrapSelection.fromVerifiedReceipt(result, signer, receiverNow, receiverNow)
            assertNotNull(selection)
            assertTrue(selection!!.matchesKeyAndIsUsableAt(signer, receiverNow))
            assertFalse(selection.matchesKeyAndIsUsableAt(signer, receiverNow - 1))
        }
    }

    @Test fun sameImportedLinkAndKeyAreBoundByRealLinkProof(): Unit = runBlocking {
        val signer = GeneralTestFixture.signer()
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { path, body ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            assertTrue(body.size <= 4096)
            val request = JSONObject(body.toString(Charsets.UTF_8)); requests += path to request
            when (path) {
                "/v1/general/link/begin" -> GeneralTestFixture.response(GeneralTestFixture.challenge())
                "/v1/general/bootstrap" -> {
                    val der = decode(request.getString("publicKeyDer"))
                    val signature = decode(request.getString("signature"))
                    val transcript = PersonalLinkTranscript.build("rga-main", signer.installationId,
                        "test_challenge_general_01", der, ByteArray(32) { (it + 1).toByte() })
                    assertTrue(Signature.getInstance("SHA256withECDSA").apply {
                        initVerify(canonicalP256PublicKey(der)); update(transcript)
                    }.verify(signature))
                    GeneralTestFixture.response(GeneralTestFixture.metadata())
                }
                else -> error("Unexpected controlled path")
            }
        }) { GeneralTestFixture.now }
        val result = producer.completeLinkAndPrefetch(GeneralTestFixture.link, signer)
        assertNotNull(result)
        assertEquals(801L, result!!.userId)
        assertEquals(signer.installationId, result.installationId)
        assertEquals(listOf("/v1/general/link/begin", "/v1/general/bootstrap"), requests.map { it.first })
        assertEquals("TestToken_123", requests[0].second.getString("shortUuid"))
        assertFalse(requests.any { it.second.toString().contains("pan.gate-altas.tech") })
        assertArrayEquals(signer.publicKeyDer, decode(requests[0].second.getString("publicKeyDer")))
    }

    @Test fun mixedMetadataDuplicateJsonAndStaleExpiryDeny(): Unit = runBlocking {
        for (bad in listOf(
            GeneralTestFixture.metadata().replace("rga-main", "another-panel"),
            GeneralTestFixture.metadata().replace(GeneralTestFixture.installation, "other_installation_0001"),
            GeneralTestFixture.metadata(GeneralTestFixture.now),
            GeneralTestFixture.metadata(GeneralTestFixture.now + 330_001),
            GeneralTestFixture.metadata().replace("\"userId\":801", "\"userId\":801,\"userId\":802"),
            " ".repeat(8193)
        )) {
            var calls = 0
            val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
                GeneralTestFixture.response(if (++calls == 1) GeneralTestFixture.challenge() else bad)
            }) { GeneralTestFixture.now }
            assertNull(producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()))
        }
    }

    @Test fun transportFailuresNoStoreAndMalformedChallengeDenyBeforeSecondRequest(): Unit = runBlocking {
        for (first in listOf(
            GeneralBootstrapHttpResponse(200, "application/json", GeneralTestFixture.challenge().toByteArray(), null),
            GeneralTestFixture.response(GeneralTestFixture.challenge().replace("rga-main", "INVALID")),
            GeneralTestFixture.response(GeneralTestFixture.challenge().replace("\"panelInstance\":\"rga-main\"", "\"panelInstance\":\"rga-main\",\"panelInstance\":\"other\"")),
            GeneralBootstrapHttpResponse(503, "application/json", "{}".toByteArray(), "no-store")
        )) {
            var calls = 0
            val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ -> calls++; first }) { GeneralTestFixture.now }
            assertNull(producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()))
            assertEquals(1, calls)
        }
        assertNull(AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ -> error("private detail") })
            .completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()))
    }

    @Test fun cancellationRethrowsAndInvalidLinkNeverRequests(): Unit = runBlocking {
        var calls = 0
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ -> calls++; throw CancellationException("controlled") }) { GeneralTestFixture.now }
        assertNull(producer.completeLinkAndPrefetch("https://evil.invalid/TestToken_123", GeneralTestFixture.signer()))
        assertEquals(0, calls)
        try { producer.completeLinkAndPrefetch(GeneralTestFixture.link, GeneralTestFixture.signer()); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun keyCancellationAndSecondRequestCancellationAreNotConvertedToDenial(): Unit = runBlocking {
        val signer = GeneralTestFixture.signer()
        val cancelledKey = object : PersonalJoinSigningKey by signer {
            override fun sign(transcript: ByteArray): ByteArray = throw CancellationException("controlled")
        }
        var requests = 0
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
            requests++; GeneralTestFixture.response(GeneralTestFixture.challenge())
        }) { GeneralTestFixture.now }
        try { producer.completeLinkAndPrefetch(GeneralTestFixture.link, cancelledKey); fail("Key cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals(1, requests)
        var calls = 0
        val second = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ ->
            if (++calls == 1) GeneralTestFixture.response(GeneralTestFixture.challenge()) else throw CancellationException("controlled")
        }) { GeneralTestFixture.now }
        try { second.completeLinkAndPrefetch(GeneralTestFixture.link, signer); fail("Second cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun responseBytesAreCopiedAndRepresentationDoesNotRetainBody() {
        val bytes = GeneralTestFixture.metadata().toByteArray()
        val response = GeneralBootstrapHttpResponse(200, "application/json", bytes, "no-store")
        bytes.fill(0)
        val first = response.body; first.fill(0)
        assertEquals(GeneralTestFixture.metadata(), response.body.toString(Charsets.UTF_8))
        assertFalse(response.toString().contains(GeneralTestFixture.key))
    }

    private fun decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
