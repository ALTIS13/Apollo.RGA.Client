package org.olcbox.app.net

import kotlinx.coroutines.CancellationException

/** Same installation key, fresh one-use proof per control join. Not an admission authority. */
internal class AndroidManagedJoinSigner private constructor(
    private val metadata: GeneralBootstrapV1,
    private val installation: PersonalJoinSigningKey,
    private val nowEpochMs: () -> Long,
    private val selection: GeneralBootstrapSelection?
) {
    constructor(metadata: GeneralBootstrapV1, installation: PersonalJoinSigningKey,
        nowEpochMs: () -> Long = System::currentTimeMillis) : this(metadata, installation, nowEpochMs, null)
    constructor(selection: GeneralBootstrapSelection, installation: PersonalJoinSigningKey,
        nowEpochMs: () -> Long = System::currentTimeMillis) : this(selection.metadata, installation, nowEpochMs, selection)

    private val installationId = installation.installationId
    private val publicDer = installation.publicKeyDer.copyOf()
    init { canonicalP256PublicKey(publicDer); checkIdentityAndDiscovery() }

    fun signManagedJoin(): String {
        checkIdentityAndDiscovery()
        var cancellation: CancellationException? = null
        val guardedKey = object : PersonalJoinSigningKey by installation {
            override fun sign(transcript: ByteArray): ByteArray {
                checkIdentityAndDiscovery()
                return try { installation.sign(transcript) }
                catch (cancelled: CancellationException) { cancellation = cancelled; throw cancelled }
            }
        }
        val proof = try { PersonalJoinProofFactory(guardedKey, nowEpochMs).create(metadata.roomId, metadata.country) }
        catch (_: Exception) { cancellation?.let { throw it }; throw IllegalArgumentException(DENIED) }
        checkIdentityAndDiscovery()
        return proof.toJsonString()
    }

    private fun checkIdentityAndDiscovery() {
        require(metadata.usesManagedLease && metadata.installationId == installationId &&
            installation.installationId == installationId && installation.publicKeyDer.contentEquals(publicDer) &&
            if (selection == null) metadata.isUsableAt(nowEpochMs())
            else selection.matchesKeyAndIsUsableAt(installation, nowEpochMs())) { DENIED }
    }
    override fun toString() = "AndroidManagedJoinSigner([redacted])"
    companion object { private const val DENIED = "Managed join proof denied [redacted]" }
}
