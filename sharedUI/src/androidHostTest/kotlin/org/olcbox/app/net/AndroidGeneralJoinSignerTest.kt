package org.olcbox.app.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidGeneralJoinSignerTest {
    @Test fun typedSelectionKeepsTheServerProofBoundaryAndLegacyFreshness() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        val selection = GeneralBootstrapSelection.fromVerifiedReceipt(metadata, key, GeneralTestFixture.now, GeneralTestFixture.now)!!
        val now = GeneralTestFixture.now + 300_001
        denied { AndroidGeneralJoinSigner(metadata, key) { now } }
        denied { AndroidGeneralJoinSigner(selection, GeneralTestFixture.signer()) { now } }
        denied { AndroidGeneralJoinSigner(selection, key) { GeneralTestFixture.now + 86_400_000 } }
        var calls = 0
        val observed = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray { calls++; return key.sign(transcript) }
        }
        val callback = AndroidGeneralJoinSigner(selection, observed) { now }
        val expired = GeneralTestFixture.prepared(key.publicKeyDer)
        denied { callback.signGeneral(expired, GeneralTestFixture.carrier(expired), GeneralTestFixture.end) }
        fun fresh(input: ByteArray): ByteArray {
            val prefix = "Apollo.RGA/general-prepared-proof/v1\u0000".toByteArray()
            val body = input.copyOfRange(prefix.size + 4, input.size).toString(Charsets.UTF_8)
                .replace("2026-05-28T20:27:40Z", "2026-05-28T20:32:40Z").toByteArray()
            return prefix + ByteBuffer.allocate(4).putInt(body.size).array() + body
        }
        val wrongSubject = fresh(GeneralTestFixture.prepared(key.publicKeyDer, user = 802))
        denied { callback.signGeneral(wrongSubject, GeneralTestFixture.carrier(wrongSubject), GeneralTestFixture.now + 360_000) }
        val prepared = fresh(GeneralTestFixture.prepared(key.publicKeyDer))
        denied { callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared, room = "other-room-0001"), GeneralTestFixture.now + 360_000) }
        assertEquals(0, calls)
        assertNotNull(callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.now + 360_000))
        assertEquals(2, calls)
        assertEquals(GeneralTestFixture.end, selection.metadata.expiresAtEpochMs)
    }

    @Test fun realJcaProofIsCanonicalStandardBase64AndSignsBothTranscripts() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        val callback = AndroidGeneralJoinSigner(metadata, key) { GeneralTestFixture.now }
        val prepared = GeneralTestFixture.prepared(key.publicKeyDer)
        val carrier = GeneralTestFixture.carrier(prepared)
        val proof = callback.signGeneral(prepared, carrier, GeneralTestFixture.end)
        val json = JSONObject(proof)
        val fields = listOf("publicKey", "preparedProof", "carrierProof")
        val canonical = fields.joinToString(",", "{", "}") { "\"$it\":\"${json.getString(it)}\"" }
        assertEquals(canonical, proof)
        assertTrue(proof.toByteArray(Charsets.UTF_8).size <= 2048)
        val public = Base64.getDecoder().decode(json.getString("publicKey"))
        assertArrayEquals(key.publicKeyDer, public)
        for ((name, transcript) in listOf("preparedProof" to prepared, "carrierProof" to carrier)) {
            val encoded = json.getString(name)
            val sig = Base64.getDecoder().decode(encoded)
            assertEquals(Base64.getEncoder().encodeToString(sig), encoded)
            assertTrue(Signature.getInstance("SHA256withECDSA").apply {
                initVerify(canonicalP256PublicKey(public)); update(transcript)
            }.verify(sig))
        }
        assertFalse(callback.toString().contains("test-room"))
    }

    @Test fun wrongBindingDeadlineOrTranscriptNeverCallsInstallationKey() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        var calls = 0
        val observed = object : PersonalJoinSigningKey {
            override val installationId = key.installationId
            override val publicKeyDer: ByteArray get() = key.publicKeyDer
            override fun sign(transcript: ByteArray): ByteArray { calls++; return key.sign(transcript) }
        }
        val callback = AndroidGeneralJoinSigner(metadata, observed) { GeneralTestFixture.now }
        val prepared = GeneralTestFixture.prepared(key.publicKeyDer)
        for (bad in listOf(
            GeneralTestFixture.prepared(key.publicKeyDer, room = "other-room-00001"),
            GeneralTestFixture.prepared(key.publicKeyDer, installation = "other_installation_0001"),
            GeneralTestFixture.prepared(key.publicKeyDer, panel = "other-panel"),
            GeneralTestFixture.prepared(key.publicKeyDer, user = 802),
            GeneralTestFixture.prepared(key.publicKeyDer).let { it + byteArrayOf(0) }
        )) denied { callback.signGeneral(bad, GeneralTestFixture.carrier(bad), GeneralTestFixture.end) }
        denied { callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared, room = "other-room-00001"), GeneralTestFixture.end) }
        denied { callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.end + 1) }
        denied { callback.signGeneral(prepared, ByteArray(16_385), GeneralTestFixture.end) }
        assertEquals(0, calls)
        denied { AndroidGeneralJoinSigner(metadata, object : PersonalJoinSigningKey by key {
            override val installationId = "other_installation_0001"
        }) { GeneralTestFixture.now } }
    }

    @Test fun metadataFreshnessIsNotTheLaterServerPreparedLeaseAndLateSignatureDenies() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata(GeneralTestFixture.now + 1000).toByteArray())!!
        var now = GeneralTestFixture.now
        val callback = AndroidGeneralJoinSigner(metadata, key) { now }
        now += 2000 // Initial metadata expired; a fresh server prepared proof still owns its own finite end.
        val prepared = GeneralTestFixture.prepared(key.publicKeyDer)
        assertNotNull(callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.end))
        val slow = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray { now = GeneralTestFixture.end; return key.sign(transcript) }
        }
        now = GeneralTestFixture.now
        val late = AndroidGeneralJoinSigner(metadata, slow) { now }
        denied { late.signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.end) }
        val expired = AndroidGeneralJoinSigner(metadata, key) { GeneralTestFixture.now }
        denied { expired.signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.now) }
    }

    @Test fun canonicalPreparedCountryFingerprintAndDamagedKeyOutputDeny() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        var calls = 0
        val observed = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray { calls++; return key.sign(transcript) }
        }
        val callback = AndroidGeneralJoinSigner(metadata, observed) { GeneralTestFixture.now }
        val prepared = GeneralTestFixture.prepared(key.publicKeyDer)
        fun edit(body: String): ByteArray = "Apollo.RGA/general-prepared-proof/v1\u0000".toByteArray() +
            ByteBuffer.allocate(4).putInt(body.toByteArray().size).array() + body.toByteArray()
        val body = prepared.copyOfRange("Apollo.RGA/general-prepared-proof/v1\u0000".toByteArray().size + 4, prepared.size).toString(Charsets.UTF_8)
        for (bad in listOf(
            edit(body.replace("\"country\":\"NL\"", "\"country\":\"DE\"")),
            edit(body.replace("\"revision\":7", "\"revision\":7,\"revision\":8")),
            edit(body.dropLast(1) + ",\"allow\":true}"),
            edit(body.replace("\"user\":801", "\"user\":801.0")),
            edit(body.replace("\"fingerprint\":\"", "\"fingerprint\":\"0")),
            edit(" " + body)
        )) denied { callback.signGeneral(bad, GeneralTestFixture.carrier(bad), GeneralTestFixture.end) }
        denied { callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared) + byteArrayOf(0), GeneralTestFixture.end) }
        assertEquals(0, calls)
        val broken = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray) = ByteArray(70)
        }
        denied { AndroidGeneralJoinSigner(metadata, broken) { GeneralTestFixture.now }
            .signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.end) }
        val leaking = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray = throw IllegalStateException("test-room private detail")
        }
        denied { AndroidGeneralJoinSigner(metadata, leaking) { GeneralTestFixture.now }
            .signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.end) }
    }

    @Test fun publicVectorContainsOnlyIndependentJcaPublicProofMaterial() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        val prepared = GeneralTestFixture.prepared(key.publicKeyDer)
        val carrier = GeneralTestFixture.carrier(prepared)
        val proof = AndroidGeneralJoinSigner(metadata, key) { GeneralTestFixture.now }
            .signGeneral(prepared, carrier, GeneralTestFixture.end)
        val public = JSONObject(proof)
        for ((field, bytes) in listOf("preparedProof" to prepared, "carrierProof" to carrier)) {
            assertTrue(Signature.getInstance("SHA256withECDSA").apply {
                initVerify(canonicalP256PublicKey(key.publicKeyDer)); update(bytes)
            }.verify(Base64.getDecoder().decode(public.getString(field))))
        }
        println("TASK13B_PUBLIC_VECTOR=" + JSONObject().put("schema", "rga.task13b.public-jca-vector/1")
            .put("synthetic", true).put("nowUnixMillis", GeneralTestFixture.now).put("notAfterUnixMillis", GeneralTestFixture.end)
            .put("panel", "rga-main").put("user", 801).put("installation", key.installationId)
            .put("room", GeneralTestFixture.room).put("country", "NL")
            .put("prepared", Base64.getEncoder().encodeToString(prepared))
            .put("carrier", Base64.getEncoder().encodeToString(carrier)).put("proof", public).toString())
    }

    @Test fun subMillisecondPolicyEndCannotBeRoundedIntoAnAllow() {
        val key = GeneralTestFixture.signer()
        val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralTestFixture.metadata().toByteArray())!!
        var calls = 0
        val observed = object : PersonalJoinSigningKey by key {
            override fun sign(transcript: ByteArray): ByteArray { calls++; return key.sign(transcript) }
        }
        val callback = AndroidGeneralJoinSigner(metadata, observed) { GeneralTestFixture.now }
        val original = GeneralTestFixture.prepared(key.publicKeyDer)
        val prefix = "Apollo.RGA/general-prepared-proof/v1\u0000".toByteArray()
        val body = original.copyOfRange(prefix.size + 4, original.size).toString(Charsets.UTF_8)
            .replace("\"notAfter\":\"2026-05-28T20:27:40Z\"", "\"notAfter\":\"2026-05-28T20:27:40.000002Z\"")
            .replace("\"policyExpiry\":\"2026-05-28T21:00:00Z\"", "\"policyExpiry\":\"2026-05-28T20:27:40.000001Z\"")
        val raw = body.toByteArray()
        val prepared = prefix + ByteBuffer.allocate(4).putInt(raw.size).array() + raw
        denied { callback.signGeneral(prepared, GeneralTestFixture.carrier(prepared), GeneralTestFixture.end) }
        assertEquals(0, calls)
    }

    private fun denied(action: () -> Any?) {
        try { action(); fail("Unbound GENERAL signature accepted") }
        catch (e: IllegalArgumentException) { assertFalse(e.message.orEmpty().contains("test-room")) }
        catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("test-room")) }
    }
}

