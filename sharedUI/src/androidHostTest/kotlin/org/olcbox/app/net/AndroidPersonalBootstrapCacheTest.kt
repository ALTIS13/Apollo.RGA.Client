package org.olcbox.app.net

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class AndroidPersonalBootstrapCacheTest {
    private val subscription = "https://subscription.example.test/sub/old-link-token"
    private val roomKey = "0123456789abcdef".repeat(4)
    private val personalPackage = "tech.gatealtas.rga.personal"
    private val now = 1_780_000_000_000L

    @Test fun verifiedPrefetchIsEncryptedAndReturnsOnlyForSameImportedLinkAndInstallation(): Unit = runBlocking {
        val fixture = Fixture()
        val bootstrap = fixture.bootstrap()
        var called = 0
        val producer = VerifiedPersonalBootstrapProducer { link, signingKey ->
            called++
            assertEquals(subscription, link)
            assertEquals(fixture.signingKey.installationId, signingKey.installationId)
            bootstrap
        }

        assertTrue(fixture.cache.prefetch(subscription, producer))
        assertEquals(1, called)
        val storedBytes = fixture.file.readBytes()
        assertFalse(storedBytes.toString(Charsets.UTF_8).contains(subscription))
        assertFalse(storedBytes.toString(Charsets.UTF_8).contains(roomKey))
        val loaded = assertNotNull(fixture.cache.read(subscription))
        assertEquals(1, loaded.schemaVersion)
        assertEquals("telemost", loaded.provider)
        assertEquals("vp8channel", loaded.transport)
        assertEquals("test-room-000001", loaded.roomId)
        assertEquals("NL", loaded.country)
        assertEquals(roomKey, loaded.roomKey)
        assertEquals(now + 60_000, loaded.expiresAtEpochMs)
        assertEquals(7L, loaded.policyRevision)
        assertFalse(loaded.toString().contains(roomKey))

        assertNull(fixture.cache.read("https://subscription.example.test/sub/another-token"))
        assertNotNull(fixture.cache.read(subscription))
        fixture.signingKey = signingKey("different-installation-000001")
        assertNull(fixture.cache.read(subscription))
        fixture.signingKey = signingKey("test-installation-A063-0001")
        assertNull(fixture.cache.read(subscription)) // Same ID, different P-256 public key.
    }

    @Test fun expiredOrClockRolledBackBootstrapIsUnavailable(): Unit = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.cache.prefetch(subscription) { _, _ -> fixture.bootstrap() })
        assertNotNull(fixture.cache.read(subscription))
        fixture.clock = now + 60_000
        assertNull(fixture.cache.read(subscription))

        fixture.clock = now
        assertTrue(fixture.cache.prefetch(subscription) { _, _ -> fixture.bootstrap() })
        fixture.clock = now - 1
        assertNull(fixture.cache.read(subscription))
    }

    @Test fun malformedSchemaFieldsAndCorruptCiphertextNeverProduceBootstrap(): Unit = runBlocking {
        val fixture = Fixture()
        val valid = fixture.bootstrap()
        val invalid = listOf(
            fixture.bootstrap(schemaVersion = 2),
            fixture.bootstrap(provider = "other"),
            fixture.bootstrap(transport = "other"),
            fixture.bootstrap(roomId = "https://room.example.test"),
            fixture.bootstrap(country = "nl"),
            fixture.bootstrap(roomKey = "line\nsecret"),
            fixture.bootstrap(roomKey = "A" + roomKey.drop(1)),
            fixture.bootstrap(roomKey = "0".repeat(64)),
            fixture.bootstrap(roomKey = roomKey.dropLast(1)),
            fixture.bootstrap(policyRevision = 0),
            fixture.bootstrap(expiresAtEpochMs = now)
        )
        invalid.forEach { bootstrap ->
            assertFalse(fixture.cache.prefetch(subscription) { _, _ -> bootstrap })
            assertNull(fixture.cache.read(subscription))
        }
        assertTrue(fixture.cache.prefetch(subscription) { _, _ -> valid })
        val bytes = fixture.file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        fixture.file.writeBytes(bytes)
        assertNull(fixture.cache.read(subscription))
        assertNull(fixture.cache.read(subscription))
    }

    @Test fun onlyPersonalDebugPackageMayCallVerifiedProducerOrReadCache(): Unit = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.cache.prefetch(subscription) { _, _ -> fixture.bootstrap() })
        var called = false
        fixture.packageName = "tech.gatealtas.rga.debug"
        assertNull(fixture.cache.read(subscription))
        assertFalse(fixture.cache.prefetch(subscription) { _, _ -> called = true; fixture.bootstrap() })
        assertFalse(called)
        fixture.packageName = personalPackage
        fixture.policy = RuntimeLocationPolicy.OrdinaryOnly
        assertNull(fixture.cache.read(subscription))
        assertFalse(fixture.cache.prefetch(subscription) { _, _ -> called = true; fixture.bootstrap() })
        assertFalse(called)
        fixture.policy = RuntimeLocationPolicy.ExperimentalDebug
        assertNotNull(fixture.cache.read(subscription))
    }

    @Test fun invalidLinkOrProducerFailureDoesNotReplaceLastValidBootstrap(): Unit = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.cache.prefetch(subscription) { _, _ -> fixture.bootstrap() })
        var called = false
        assertFalse(fixture.cache.prefetch("http://subscription.example.test/sub/token") { _, _ ->
            called = true
            fixture.bootstrap()
        })
        assertFalse(called)
        assertFalse(fixture.cache.prefetch(subscription) { _, _ -> throw IllegalStateException("synthetic secret") })
        assertNotNull(fixture.cache.read(subscription))
    }

    @Test fun privateJsonDecoderRejectsMissingExtraOrCoercedWireFields() {
        val valid = Fixture().bootstrap().toPrivateJson()
        assertNotNull(PersonalBootstrapV1.fromPrivateJson(valid))
        assertNull(PersonalBootstrapV1.fromPrivateJson(
            JSONObject(valid.toString()).apply { remove("roomKey") }
        ))
        assertNull(PersonalBootstrapV1.fromPrivateJson(
            JSONObject(valid.toString()).put("subscriptionUrl", subscription)
        ))
        assertNull(PersonalBootstrapV1.fromPrivateJson(
            JSONObject(valid.toString()).put("schemaVersion", "1")
        ))
        assertNull(PersonalBootstrapV1.fromPrivateJson(
            JSONObject(valid.toString()).put("policyRevision", 7.5)
        ))
    }

    @Test fun lostEncryptionKeyFailsClosedAndExplicitClearRemovesCiphertext(): Unit = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.cache.prefetch(subscription) { _, _ -> fixture.bootstrap() })
        val originalKey = fixture.key
        fixture.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(fixture.cache.read(subscription))
        fixture.key = originalKey
        assertNotNull(fixture.cache.read(subscription))
        fixture.cache.clear()
        assertNull(fixture.cache.read(subscription))
        assertFalse(fixture.file.exists())
    }

    private inner class Fixture {
        var clock = now
        var packageName = personalPackage
        var policy = RuntimeLocationPolicy.ExperimentalDebug
        var signingKey: PersonalJoinSigningKey = signingKey("test-installation-A063-0001")
        var key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val file = File(RuntimeEnvironment.getApplication().noBackupFilesDir,
            "bootstrap_cache_test_${System.nanoTime()}")
        private val context: Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName(): String = this@Fixture.packageName
        }
        val cache = AndroidPersonalBootstrapCache(
            context = context,
            file = AtomicFile(file),
            installationIdentity = { signingKey },
            encryptionKey = { key },
            runtimePolicy = { policy },
            nowEpochMs = { clock }
        )

        fun bootstrap(
            schemaVersion: Int = 1,
            provider: String = "telemost",
            transport: String = "vp8channel",
            roomId: String = "test-room-000001",
            country: String = "NL",
            roomKey: String = this@AndroidPersonalBootstrapCacheTest.roomKey,
            expiresAtEpochMs: Long = now + 60_000,
            policyRevision: Long = 7
        ) = PersonalBootstrapV1(schemaVersion, provider, transport, roomId, country,
            roomKey, expiresAtEpochMs, policyRevision)
    }

    private fun signingKey(id: String): PersonalJoinSigningKey {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return object : PersonalJoinSigningKey {
            override val installationId = id
            override val publicKeyDer: ByteArray = pair.public.encoded
            override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
                .apply { initSign(pair.private); update(transcript) }.sign()
        }
    }
}
