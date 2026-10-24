package org.olcbox.app.net

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidGeneralBootstrapCacheTest {
    @Test fun vkReserveKeepsOriginalEncryptedReceiptAndCannotBecomePrimary(): Unit = runBlocking {
        val f = Fixture()
        val file = File(f.file.parentFile, "vk-reserve-${System.nanoTime()}.enc")
        val reserve = AndroidGeneralBootstrapCache(f.context, AtomicFile(file), { f.key }, { f.aes }, { f.aes },
            channel = org.olcbox.app.vpn.OlcChannelRole.Reserve) { f.now }
        val vk = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"vkcalls\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", "https://vk.ru/call/join/fixture-opaque_123")
        val producer = AndroidVerifiedGeneralBootstrapProducer.managedReserve(GeneralBootstrapHttpTransport { path, _ ->
            GeneralTestFixture.response(if (path.endsWith("/link/begin")) GeneralTestFixture.challenge() else vk)
        }) { f.now }
        assertTrue(reserve.prefetch(GeneralTestFixture.link, producer))
        val first = reserve.read(GeneralTestFixture.link)!!
        assertEquals("vkcalls", first.metadata.provider)
        assertFalse(f.cache.prefetch(GeneralTestFixture.link, producer))
        f.now += 300_001
        assertEquals(first.receivedAtEpochMs, reserve.read(GeneralTestFixture.link)!!.receivedAtEpochMs)
        reserve.clear()
    }
    @Test fun reserveReceiptCannotReplaceOrDecryptAsPrimaryDiscovery(): Unit = runBlocking {
        val f = Fixture()
        assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
        val primary = f.file.readBytes()
        val reserveFile = File(f.file.parentFile, "reserve-${System.nanoTime()}.enc")
        val reserve = AndroidGeneralBootstrapCache(f.context, AtomicFile(reserveFile), { f.key }, { f.aes }, { f.aes },
            channel = org.olcbox.app.vpn.OlcChannelRole.Reserve) { f.now }
        reserveFile.writeBytes(primary)
        assertNull("A copied primary receipt is not reserve discovery", reserve.read(GeneralTestFixture.link))
        val jazz = managedMetadata().replace("\"provider\":\"telemost\"", "\"provider\":\"salutejazz\"")
            .replace("\"transport\":\"vp8channel\"", "\"transport\":\"datachannel\"")
            .replace("https://telemost.yandex.ru/j/12345678901234", "abc123:abcd1234")
        fun producer() = AndroidVerifiedGeneralBootstrapProducer.managedReserve(GeneralBootstrapHttpTransport { path, _ ->
            GeneralTestFixture.response(if (path.endsWith("/link/begin")) GeneralTestFixture.challenge() else jazz)
        }) { f.now }
        assertFalse(f.cache.prefetch(GeneralTestFixture.link, producer()))
        assertArrayEquals(primary, f.file.readBytes())
        assertTrue(reserve.prefetch(GeneralTestFixture.link, producer()))
        assertEquals("salutejazz", reserve.read(GeneralTestFixture.link)!!.metadata.provider)
        f.file.writeBytes(reserveFile.readBytes())
        assertNull("A copied reserve receipt is not primary discovery", f.newCache().read(GeneralTestFixture.link))
        reserve.clear()
    }
    @Test fun finalObservedClockFencesRollbackWithoutRenewingEncryptedReceipt(): Unit = runBlocking {
        for (prefetchFirst in listOf(true, false)) {
            val f = Fixture()
            assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
            val sequence = listOf(GeneralTestFixture.now, GeneralTestFixture.now + 1000, GeneralTestFixture.now + 500)
            var reads = 0
            val observed = AndroidGeneralBootstrapCache(f.context, AtomicFile(f.file), { f.key }, { f.aes }, { f.aes }) {
                sequence[minOf(reads++, 2)]
            }
            if (prefetchFirst) assertTrue(observed.prefetch(GeneralTestFixture.link, f.producer()))
            else assertEquals(GeneralTestFixture.now, observed.read(GeneralTestFixture.link)!!.receivedAtEpochMs)
            assertNull(observed.read(GeneralTestFixture.link))
            assertEquals(GeneralTestFixture.now, f.newCache().read(GeneralTestFixture.link)!!.receivedAtEpochMs)
        }
    }

    @Test fun authenticatedRestoreKeepsOriginalExpiryAndReceiptAcrossColdStart(): Unit = runBlocking {
        val f = Fixture()
        assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
        val blob = f.file.readBytes()
        assertFalse(blob.toString(Charsets.ISO_8859_1).contains(GeneralTestFixture.room))
        assertFalse(blob.toString(Charsets.ISO_8859_1).contains(GeneralTestFixture.link))
        assertEquals(f.context.noBackupFilesDir.canonicalFile, f.file.parentFile!!.canonicalFile)
        f.now += 300_001
        val restored = f.newCache().read(GeneralTestFixture.link)!!
        assertEquals(GeneralTestFixture.end, restored.metadata.expiresAtEpochMs)
        assertEquals(GeneralTestFixture.now, restored.receivedAtEpochMs)
        assertFalse(restored.metadata.isUsableAt(f.now))
        assertTrue(restored.matchesKeyAndIsUsableAt(f.key, f.now))
        f.now = GeneralTestFixture.now + 86_399_999
        assertNotNull(f.newCache().read(GeneralTestFixture.link))
        f.now++
        assertNull(f.newCache().read(GeneralTestFixture.link))
        f.now = GeneralTestFixture.now - 1
        assertNull(f.newCache().read(GeneralTestFixture.link))
    }

    @Test fun exactLinkPackageInstallationAndCanonicalKeyBindCiphertext(): Unit = runBlocking {
        val f = Fixture(); assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
        assertNull(f.cache.read("https://pan.gate-altas.tech/DifferentToken_123"))
        val original = f.key
        f.key = GeneralTestFixture.signer(); assertNull(f.cache.read(GeneralTestFixture.link))
        f.key = object : PersonalJoinSigningKey by original { override val installationId = "different_installation_01" }
        assertNull(f.cache.read(GeneralTestFixture.link))
        f.key = original; f.packageName = "tech.gatealtas.rga.personal"
        assertNull(f.cache.read(GeneralTestFixture.link))
        assertNull(AndroidGeneralBootstrapCache.forApp(f.context))
        f.packageName = "tech.gatealtas.rga"
        f.readKey = null; assertNull(f.cache.read(GeneralTestFixture.link))
        f.readKey = f.aes
        val blob = f.file.readBytes(); blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        f.file.writeBytes(blob); assertNull(f.cache.read(GeneralTestFixture.link))
    }

    @Test fun duplicateOversizeFutureReceiptAndNeverFreshEnvelopeDeny(): Unit = runBlocking {
        val f = Fixture(); assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
        val metadata = GeneralTestFixture.metadata()
        for (payload in listOf(
            "{\"cacheVersion\":1,\"cacheVersion\":1,\"receivedAtEpochMs\":${f.now},\"metadata\":$metadata}",
            "{\"cacheVersion\":1,\"receivedAtEpochMs\":${f.now + 1},\"metadata\":$metadata}",
            "{\"cacheVersion\":1,\"receivedAtEpochMs\":${GeneralTestFixture.end},\"metadata\":$metadata}",
            "{\"cacheVersion\":1,\"receivedAtEpochMs\":${f.now - 300_001},\"metadata\":$metadata}",
            "{\"cacheVersion\":1,\"receivedAtEpochMs\":${f.now},\"metadata\":${metadata.dropLast(1)},\"roomId\":\"other-room\"}}"
        )) { f.writeEnvelope(payload); assertNull(f.newCache().read(GeneralTestFixture.link)) }
        f.file.writeBytes(ByteArray(8_229)); assertNull(f.cache.read(GeneralTestFixture.link))
        f.writeEnvelope(" ".repeat(8_193)); assertNull(f.cache.read(GeneralTestFixture.link))
    }

    @Test fun failedOrCancelledProducerPreservesOldAuthenticatedSelection(): Unit = runBlocking {
        val f = Fixture(); assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
        val before = f.file.readBytes()
        val failed = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ -> null }) { f.now }
        assertFalse(f.cache.prefetch(GeneralTestFixture.link, failed))
        assertArrayEquals(before, f.file.readBytes())
        val cancelled = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ -> throw CancellationException("private detail") }) { f.now }
        try { f.cache.prefetch(GeneralTestFixture.link, cancelled); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
        assertArrayEquals(before, f.file.readBytes())
        val rotate = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { path, _ ->
            if (path.endsWith("begin")) GeneralTestFixture.response(GeneralTestFixture.challenge())
            else { f.key = GeneralTestFixture.signer(); GeneralTestFixture.response(GeneralTestFixture.metadata()) }
        }) { f.now }
        assertFalse(f.cache.prefetch(GeneralTestFixture.link, rotate))
        assertArrayEquals(before, f.file.readBytes())
    }

    @Test fun clearIsExplicitAndOnlyTheOwnedGeneralFileIsRemoved(): Unit = runBlocking {
        val f = Fixture(); val other = File(f.file.parentFile, "personal-control-${System.nanoTime()}.enc")
        other.writeBytes(byteArrayOf(7)); assertTrue(f.cache.prefetch(GeneralTestFixture.link, f.producer()))
        f.cache.clear(); assertFalse(f.file.exists()); assertArrayEquals(byteArrayOf(7), other.readBytes())
        assertEquals("AndroidGeneralBootstrapCache([redacted])", f.cache.toString())
        other.delete()
    }

    @Test fun publicConstructorIsInertAndStorageOutsideNoBackupIsDenied(): Unit = runBlocking {
        val f = Fixture()
        assertNotNull(AndroidGeneralBootstrapCache.forApp(f.context))
        f.packageName = "tech.gatealtas.rga.debug"
        assertNull(AndroidGeneralBootstrapCache.forApp(f.context))
        f.packageName = "tech.gatealtas.rga"
        var calls = 0
        val producer = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { _, _ -> calls++; null }) { f.now }
        val outside = AndroidGeneralBootstrapCache(f.context, AtomicFile(File(f.context.filesDir, "general-outside-test.enc")),
            { f.key }, { f.aes }, { f.aes }) { f.now }
        assertFalse(outside.prefetch(GeneralTestFixture.link, producer)); assertEquals(0, calls)
    }

    private class Fixture {
        var now = GeneralTestFixture.now
        var key = GeneralTestFixture.signer()
        var packageName = "tech.gatealtas.rga"
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName(): String = this@Fixture.packageName
            override fun getApplicationContext(): Context = this
        }
        val file = File(context.noBackupFilesDir, "general-test-${System.nanoTime()}.enc")
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var readKey: SecretKey? = aes
        val cache = newCache()
        fun newCache() = AndroidGeneralBootstrapCache(context, AtomicFile(file), { key }, { aes }, { readKey }) { now }
        fun producer() = AndroidVerifiedGeneralBootstrapProducer(GeneralBootstrapHttpTransport { path, _ ->
            GeneralTestFixture.response(if (path.endsWith("begin")) GeneralTestFixture.challenge() else GeneralTestFixture.metadata())
        }) { now }
        fun writeEnvelope(payload: String) {
            // Independent framed AAD construction; the URL itself is never in plaintext.
            val aad = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { framed ->
                for (field in listOf("Apollo.RGA/general-bootstrap-cache/v1".toByteArray(), packageName.toByteArray(),
                    "https://rga.apollot.ru".toByteArray(), key.installationId.toByteArray(), key.publicKeyDer,
                    GeneralTestFixture.link.toByteArray())) { framed.writeInt(field.size); framed.write(field) }
            } }.toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes); updateAAD(aad) }
            file.writeBytes(byteArrayOf(65, 71, 66, 1) + cipher.iv + cipher.doFinal(payload.toByteArray()))
        }
    }
}
