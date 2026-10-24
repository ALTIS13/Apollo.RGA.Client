package org.olcbox.app.net

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidVerifiedPersonalBootstrapProducerTest {
    private val now = 1_780_000_000_000L
    private val bearer = "https://pan.gate-altas.tech/TestToken_123"
    private val roomKey = "0123456789abcdef".repeat(4)
    private val signer = signer()

    @Test fun sameLinkProofProducesOnlyShortUuidAndAcceptsStrictNoStoreBootstrap(): Unit = runBlocking {
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val transport = PersonalBootstrapHttpTransport { path, body ->
            val json = JSONObject(body.toString(Charsets.UTF_8))
            requests += path to json
            when (path) {
                "/v1/personal/link/begin" -> response(challenge())
                "/v1/personal/bootstrap" -> response(bootstrap())
                else -> error("unexpected path")
            }
        }
        val result = AndroidVerifiedPersonalBootstrapProducer(transport) { now }
            .completeLinkAndPrefetch(bearer, signer)

        assertNotNull(result)
        assertEquals("test-room-000001", result?.roomId)
        assertEquals(roomKey, result?.roomKey)
        assertEquals(listOf("/v1/personal/link/begin", "/v1/personal/bootstrap"), requests.map { it.first })
        assertEquals("TestToken_123", requests[0].second.getString("shortUuid"))
        assertEquals("TestToken_123", requests[1].second.getString("shortUuid"))
        assertFalse(requests.any { it.second.toString().contains("pan.gate-altas.tech") })
        assertEquals(signer.installationId, requests[0].second.getString("installationId"))
        assertArrayEquals(signer.publicKeyDer, decode(requests[0].second.getString("publicKeyDer")))
        val signature = decode(requests[1].second.getString("signature"))
        assertTrue(signature.isNotEmpty())
    }

    @Test fun invalidLinkNeverReachesTransport(): Unit = runBlocking {
        var calls = 0
        val producer = AndroidVerifiedPersonalBootstrapProducer(
            PersonalBootstrapHttpTransport { _, _ -> calls++; response(challenge()) }
        ) { now }
        for (link in listOf(
            "http://pan.gate-altas.tech/TestToken_123",
            "https://pan.gate-altas.tech:443/TestToken_123",
            "https://pan.gate-altas.tech/TestToken_123?x=1",
            "https://pan.gate-altas.tech/TestToken_123/extra",
            "https://evil.invalid/TestToken_123"
        )) assertNull(producer.completeLinkAndPrefetch(link, signer))
        assertEquals(0, calls)
    }

    @Test fun missingNoStoreOrMalformedChallengeNeverRequestsRoom(): Unit = runBlocking {
        for (bad in listOf(
            response(challenge(), cacheControl = null),
            response(challenge().put("unexpected", true)),
            response(challenge().put("nonce", "not-base64")),
            response(challenge().put("expiresAt", "2001-01-01T00:00:00Z"))
        )) {
            var calls = 0
            val producer = AndroidVerifiedPersonalBootstrapProducer(
                PersonalBootstrapHttpTransport { _, _ -> calls++; bad }
            ) { now }
            assertNull(producer.completeLinkAndPrefetch(bearer, signer))
            assertEquals(1, calls)
        }
    }

    @Test fun denialUnavailableOrBadBootstrapCannotYieldRoom(): Unit = runBlocking {
        for (second in listOf(
            PersonalBootstrapHttpResponse(403, "application/json", "{\"error\":\"denied\"}".toByteArray(), "no-store"),
            PersonalBootstrapHttpResponse(503, "application/json", "{\"error\":\"unavailable\"}".toByteArray(), "no-store"),
            response(bootstrap(), cacheControl = "private"),
            response(bootstrap().put("roomKey", "0".repeat(64)))
        )) {
            var calls = 0
            val producer = AndroidVerifiedPersonalBootstrapProducer(
                PersonalBootstrapHttpTransport { _, _ -> if (++calls == 1) response(challenge()) else second }
            ) { now }
            assertNull(producer.completeLinkAndPrefetch(bearer, signer))
            assertEquals(2, calls)
        }
    }

    private fun challenge(): JSONObject = JSONObject()
        .put("challengeId", "test_challenge_A063_00001")
        .put("panelInstance", "rga-main")
        .put("nonce", url64(ByteArray(32) { (it + 1).toByte() }))
        .put("expiresAt", "2026-05-28T20:27:40Z")

    private fun bootstrap(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("provider", "telemost")
        .put("transport", "vp8channel")
        .put("roomId", "test-room-000001")
        .put("country", "NL")
        .put("roomKey", roomKey)
        .put("policyRevision", 7)
        .put("expiresAtEpochMs", now + 60_000)

    private fun response(json: JSONObject, cacheControl: String? = "no-store") =
        PersonalBootstrapHttpResponse(200, "application/json", json.toString().toByteArray(), cacheControl)

    private fun signer(): PersonalJoinSigningKey {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return object : PersonalJoinSigningKey {
            override val installationId = "test_installation_A063_01"
            override val publicKeyDer: ByteArray get() = pair.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(pair.private); update(transcript) }.sign()
        }
    }

    private fun url64(bytes: ByteArray): String = Base64.encodeToString(
        bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
