package org.olcbox.app.net

import android.util.Base64
import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidManagedJoinSignerTest {
    @Test fun reserveLocatorDoesNotReplaceTheSignedAuthorityRoom() {
        val json = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"salutejazz\"")
            .replace("\"transport\":\"vp8channel\"", "\"transport\":\"datachannel\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", "abc123:abcd1234")
        val metadata = GeneralBootstrapV1.fromPrivateBytes(json.toByteArray())!!
        var locator: String? = null; var channel: String? = null
        org.olcbox.app.vpn.configureGeneralNativeRoom(metadata, { locator = it }, { channel = it })
        assertEquals("abc123:abcd1234", locator)
        assertEquals(GeneralTestFixture.room, channel)
        val signer = AndroidManagedJoinSigner(metadata, GeneralTestFixture.signer()) { GeneralTestFixture.now }
        val proof = signer.signManagedJoin()
        assertFalse(proof.contains("abcd1234"))
        assertNotNull(GeneralJson.parse(proof.toByteArray(), 4096)["signature"])
    }
    @Test fun providerLocatorAndSignedAuthorityChannelReachDifferentNativeSetters() {
        val metadata = GeneralBootstrapV1.fromPrivateBytes(managedMetadata().toByteArray())!!
        var locator: String? = null
        var channel: String? = null
        org.olcbox.app.vpn.configureGeneralNativeRoom(metadata, { locator = it }, { channel = it })
        assertEquals("https://telemost.yandex.ru/j/12345678901234", locator)
        assertEquals(GeneralTestFixture.room, channel)
    }
    @Test fun everyReconnectGetsFreshSameKeyRoomBoundCryptographicProof() {
        val key = GeneralTestFixture.signer()
        var signs = 0
        val counted = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray { signs++; return key.sign(transcript) }
        }
        val metadata = GeneralBootstrapV1.fromPrivateBytes(managedMetadata().toByteArray())!!
        val signer = AndroidManagedJoinSigner(metadata, counted) { GeneralTestFixture.now }
        assertEquals("No eager proof during configuration", 0, signs)
        val proofs = List(2) { GeneralJson.parse(signer.signManagedJoin().toByteArray(), 4096) }
        assertEquals(2, signs)
        assertNotEquals(proofs[0]["nonce"], proofs[1]["nonce"])
        for (proof in proofs) {
            assertEquals(setOf("installationId", "issuedAt", "nonce", "publicKey", "signature"), proof.keys)
            assertEquals(GeneralTestFixture.installation, proof["installationId"])
            val der = decode(proof["publicKey"] as String)
            assertArrayEquals(key.publicKeyDer, der)
            val transcript = PersonalJoinTranscript.build(GeneralTestFixture.installation,
                MessageDigest.getInstance("SHA-256").digest(der), GeneralTestFixture.room, "NL",
                proof["issuedAt"] as String, decode(proof["nonce"] as String))
            assertTrue(Signature.getInstance("SHA256withECDSA").apply {
                initVerify(canonicalP256PublicKey(der)); update(transcript)
            }.verify(decode(proof["signature"] as String)))
        }
        assertFalse(signer.toString().contains(GeneralTestFixture.room))
    }

    @Test fun legacyModeRotatedKeyAndExpiredDiscoveryCannotSign() {
        val key = GeneralTestFixture.signer()
        denied { AndroidManagedJoinSigner(GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!,
            key) { GeneralTestFixture.now } }
        var clock = GeneralTestFixture.now
        var current = key
        var signs = 0
        val rotating = object : PersonalJoinSigningKey {
            override val installationId get() = current.installationId
            override val publicKeyDer get() = current.publicKeyDer
            override fun sign(transcript: ByteArray): ByteArray { signs++; return current.sign(transcript) }
        }
        val metadata = GeneralBootstrapV1.fromPrivateBytes(managedMetadata().toByteArray())!!
        val signer = AndroidManagedJoinSigner(metadata, rotating) { clock }
        current = GeneralTestFixture.signer()
        denied { signer.signManagedJoin() }; assertEquals(0, signs)
        current = key; clock = metadata.expiresAtEpochMs
        denied { signer.signManagedJoin() }; assertEquals(0, signs)
        denied { AndroidGeneralJoinSigner(metadata, key) { GeneralTestFixture.now } }
    }

    @Test fun cachedRoomDiscoveryNeverExtendsAdmissionAndKeyCancellationPropagates() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(managedMetadata().toByteArray())!!
        val selection = GeneralBootstrapSelection.fromVerifiedReceipt(metadata, key,
            GeneralTestFixture.now, GeneralTestFixture.now + 600_000)!!
        // Cached discovery can locate the carrier; only the SERVER can grant a fresh finite lease.
        val signer = AndroidManagedJoinSigner(selection, key) { GeneralTestFixture.now + 600_000 }
        assertNotNull(signer.signManagedJoin())
        val cancelled = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray = throw CancellationException("controlled")
        }
        val stop = AndroidManagedJoinSigner(metadata, cancelled) { GeneralTestFixture.now }
        try { stop.signManagedJoin(); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
    }
    private fun denied(block: () -> Any?) {
        try { block(); fail("Managed proof unexpectedly admitted") }
        catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains(GeneralTestFixture.room)) }
    }
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

internal fun managedMetadata(): String = GeneralTestFixture.metadata().replace("\"schemaVersion\":1",
    "\"schemaVersion\":3,\"admissionMode\":\"managed-lease-v2\",\"roomUrl\":\"https://telemost.yandex.ru/j/12345678901234\"")