internal object GeneralTestFixture {
    const val now = 1_780_000_000_000L // 2026-05-28T20:26:40Z; synthetic time only.
    const val end = now + 60_000
    const val installation = "test_installation_general_01"
    const val room = "test-room-general-0001"
    const val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    const val link = "https://pan.gate-altas.tech/TestToken_123"
    fun metadata(expiry: Long = end) = "{\"schemaVersion\":1,\"panelInstance\":\"rga-main\",\"userId\":801,\"installationId\":\"$installation\",\"provider\":\"telemost\",\"transport\":\"vp8channel\",\"roomId\":\"$room\",\"country\":\"NL\",\"roomKey\":\"$key\",\"expiresAtEpochMs\":$expiry,\"policyRevision\":7}"
    fun challenge() = "{\"challengeId\":\"test_challenge_general_01\",\"panelInstance\":\"rga-main\",\"nonce\":\"${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })}\",\"expiresAt\":\"2026-05-28T20:27:40Z\"}"
    fun response(body: String) = GeneralBootstrapHttpResponse(200, "application/json", body.toByteArray(), "no-store")
    fun signer(): PersonalJoinSigningKey {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return object : PersonalJoinSigningKey {
            override val installationId = installation
            override val publicKeyDer: ByteArray get() = pair.public.encoded.copyOf()
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(transcript) }.sign()
        }
    }
    fun prepared(der: ByteArray, room: String = this.room, installation: String = this.installation, panel: String = "rga-main", user: Long = 801): ByteArray {
        val fp = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it.toInt() and 255) }
        val target = "{\"nodeId\":\"ru-test\",\"panelId\":\"$panel\",\"subjectId\":\"$user\",\"scopeTag\":\"vless-test\",\"scopeDigest\":\"${"a".repeat(64)}\",\"credentialDigest\":\"${"b".repeat(64)}\",\"capability\":{\"nodeVersion\":\"2.9.0\",\"nodeSource\":\"test\",\"sdkVersion\":\"test\",\"sdkSource\":\"test\",\"coreVersion\":\"test\",\"coreSource\":\"test\",\"patchDigest\":\"test\",\"executableDigest\":\"test\",\"schemaDigest\":\"test\",\"mode\":\"test\"}}"
        val json = "{\"panel\":\"$panel\",\"user\":$user,\"installation\":\"$installation\",\"fingerprint\":\"$fp\",\"challenge\":\"${"c".repeat(32)}\",\"session\":\"${"d".repeat(32)}\",\"nonce\":\"${"e".repeat(64)}\",\"target\":$target,\"launch\":{\"coreBootId\":\"${"f".repeat(32)}\",\"expectedSocket\":\"test.sock\",\"revision\":\"7\"},\"owner\":\"test-owner\",\"commandDigest\":\"${"1".repeat(64)}\",\"level\":0,\"nativeEndpoint\":\"127.0.0.1:12345\",\"revision\":7,\"room\":\"$room\",\"country\":\"NL\",\"destinationPolicy\":\"${"2".repeat(64)}\",\"notAfter\":\"2026-05-28T20:27:40Z\",\"policyExpiry\":\"2026-05-28T21:00:00Z\",\"limit\":1000000,\"used\":100,\"strategy\":\"NO_RESET\",\"createdAt\":\"2026-05-01T00:00:00Z\",\"resetAt\":\"\",\"resetPresent\":false,\"budgetIntent\":\"stock-common-delayed-cutoff\"}"
        val bytes = json.toByteArray(Charsets.UTF_8)
        return "Apollo.RGA/general-prepared-proof/v1\u0000".toByteArray() + ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes
    }
    // Independent fixture construction from the selected Go six-field uint32-BE grammar.
    fun carrier(prepared: ByteArray, room: String = this.room): ByteArray = ByteArrayOutputStream().apply {
        write("Apollo.RGA/general-carrier-join/v1\u0000".toByteArray())
        for (field in listOf(prepared, "3".repeat(32).toByteArray(), "4".repeat(64).toByteArray(), installation.toByteArray(), room.toByteArray(), "NL".toByteArray())) {
            write(ByteBuffer.allocate(4).putInt(field.size).array()); write(field)
        }
    }.toByteArray()
}
