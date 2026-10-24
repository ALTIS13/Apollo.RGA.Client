package org.olcbox.app.net

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalJoinProofTest {
    private val installationId = "test_installation_A063_01"
    private val room = "test-room-000001"
    private val country = "NL"
    private val issuedAt = "2026-09-28T19:00:00.1Z"
    private val fingerprint = ByteArray(32) { (it + 1).toByte() }
    private val nonce = ByteArray(32) { (255 - it).toByte() }

    @Test fun transcriptMatchesGoLengthPrefixedContract() {
        val transcript = PersonalJoinTranscript.build(
            installationId, fingerprint, room, country, issuedAt, nonce
        )
        val expected = "Apollo.RGA/olcrtc-test-join/v1".toByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 25) + installationId.toByteArray() +
            byteArrayOf(0, 32) + fingerprint +
            byteArrayOf(0, 16) + room.toByteArray() +
            byteArrayOf(0, 2) + country.toByteArray() +
            byteArrayOf(0, 22) + issuedAt.toByteArray() +
            byteArrayOf(0, 32) + nonce
        assertArrayEquals(expected, transcript)
    }

    @Test fun transcriptRejectsNoncanonicalOrUnboundedInputs() {
        val invalid = listOf(
            listOf("short", room, country, issuedAt),
            listOf(installationId, "https://example.invalid", country, issuedAt),
            listOf(installationId, room, "nl", issuedAt),
            listOf(installationId, room, country, "2026-09-28T19:00:00.100Z"),
            listOf(installationId, room, country, "2026-13-28T19:00:00Z"),
            listOf(installationId, room, country, "2026-09-28T21:00:00+02:00")
        )
        for ((id, requestedRoom, requestedCountry, at) in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                PersonalJoinTranscript.build(id, fingerprint, requestedRoom, requestedCountry, at, nonce)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalJoinTranscript.build(installationId, fingerprint, room, country, issuedAt, ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalJoinTranscript.build(installationId, ByteArray(31), room, country, issuedAt, nonce)
        }
    }

    @Test fun freshFlatClaimsVerifyAsP256AndBindRoomCountryTimeAndNonce() {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = object : PersonalJoinSigningKey {
            override val installationId = this@PersonalJoinProofTest.installationId
            override val publicKeyDer: ByteArray = keys.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(keys.private); update(transcript) }.sign()
        }
        var sequence = 0
        val factory = PersonalJoinProofFactory(
            signer,
            clockMs = { Instant.parse("2026-09-28T19:00:00.100Z").toEpochMilli() },
            nonceBytes = { target -> target.fill((++sequence).toByte()) }
        )
        val first = factory.create(room, country)
        val second = factory.create(room, country)
        val claims = JSONObject(first.toJsonString())
        assertEquals(setOf("installationId", "issuedAt", "nonce", "publicKey", "signature"), claims.keys().asSequence().toSet())
        assertEquals(installationId, claims.getString("installationId"))
        assertEquals(issuedAt, claims.getString("issuedAt"))
        assertEquals(43, claims.getString("nonce").length)
        assertFalse(first.toJsonString().contains("="))
        for (name in listOf("nonce", "publicKey", "signature")) {
            val encoded = claims.getString(name)
            assertEquals(encoded, Base64.getUrlEncoder().withoutPadding().encodeToString(
                Base64.getUrlDecoder().decode(encoded)
            ))
        }
        assertFalse(first.toString().contains(claims.getString("nonce")))
        assertFalse(first.toString().contains(claims.getString("signature")))
        assertFalse(first.toString().contains(installationId))
        assertFalse(first.toString().contains(room))
        assertFalse(first.toString().contains("key"))
        assertFalse(first.toJsonString().contains(room))
        assertFalse(claims.getString("nonce") == JSONObject(second.toJsonString()).getString("nonce"))

        val keyHash = MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(claims.getString("publicKey")))
        val decodedNonce = Base64.getUrlDecoder().decode(claims.getString("nonce"))
        val signature = Base64.getUrlDecoder().decode(claims.getString("signature"))
        fun verifies(id: String, targetRoom: String, targetCountry: String, at: String, challenge: ByteArray): Boolean {
            val transcript = PersonalJoinTranscript.build(id, keyHash, targetRoom, targetCountry, at, challenge)
            return Signature.getInstance("SHA256withECDSA").apply {
                initVerify(keys.public)
                update(transcript)
            }.verify(signature)
        }
        assertTrue(verifies(installationId, room, country, issuedAt, decodedNonce))
        assertFalse(verifies(installationId, "other-room-000001", country, issuedAt, decodedNonce))
        assertFalse(verifies("test_installation_other_01", room, country, issuedAt, decodedNonce))
        assertFalse(verifies(installationId, room, "DE", issuedAt, decodedNonce))
        assertFalse(verifies(installationId, room, country, "2026-09-28T19:00:01.1Z", decodedNonce))
        assertFalse(verifies(installationId, room, country, issuedAt, ByteArray(32) { 3 }))
    }

    @Test fun nonP256PublicKeyCannotProduceClaims() {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val signer = object : PersonalJoinSigningKey {
            override val installationId = this@PersonalJoinProofTest.installationId
            override val publicKeyDer: ByteArray = keys.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(keys.private); update(transcript) }.sign()
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalJoinProofFactory(signer, { Instant.parse("2026-09-28T19:00:00Z").toEpochMilli() }) { it.fill(1) }
                .create(room, country)
        }
    }

    @Test fun claimInstallationIdIsTheOneActuallySigned() {
        val keys = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var reads = 0
        val signer = object : PersonalJoinSigningKey {
            override val installationId: String get() = if (++reads == 1) {
                this@PersonalJoinProofTest.installationId
            } else "test_installation_other_01"
            override val publicKeyDer: ByteArray = keys.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(keys.private); update(transcript) }.sign()
        }
        val claims = JSONObject(PersonalJoinProofFactory(
            signer, { Instant.parse("2026-09-28T19:00:00Z").toEpochMilli() }
        ) { it.fill(1) }.create(room, country).toJsonString())
        assertEquals(installationId, claims.getString("installationId"))
    }
}
