package org.olcbox.app.net

import android.content.Context
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.Signature

/** Fields from the authenticated BeginLink response; no subscription bearer is retained. */
internal class PersonalLinkChallenge(
    val panelInstance: String,
    val challengeId: String,
    nonce: ByteArray,
    val expiresAtEpochMs: Long
) {
    private val nonceValue = nonce.copyOf()
    internal fun nonceBytes(): ByteArray = nonceValue.copyOf()
    override fun toString(): String = "PersonalLinkChallenge([redacted])"
}

/** Go access.LinkChallenge.SigningPayload: domain, NUL, then five uint16-BE length/value fields. */
internal object PersonalLinkTranscript {
    private const val DOMAIN = "Apollo.RGA/link-proof/v1"
    private val PANEL_INSTANCE = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val OPAQUE_ID = Regex("[A-Za-z0-9_-]{16,128}")

    fun build(
        panelInstance: String,
        installationId: String,
        challengeId: String,
        publicKeyDer: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        require(PANEL_INSTANCE.matches(panelInstance) && OPAQUE_ID.matches(installationId) &&
            OPAQUE_ID.matches(challengeId) && nonce.size == 32 && nonce.any { it != 0.toByte() }) {
            "Invalid personal link challenge"
        }
        canonicalP256PublicKey(publicKeyDer)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
        require(fingerprint.any { it != 0.toByte() }) { "Invalid personal link challenge" }
        return ByteArrayOutputStream().apply {
            write(DOMAIN.toByteArray(Charsets.US_ASCII))
            write(0)
            for (field in arrayOf(
                panelInstance.toByteArray(Charsets.UTF_8),
                installationId.toByteArray(Charsets.UTF_8),
                challengeId.toByteArray(Charsets.UTF_8),
                fingerprint,
                nonce
            )) {
                require(field.size <= 0xffff) { "Invalid personal link challenge" }
                write(field.size ushr 8)
                write(field.size and 0xff)
                write(field)
            }
        }.toByteArray()
    }
}

/** Exact data needed for CompleteLink; never format the signature or challenge into logs. */
internal class PersonalLinkProof internal constructor(
    val challengeId: String,
    publicKeyDer: ByteArray,
    signature: ByteArray
) {
    private val publicKeyValue = publicKeyDer.copyOf()
    private val signatureValue = signature.copyOf()
    fun publicKeyDer(): ByteArray = publicKeyValue.copyOf()
    fun signature(): ByteArray = signatureValue.copyOf()
    override fun toString(): String = "PersonalLinkProof([redacted])"
}

/** One signer snapshot serves both BeginLink identity and CompleteLink proof. */
internal class PersonalLinkProofFactory(
    private val signer: PersonalJoinSigningKey,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    val installationId: String = signer.installationId
    private val publicKeyValue = signer.publicKeyDer.copyOf()
    fun publicKeyDer(): ByteArray = publicKeyValue.copyOf()

    fun create(challenge: PersonalLinkChallenge): PersonalLinkProof {
        require(challenge.expiresAtEpochMs > nowEpochMs()) { "Invalid personal link challenge" }
        val transcript = PersonalLinkTranscript.build(
            challenge.panelInstance, installationId, challenge.challengeId,
            publicKeyValue, challenge.nonceBytes()
        )
        val signature = try {
            signer.sign(transcript)
        } catch (_: Exception) {
            throw IllegalStateException("Personal link proof unavailable")
        }
        if (signature.isEmpty() || signature.size > 80) {
            throw IllegalStateException("Personal link proof unavailable")
        }
        val verified = try {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(canonicalP256PublicKey(publicKeyValue))
                update(transcript)
            }.verify(signature)
        } catch (_: Exception) {
            false
        }
        if (!verified) throw IllegalStateException("Personal link proof unavailable")
        require(challenge.expiresAtEpochMs > nowEpochMs()) { "Invalid personal link challenge" }
        return PersonalLinkProof(challenge.challengeId, publicKeyValue, signature)
    }

    companion object {
        fun forApp(context: Context): PersonalLinkProofFactory = PersonalLinkProofFactory(
            AndroidKeystoreInstallationIdentity.forApp(context).loadOrCreate()
        )
    }
}
