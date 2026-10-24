package org.olcbox.app.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** An AndroidKeyStore AES key; only opaque ciphertext is written to private no-backup storage. */
internal class AndroidKeystoreBootstrapAesKey {
    fun loadOrCreate(): SecretKey = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            return@synchronized store.getKey(ALIAS, null) as? SecretKey
                ?: throw IllegalStateException("Personal bootstrap key unavailable")
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "org.olcbox.app.olcrtc_personal_bootstrap_aes_v1"
        val lock = Any()
    }
}

/**
 * One last-known, server-verified room bootstrap. This is neither a grant nor transport readiness.
 * The exact imported URL is never persisted; its bytes and the installation P-256 key bind AES-GCM AAD.
 */
internal class AndroidPersonalBootstrapCache(
    private val context: Context,
    private val file: AtomicFile,
    private val installationIdentity: () -> PersonalJoinSigningKey,
    private val encryptionKey: () -> SecretKey,
    private val runtimePolicy: () -> RuntimeLocationPolicy,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    /** Future HTTP producer must finish the same-link PoP and authenticate the Bridge response. */
    suspend fun prefetch(
        importedSubscriptionUrl: String,
        producer: VerifiedPersonalBootstrapProducer
    ): Boolean {
        if (!mayUseCache() || !validImportedLink(importedSubscriptionUrl)) return false
        val installation = safeInstallation() ?: return false
        val bootstrap = try {
            producer.completeLinkAndPrefetch(importedSubscriptionUrl, installation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        val now = nowEpochMs()
        if (!bootstrap.isUsableAt(now)) return false
        val aad = bindingAad(importedSubscriptionUrl, installation)
        return synchronized(fileLock) {
            try {
                val payload = JSONObject()
                    .put("cacheVersion", 1)
                    .put("cachedAtEpochMs", now)
                    .put("bootstrap", bootstrap.toPrivateJson())
                    .toString().toByteArray(StandardCharsets.UTF_8)
                if (payload.size > MAX_PLAINTEXT_BYTES) return@synchronized false
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                val iv = cipher.iv
                if (iv.size != IV_BYTES) return@synchronized false
                cipher.updateAAD(aad)
                val ciphertext = cipher.doFinal(payload)
                if (ciphertext.size < TAG_BYTES || ciphertext.size > MAX_CIPHERTEXT_BYTES) {
                    return@synchronized false
                }
                val blob = MAGIC + iv + ciphertext
                writeAtomically(blob)
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Null means unusable for any reason; callers must still obtain a fresh server join grant. */
    fun read(importedSubscriptionUrl: String): PersonalBootstrapV1? {
        if (!mayUseCache() || !validImportedLink(importedSubscriptionUrl)) return null
        val installation = safeInstallation() ?: return null
        val aad = bindingAad(importedSubscriptionUrl, installation)
        return synchronized(fileLock) {
            try {
                val blob = readBounded() ?: return@synchronized null
                if (blob.size < MAGIC.size + IV_BYTES + TAG_BYTES ||
                    !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return@synchronized null
                val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
                val ciphertext = blob.copyOfRange(MAGIC.size + IV_BYTES, blob.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
                cipher.updateAAD(aad)
                val plaintext = cipher.doFinal(ciphertext)
                if (plaintext.size > MAX_PLAINTEXT_BYTES) return@synchronized null
                val decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext)).toString()
                val envelope = JSONObject(decoded)
                if (envelope.keys().asSequence().toSet() != ENVELOPE_FIELDS ||
                    envelope.opt("cacheVersion").strictLong() != 1L) return@synchronized null
                val cachedAt = envelope.opt("cachedAtEpochMs").strictLong()
                    ?: return@synchronized null
                val now = nowEpochMs()
                if (cachedAt <= 0 || now < cachedAt) return@synchronized null
                val bootstrap = PersonalBootstrapV1.fromPrivateJson(
                    envelope.opt("bootstrap") as? JSONObject ?: return@synchronized null
                ) ?: return@synchronized null
                bootstrap.takeIf { it.isUsableAt(now) }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Explicit removal on unlink/replacement; no subscription JSON or selected host is changed. */
    fun clear() = synchronized(fileLock) { file.delete() }

    private fun mayUseCache(): Boolean = try {
        context.packageName == PERSONAL_PACKAGE &&
            runtimePolicy() == RuntimeLocationPolicy.ExperimentalDebug
    } catch (_: Exception) {
        false
    }

    private fun safeInstallation(): PersonalJoinSigningKey? = try {
        installationIdentity().also {
            require(INSTALLATION_ID.matches(it.installationId))
            canonicalP256PublicKey(it.publicKeyDer)
        }
    } catch (_: Exception) {
        null
    }

    private fun bindingAad(url: String, installation: PersonalJoinSigningKey): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { framed ->
                for (field in arrayOf(
                    DOMAIN.toByteArray(StandardCharsets.US_ASCII),
                    context.packageName.toByteArray(StandardCharsets.UTF_8),
                    installation.installationId.toByteArray(StandardCharsets.US_ASCII),
                    installation.publicKeyDer,
                    url.toByteArray(StandardCharsets.UTF_8)
                )) {
                    framed.writeInt(field.size)
                    framed.write(field)
                }
            }
        }.toByteArray()

    private fun writeAtomically(blob: ByteArray): Boolean {
        var stream: FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(blob)
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let { failed -> try { file.failWrite(failed) } catch (_: Exception) { } }
            false
        }
    }

    private fun readBounded(): ByteArray? = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (output.size() + count > MAX_BLOB_BYTES) return@use null
            output.write(chunk, 0, count)
        }
        output.toByteArray()
    }

    private fun validImportedLink(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_CHARS ||
            url.any { it.code < 0x20 || it.code == 0x7f }) return false
        return try {
            val parsed = URI(url)
            parsed.scheme == "https" && !parsed.isOpaque && !parsed.host.isNullOrEmpty() &&
                parsed.rawUserInfo == null
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val PERSONAL_PACKAGE = "tech.gatealtas.rga.personal"
        private const val DOMAIN = "Apollo.RGA/personal-bootstrap-cache/v1"
        private const val FILE_NAME = "olcrtc_personal_bootstrap_v1.enc"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_URL_CHARS = 16_384
        private const val MAX_PLAINTEXT_BYTES = 8_192
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES
        private const val MAX_BLOB_BYTES = 4 + IV_BYTES + MAX_CIPHERTEXT_BYTES
        private val MAGIC = byteArrayOf('A'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), 1)
        private val ENVELOPE_FIELDS = setOf("cacheVersion", "cachedAtEpochMs", "bootstrap")
        private val INSTALLATION_ID = Regex("[A-Za-z0-9_-]{16,128}")
        private val fileLock = Any()

        fun forApp(context: Context): AndroidPersonalBootstrapCache {
            val app = context.applicationContext
            val aesKey = AndroidKeystoreBootstrapAesKey()
            val identity = AndroidKeystoreInstallationIdentity.forApp(app)
            return AndroidPersonalBootstrapCache(
                context = app,
                file = AtomicFile(File(app.noBackupFilesDir, FILE_NAME)),
                installationIdentity = identity::loadOrCreate,
                encryptionKey = aesKey::loadOrCreate,
                runtimePolicy = { AndroidRuntimeLocationPolicy.forApp(app) }
            )
        }
    }
}
