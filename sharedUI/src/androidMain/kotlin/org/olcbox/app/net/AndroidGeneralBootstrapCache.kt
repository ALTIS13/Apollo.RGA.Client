package org.olcbox.app.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.olcbox.app.vpn.OlcChannelRole

/** Separate GENERAL key; reads never create a replacement for a lost key. */
private class GeneralCacheAesKey {
    fun read(): SecretKey? = synchronized(lock) { store().getKey(ALIAS, null) as? SecretKey }
    fun write(): SecretKey = synchronized(lock) {
        val store = store()
        if (store.containsAlias(ALIAS)) return@synchronized store.getKey(ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("GENERAL cache denied [redacted]")
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
        }.generateKey()
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private companion object {
        const val ALIAS = "org.olcbox.app.olcrtc_general_bootstrap_aes_v1"
        val lock = Any()
    }
}

/** Encrypted local room discovery, NOT permission to join or a renewed metadata lease. */
internal class AndroidGeneralBootstrapCache(
    private val context: Context,
    private val file: AtomicFile,
    private val installationIdentity: () -> PersonalJoinSigningKey,
    private val encryptionKeyForWrite: () -> SecretKey,
    private val encryptionKeyForRead: () -> SecretKey?,
    private val channel: OlcChannelRole = OlcChannelRole.Primary,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private var lastObservedEpochMs = 0L

    suspend fun prefetch(link: String, producer: AndroidVerifiedGeneralBootstrapProducer): Boolean {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        if (!eligible(link)) return false
        return try {
            observeGeneralBootstrap(GeneralBootstrapDiagnostic.Identity)
            val key = installationIdentity()
            val id = key.installationId; val der = key.publicKeyDer.copyOf()
            canonicalP256PublicKey(der)
            val metadata = producer.completeLinkAndPrefetch(link, key) ?: return false
            if (!matchesChannel(metadata)) return false
            callerContext.ensureActive()
            val receipt = nowEpochMs()
            val selection = GeneralBootstrapSelection.fromVerifiedReceipt(metadata, key, receipt, receipt) ?: return false
            if (key.installationId != id || !key.publicKeyDer.contentEquals(der) ||
                !selection.matchesKeyAndIsUsableAt(installationIdentity(), receipt)) return false
            synchronized(fileLock) {
                if (!eligible(link) || receipt < lastObservedEpochMs) return@synchronized false
                val plaintext = GeneralJson.canonical(linkedMapOf("cacheVersion" to 1L,
                    "receivedAtEpochMs" to receipt, "metadata" to metadataMap(metadata))).toByteArray(Charsets.UTF_8)
                if (plaintext.size > MAX_PLAINTEXT) return@synchronized false
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, encryptionKeyForWrite()); updateAAD(aad(link, id, der))
                }
                if (cipher.iv.size != 12) return@synchronized false
                val blob = MAGIC + cipher.iv + cipher.doFinal(plaintext)
                val finalNow = nowEpochMs()
                if (finalNow < receipt || finalNow < lastObservedEpochMs || blob.size > MAX_BLOB ||
                    !selection.matchesKeyAndIsUsableAt(installationIdentity(), finalNow))
                    return@synchronized false
                callerContext.ensureActive()
                writeAtomically(blob).also { if (it) lastObservedEpochMs = finalNow }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { observeGeneralBootstrap(GeneralBootstrapDiagnostic.CacheDenied); false }
    }

    fun read(link: String): GeneralBootstrapSelection? {
        if (!eligible(link)) return null
        return synchronized(fileLock) {
            try {
                val key = installationIdentity(); val id = key.installationId; val der = key.publicKeyDer.copyOf()
                canonicalP256PublicKey(der)
                val now = nowEpochMs()
                if (now < lastObservedEpochMs) return@synchronized null
                val blob = readBounded() ?: return@synchronized null
                if (blob.size < 32 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) return@synchronized null
                val aes = encryptionKeyForRead() ?: return@synchronized null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, blob.copyOfRange(4, 16)))
                    updateAAD(aad(link, id, der))
                }
                val envelope = GeneralJson.parse(cipher.doFinal(blob.copyOfRange(16, blob.size)), MAX_PLAINTEXT)
                if (envelope.keys != ENVELOPE_FIELDS || GeneralJson.long(envelope, "cacheVersion") != 1L) return@synchronized null
                val receipt = GeneralJson.long(envelope, "receivedAtEpochMs")
                val original = GeneralBootstrapV1.fromPrivateBytes(GeneralJson.canonical(
                    GeneralJson.objectValue(envelope, "metadata")).toByteArray(Charsets.UTF_8)) ?: return@synchronized null
                if (!matchesChannel(original)) return@synchronized null
                val selection = GeneralBootstrapSelection.fromVerifiedReceipt(original, key, receipt, now) ?: return@synchronized null
                val finalNow = nowEpochMs()
                if (finalNow < now || finalNow < lastObservedEpochMs || key.installationId != id ||
                    !key.publicKeyDer.contentEquals(der) ||
                    !selection.matchesKeyAndIsUsableAt(installationIdentity(), finalNow)) return@synchronized null
                lastObservedEpochMs = finalNow
                selection
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    /** Explicit unlink only; this file and no personal/subscription data are removed. */
    fun clear() = synchronized(fileLock) { file.delete(); lastObservedEpochMs = 0 }
    override fun toString() = "AndroidGeneralBootstrapCache([redacted])"

    private fun matchesChannel(metadata: GeneralBootstrapV1): Boolean = metadata.matchesGeneralChannel(channel)

    private fun eligible(link: String): Boolean = try {
        context.packageName == PUBLIC_PACKAGE && file.baseFile.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile &&
            RemnawavePersonalBearer.parse(link) != null
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun aad(link: String, id: String, der: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { framed ->
            val domain = if (channel == OlcChannelRole.Reserve) "$DOMAIN/reserve" else DOMAIN
            for (field in listOf(domain.toByteArray(Charsets.US_ASCII), context.packageName.toByteArray(Charsets.UTF_8),
                ORIGIN.toByteArray(Charsets.US_ASCII), id.toByteArray(Charsets.US_ASCII), der.copyOf(), link.toByteArray(Charsets.UTF_8))) {
                framed.writeInt(field.size); framed.write(field)
            }
        }
    }.toByteArray()

    private fun readBounded(): ByteArray? = file.openRead().use { input ->
        val output = ByteArrayOutputStream(); val chunk = ByteArray(1024)
        while (true) {
            val count = input.read(chunk); if (count < 0) break
            if (output.size() + count > MAX_BLOB) return@use null
            output.write(chunk, 0, count)
        }
        output.toByteArray()
    }
    private fun writeAtomically(blob: ByteArray): Boolean {
        var stream: FileOutputStream? = null
        return try { stream = file.startWrite(); stream.write(blob); file.finishWrite(stream); true }
        catch (cancelled: CancellationException) {
            stream?.let { try { file.failWrite(it) } catch (_: Exception) { } }; throw cancelled
        } catch (_: Exception) {
            stream?.let { try { file.failWrite(it) } catch (_: Exception) { } }; false
        }
    }
    companion object {
        private const val PUBLIC_PACKAGE = "tech.gatealtas.rga"
        private const val ORIGIN = "https://rga.apollot.ru"
        private const val DOMAIN = "Apollo.RGA/general-bootstrap-cache/v1"
        private const val MAX_PLAINTEXT = 8192
        private const val MAX_BLOB = 4 + 12 + MAX_PLAINTEXT + 16
        private val MAGIC = byteArrayOf(65, 71, 66, 1)
        private val ENVELOPE_FIELDS = setOf("cacheVersion", "receivedAtEpochMs", "metadata")
        private val fileLock = Any()
        fun forApp(context: Context, channel: OlcChannelRole = OlcChannelRole.Primary): AndroidGeneralBootstrapCache? {
            if (context.packageName != PUBLIC_PACKAGE) return null
            val app = context.applicationContext
            if (app.packageName != PUBLIC_PACKAGE) return null
            val aes = GeneralCacheAesKey(); val identity = AndroidKeystoreInstallationIdentity.forApp(app)
            val name = if (channel == OlcChannelRole.Reserve) "olcrtc_general_reserve_v1.enc" else "olcrtc_general_bootstrap_v1.enc"
            return AndroidGeneralBootstrapCache(app, AtomicFile(File(app.noBackupFilesDir, name)),
                identity::loadOrCreate, aes::write, aes::read, channel)
        }
        private fun metadataMap(m: GeneralBootstrapV1): Map<String, Any?> = linkedMapOf<String, Any?>(
            "schemaVersion" to m.schemaVersion.toLong(), "panelInstance" to m.panelInstance, "userId" to m.userId,
            "installationId" to m.installationId, "provider" to m.provider, "transport" to m.transport,
            "roomId" to m.roomId, "country" to m.country, "roomKey" to m.roomKey,
            "expiresAtEpochMs" to m.expiresAtEpochMs, "policyRevision" to m.policyRevision).apply {
                if (m.schemaVersion >= 2) put("roomUrl", m.roomUrl)
                if (m.usesManagedLease) put("admissionMode", "managed-lease-v2")
            }
    }
}
