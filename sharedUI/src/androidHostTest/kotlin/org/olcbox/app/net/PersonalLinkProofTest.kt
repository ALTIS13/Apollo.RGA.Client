package org.olcbox.app.net

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalLinkProofTest {
    private val now = Instant.parse("2026-09-28T19:00:00Z").toEpochMilli()
    private val installationId = "test_installation_A063_01"
    private val challengeId = "test_challenge_A063_00001"
    private val nonce = ByteArray(32) { it.toByte() }

    @Test fun fixedTranscriptMatchesGoLinkChallengeSigningPayloadVector() {
        // Test-only P-256 generator point (d=1), also used in the Go join vector.
        val publicDer = fixedP256PublicDer()
        val transcript = PersonalLinkTranscript.build(
            "rga-main", installationId, challengeId, publicDer, nonce
        )
        assertEquals(
            "41706f6c6c6f2e5247412f6c696e6b2d70726f6f662f763100" +
                "00087267612d6d61696e" +
                "0019746573745f696e7374616c6c6174696f6e5f413036335f3031" +
                "0019746573745f6368616c6c656e67655f413036335f3030303031" +
                "00205cd252fb0ce8932436faf8ccd1040981b89ee4ad6b9fe9e2a2b7e71aacb27cd3" +
                "0020000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            transcript.joinToString("") { "%02x".format(it) }
        )
    }

    @Test fun factoryUsesInstallationIdentityToSignExactChallengeAndRejectsTampering() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = object : PersonalJoinSigningKey {
            override val installationId = this@PersonalLinkProofTest.installationId
            override val publicKeyDer: ByteArray = pair.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(pair.private); update(transcript) }.sign()
        }
        val factory = PersonalLinkProofFactory(signer) { now }
        val challenge = PersonalLinkChallenge("rga-main", challengeId, nonce, now + 60_000)
        val proof = factory.create(challenge)
        assertEquals(installationId, factory.installationId)
        assertArrayEquals(pair.public.encoded, factory.publicKeyDer())
        assertEquals(challengeId, proof.challengeId)
        assertArrayEquals(pair.public.encoded, proof.publicKeyDer())
        assertFalse(proof.toString().contains(challengeId))
        assertFalse(challenge.toString().contains(challengeId))
        val transcript = PersonalLinkTranscript.build(
            "rga-main", installationId, challengeId, pair.public.encoded, nonce
        )
        assertTrue(Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(transcript)
        }.verify(proof.signature()))
        val tampered = PersonalLinkTranscript.build(
            "rga-other", installationId, challengeId, pair.public.encoded, nonce
        )
        assertFalse(Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(tampered)
        }.verify(proof.signature()))
    }

    @Test fun staleMalformedOrMismatchedChallengeNeverProducesProof() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val signer = object : PersonalJoinSigningKey {
            override val installationId = this@PersonalLinkProofTest.installationId
            override val publicKeyDer: ByteArray = pair.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(pair.private); update(transcript) }.sign()
        }
        val factory = PersonalLinkProofFactory(signer) { now }
        val invalid = listOf(
            PersonalLinkChallenge("rga-main", challengeId, nonce, now),
            PersonalLinkChallenge("RGA-main", challengeId, nonce, now + 60_000),
            PersonalLinkChallenge("rga-main", "bad!challenge", nonce, now + 60_000),
            PersonalLinkChallenge("rga-main", challengeId, ByteArray(32), now + 60_000),
            PersonalLinkChallenge("rga-main", challengeId, ByteArray(31) { 1 }, now + 60_000)
        )
        invalid.forEach { challenge ->
            assertThrows(IllegalArgumentException::class.java) { factory.create(challenge) }
        }
        val mismatch = object : PersonalJoinSigningKey {
            override val installationId = this@PersonalLinkProofTest.installationId
            override val publicKeyDer: ByteArray = pair.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = ByteArray(8) { 1 }
        }
        assertThrows(IllegalStateException::class.java) {
            PersonalLinkProofFactory(mismatch) { now }.create(
                PersonalLinkChallenge("rga-main", challengeId, nonce, now + 60_000)
            )
        }
    }

    @Test fun expiryDuringKeystoreSigningStillDeniesProof() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        var clock = now
        val signer = object : PersonalJoinSigningKey {
            override val installationId = this@PersonalLinkProofTest.installationId
            override val publicKeyDer: ByteArray = pair.public.encoded
            override fun sign(transcript: ByteArray): ByteArray {
                clock = now + 60_000
                return Signature.getInstance("SHA256withECDSA")
                    .apply { initSign(pair.private); update(transcript) }.sign()
            }
        }
        val factory = PersonalLinkProofFactory(signer) { clock }
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(PersonalLinkChallenge("rga-main", challengeId, nonce, now + 60_000))
        }
    }

    private fun fixedP256PublicDer(): ByteArray {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(parameters.generator, parameters)).encoded
    }
}
