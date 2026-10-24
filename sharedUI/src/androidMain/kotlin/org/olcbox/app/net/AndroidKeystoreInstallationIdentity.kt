package org.olcbox.app.net

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** AndroidKeyStore is the only production implementation: its private key is non-exportable. */
internal interface InstallationKeyMaterialStore {
    fun read(): KeyPair?
    fun create(): KeyPair
    fun delete()
}

internal class AndroidKeystoreP256MaterialStore : InstallationKeyMaterialStore {
    private val alias = "org.olcbox.app.olcrtc_personal_join_p256_v1"

    override fun read(): KeyPair? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) return null
        val privateKey = store.getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("Installation identity unavailable")
        val publicKey = store.getCertificate(alias)?.publicKey
            ?: throw IllegalStateException("Installation identity unavailable")
        return KeyPair(publicKey, privateKey)
    }

    override fun create(): KeyPair = KeyPairGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
    ).apply {
        initialize(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build())
    }.generateKeyPair()

    override fun delete() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}

/** A restored app-data ID without its Keystore key must become a new installation. */
internal class AndroidKeystoreInstallationIdentity(
    private val preferences: SharedPreferences,
    private val materialStore: InstallationKeyMaterialStore = AndroidKeystoreP256MaterialStore(),
    private val randomBytes: (ByteArray) -> Unit = SecureRandom()::nextBytes
) {
    fun loadOrCreate(): PersonalJoinSigningKey = synchronized(lock) {
        try {
            loadOrCreateLocked()
        } catch (_: Exception) {
            throw IllegalStateException("Installation identity unavailable")
        }
    }

    private fun loadOrCreateLocked(): PersonalJoinSigningKey {
        val savedId = preferences.getString(ID_KEY, null)
        val savedFingerprint = preferences.getString(FINGERPRINT_KEY, null)
        val existing = materialStore.read()
        if (existing != null && savedId != null && savedFingerprint != null &&
            INSTALLATION_ID.matches(savedId)) {
            val der = existing.public.encoded
            canonicalP256PublicKey(der)
            if (url64(sha256(der)) == savedFingerprint) return signingKey(savedId, existing)
        }

        // A mismatched key or missing ID must not retain an installation binding.
        if (existing != null) materialStore.delete()
        val generated = materialStore.create()
        val publicDer = generated.public.encoded
        canonicalP256PublicKey(publicDer)
        val opaqueId = ByteArray(32).also(randomBytes)
        require(opaqueId.any { it != 0.toByte() })
        val newId = url64(opaqueId)
        if (!preferences.edit().putString(ID_KEY, newId)
                .putString(FINGERPRINT_KEY, url64(sha256(publicDer))).commit()) {
            materialStore.delete()
            throw IllegalStateException("Installation identity unavailable")
        }
        return signingKey(newId, generated)
    }

    private fun signingKey(id: String, pair: KeyPair): PersonalJoinSigningKey = object : PersonalJoinSigningKey {
        override val installationId: String = id
        private val canonicalDer = pair.public.encoded.copyOf()
        override val publicKeyDer: ByteArray get() = canonicalDer.copyOf()
        override fun sign(transcript: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA")
            .apply { initSign(pair.private); update(transcript) }.sign()
    }

    companion object {
        private const val PREFERENCES = "olcrtc_personal_join_installation_v1"
        private const val ID_KEY = "installation_id"
        private const val FINGERPRINT_KEY = "public_key_sha256"
        private val INSTALLATION_ID = Regex("[A-Za-z0-9_-]{16,128}")
        private val lock = Any()

        fun forApp(context: Context): AndroidKeystoreInstallationIdentity =
            AndroidKeystoreInstallationIdentity(context.applicationContext.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE
            ))

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        private fun url64(bytes: ByteArray): String = Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}
