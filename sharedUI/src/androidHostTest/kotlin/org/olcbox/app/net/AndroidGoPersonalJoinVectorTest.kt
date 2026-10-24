package org.olcbox.app.net

import java.math.BigInteger
import java.security.MessageDigest
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Public test vector only: scalar 1 is intentionally not a secret or production identity. */
@RunWith(RobolectricTestRunner::class)
class AndroidGoPersonalJoinVectorTest {
    @Test fun fixedAndroidJcaClaimMatchesGoVerifierVector() {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val keys = KeyFactory.getInstance("EC")
        val publicKey = keys.generatePublic(ECPublicKeySpec(parameters.generator, parameters))
        val signer = object : PersonalJoinSigningKey {
            override val installationId = "test_installation_A063_01"
            override val publicKeyDer: ByteArray = publicKey.encoded
            override fun sign(transcript: ByteArray): ByteArray {
                // Test-only ECDSA d=1, k=1. The production signer is AndroidKeyStore.
                val r = parameters.generator.affineX.mod(parameters.order)
                val digest = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(transcript))
                val s = digest.add(r).mod(parameters.order)
                fun integer(value: BigInteger): ByteArray = value.toByteArray().let { bytes ->
                    byteArrayOf(0x02, bytes.size.toByte()) + bytes
                }
                val body = integer(r) + integer(s)
                return byteArrayOf(0x30, body.size.toByte()) + body
            }
        }
        val claims = JSONObject(PersonalJoinProofFactory(
            signer,
            clockMs = { Instant.parse("2026-09-28T19:00:00Z").toEpochMilli() },
            nonceBytes = { out -> out.indices.forEach { out[it] = it.toByte() } }
        ).create("test-room-000001", "NL").toJsonString())
        assertEquals("test_installation_A063_01", claims.getString("installationId"))
        assertEquals("2026-09-28T19:00:00Z", claims.getString("issuedAt"))
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", claims.getString("nonce"))
        assertEquals(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaxfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpZP40Li_hp_m47n60p8D54WK84zV2sxXs7LtkBoN79R9Q",
            claims.getString("publicKey")
        )
        assertEquals(
            "MEUCIGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWAiEA4HKtt6HnAsMZ7iY_boNfdaK-dhtBILFlNJMXpTihjJM",
            claims.getString("signature")
        )
    }
}
