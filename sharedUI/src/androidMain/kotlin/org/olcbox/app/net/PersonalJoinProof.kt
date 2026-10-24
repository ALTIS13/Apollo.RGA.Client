package org.olcbox.app.net

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** The private key stays with the signer; callers only receive the canonical public SPKI. */
internal interface PersonalJoinSigningKey {
    val installationId: String
    val publicKeyDer: ByteArray
    fun sign(transcript: ByteArray): ByteArray
}

/** Must remain byte-for-byte compatible with access.PersonalJoinSigningPayload. */
internal object PersonalJoinTranscript {
    private const val DOMAIN = "Apollo.RGA/olcrtc-test-join/v1"
    private val installationIdPattern = Regex("[A-Za-z0-9_-]{16,128}")
    private val roomPattern = Regex("[A-Za-z0-9_-]{8,128}")
    private val countryPattern = Regex("[A-Z]{2}")
    private val issuedAtPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")

    fun build(
        installationId: String,
        publicKeySha256: ByteArray,
        room: String,
        country: String,
        issuedAt: String,
        nonce: ByteArray
    ): ByteArray {
        require(installationIdPattern.matches(installationId) &&
            publicKeySha256.size == 32 && publicKeySha256.any { it != 0.toByte() } &&
            roomPattern.matches(room) && countryPattern.matches(country) &&
            isCanonicalIssuedAt(issuedAt) && nonce.size == 32 && nonce.any { it != 0.toByte() }) {
            "Invalid personal join proof input"
        }
        return ByteArrayOutputStream().apply {
            write(DOMAIN.toByteArray(Charsets.US_ASCII))
            write(0)
            for (field in arrayOf(
                installationId.toByteArray(Charsets.UTF_8), publicKeySha256,
                room.toByteArray(Charsets.UTF_8), country.toByteArray(Charsets.US_ASCII),
                issuedAt.toByteArray(Charsets.US_ASCII), nonce
            )) {
                require(field.size <= 0xffff) { "Invalid personal join proof input" }
                write(field.size ushr 8)
                write(field.size and 0xff)
                write(field)
            }
        }.toByteArray()
    }

    fun issuedAtUtc(epochMs: Long): String {
        val wholeSecond = utcSeconds().format(Date(epochMs))
        val fraction = (((epochMs % 1_000L) + 1_000L) % 1_000L)
            .toString().padStart(3, '0').trimEnd('0')
        return wholeSecond + (if (fraction.isEmpty()) "" else ".$fraction") + "Z"
    }

    private fun isCanonicalIssuedAt(value: String): Boolean {
        if (!issuedAtPattern.matches(value) || value.length > 40) return false
        val fraction = value.substringAfter('.', "").substringBefore('Z')
        if (fraction.endsWith('0')) return false
        val seconds = value.substring(0, 19)
        val parsed = try {
            utcSeconds().parse(seconds)
        } catch (_: Exception) {
            null
        } ?: return false
        return utcSeconds().format(parsed) == seconds
    }

    private fun utcSeconds() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}

/** Flat wire claims only. Deliberately redacted when an object is logged accidentally. */
internal class PersonalJoinClaims private constructor(
    private val installationId: String,
    private val issuedAt: String,
    private val nonce: String,
    private val publicKey: String,
    private val signature: String
) {
    fun toJsonString(): String = JSONObject()
        .put("installationId", installationId)
        .put("issuedAt", issuedAt)
        .put("nonce", nonce)
        .put("publicKey", publicKey)
        .put("signature", signature)
        .toString()

    override fun toString(): String = "PersonalJoinClaims([redacted])"

    companion object {
        fun create(id: String, issuedAt: String, nonce: ByteArray, publicDer: ByteArray, signature: ByteArray) =
            PersonalJoinClaims(id, issuedAt, url64(nonce), url64(publicDer), url64(signature))

        private fun url64(bytes: ByteArray): String = Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}

internal class PersonalJoinProofFactory(
    private val signer: PersonalJoinSigningKey,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val nonceBytes: (ByteArray) -> Unit = SecureRandom()::nextBytes
) {
    fun create(room: String, country: String): PersonalJoinClaims {
        val installationId = signer.installationId
        val publicDer = signer.publicKeyDer.copyOf()
        val publicKey = canonicalP256PublicKey(publicDer)
        val nonce = ByteArray(32).also(nonceBytes)
        val issuedAt = PersonalJoinTranscript.issuedAtUtc(clockMs())
        val transcript = PersonalJoinTranscript.build(
            installationId, MessageDigest.getInstance("SHA-256").digest(publicDer),
            room, country, issuedAt, nonce
        )
        val signature = try {
            signer.sign(transcript)
        } catch (_: Exception) {
            throw IllegalStateException("Personal join proof unavailable")
        }
        require(signature.isNotEmpty() && signature.size <= 80) { "Invalid personal join signature" }
        val verified = try {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(transcript)
            }.verify(signature)
        } catch (_: Exception) {
            false
        }
        require(verified) { "Invalid personal join signature" }
        return PersonalJoinClaims.create(installationId, issuedAt, nonce, publicDer, signature)
    }
}

/** Go accepts only a round-tripping X.509 SPKI containing the named P-256 curve. */
internal fun canonicalP256PublicKey(der: ByteArray): ECPublicKey {
    require(der.isNotEmpty() && der.size <= 256) { "Invalid installation public key" }
    val key = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as? ECPublicKey
    } catch (_: Exception) {
        null
    } ?: throw IllegalArgumentException("Invalid installation public key")
    val p256 = AlgorithmParameters.getInstance("EC").apply {
        init(ECGenParameterSpec("secp256r1"))
    }.getParameterSpec(ECParameterSpec::class.java)
    require(key.encoded.contentEquals(der) &&
        key.params.curve == p256.curve && key.params.generator == p256.generator &&
        key.params.order == p256.order && key.params.cofactor == p256.cofactor) {
        "Invalid installation public key"
    }
    return key
}
