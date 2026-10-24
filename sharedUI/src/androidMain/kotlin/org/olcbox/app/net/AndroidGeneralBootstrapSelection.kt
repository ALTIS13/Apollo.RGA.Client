package org.olcbox.app.net

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Local room discovery only. Its original metadata is never a server admission lease. */
internal class GeneralBootstrapSelection private constructor(
    val metadata: GeneralBootstrapV1,
    val receivedAtEpochMs: Long,
    private val installationId: String,
    publicDer: ByteArray
) {
    private val publicDer = publicDer.copyOf()
    private val fingerprint = MessageDigest.getInstance("SHA-256").digest(this.publicDer)

    /** Construction freshness only: fresh server-prepared proof is still mandatory for every join. */
    fun matchesKeyAndIsUsableAt(key: PersonalJoinSigningKey, now: Long): Boolean = try {
        val currentDer = key.publicKeyDer.copyOf()
        canonicalP256PublicKey(currentDer)
        now >= receivedAtEpochMs && now - receivedAtEpochMs < MAX_AGE_MS &&
            metadata.isUsableAt(receivedAtEpochMs) && metadata.installationId == installationId &&
            key.installationId == installationId && currentDer.contentEquals(publicDer) &&
            MessageDigest.isEqual(fingerprint, MessageDigest.getInstance("SHA-256").digest(currentDer))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    override fun toString() = "GeneralBootstrapSelection([redacted])"
    companion object {
        private const val MAX_AGE_MS = 86_400_000L
        // Cache-only boundary: caller must first verify the encrypted receipt or finish concrete producer PoP.
        fun fromVerifiedReceipt(metadata: GeneralBootstrapV1, key: PersonalJoinSigningKey,
            receivedAt: Long, now: Long): GeneralBootstrapSelection? = try {
            val id = key.installationId
            val der = key.publicKeyDer.copyOf()
            canonicalP256PublicKey(der)
            require(GeneralBootstrapV1.installationPattern.matches(id) && metadata.installationId == id &&
                metadata.isUsableAt(receivedAt))
            GeneralBootstrapSelection(metadata, receivedAt, id, der).takeIf { it.matchesKeyAndIsUsableAt(key, now) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
