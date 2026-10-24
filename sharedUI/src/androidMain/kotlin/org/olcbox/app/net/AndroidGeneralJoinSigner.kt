package org.olcbox.app.net

import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.Signature
import kotlinx.coroutines.CancellationException

/** SAME installation key; a source callback, not a generated mobile ABI implementation. */
internal class AndroidGeneralJoinSigner private constructor(
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
    private val publicKey = canonicalP256PublicKey(publicDer)
    private val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicDer)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val preparedPrefix = "Apollo.RGA/general-prepared-proof/v1\u0000".toByteArray(Charsets.US_ASCII)
    private val carrierPrefix = "Apollo.RGA/general-carrier-join/v1\u0000".toByteArray(Charsets.US_ASCII)
    init {
        require(!metadata.usesManagedLease && metadata.installationId == installationId && if (selection == null) metadata.isUsableAt(nowEpochMs())
            else selection.matchesKeyAndIsUsableAt(installation, nowEpochMs()) &&
                installation.installationId == installationId && installation.publicKeyDer.contentEquals(publicDer)) { DENIED }
    }

    fun signGeneral(prepared: ByteArray, carrier: ByteArray, notAfterUnixMillis: Long): String {
        val one = prepared.copyOf(); val two = carrier.copyOf()
        require(one.size <= 8192 && two.size <= 16384 && one.size > preparedPrefix.size + 4 &&
            one.copyOfRange(0, preparedPrefix.size).contentEquals(preparedPrefix)) { DENIED }
        val reader = ByteBuffer.wrap(one, preparedPrefix.size, one.size - preparedPrefix.size)
        require(reader.int == reader.remaining()) { DENIED }
        val body = ByteArray(reader.remaining()).also { reader.get(it) }
        val g = GeneralJson.parse(body, 8192)
        require(g.keys.toList() == PREPARED_FIELDS && GeneralJson.canonical(g).toByteArray(Charsets.UTF_8).contentEquals(body)) { DENIED }
        require(STRING_FIELDS.all { g[it] is String } && NUMBER_FIELDS.all { g[it] is Long } && g["resetPresent"] is Boolean) { DENIED }
        val target = GeneralJson.objectValue(g, "target"); GeneralJson.objectValue(g, "launch")
        val originalEnd = GeneralJson.utcTimestamp(GeneralJson.string(g, "notAfter"), canonical = true, microsecond = true)
        val policyEnd = GeneralJson.utcTimestamp(GeneralJson.string(g, "policyExpiry"), canonical = true)
        val end = originalEnd.first
        val now = nowEpochMs()
        val limit = GeneralJson.long(g, "limit"); val used = GeneralJson.long(g, "used")
        val budget = GeneralJson.string(g, "budgetIntent")
        require(now > 0 && end == notAfterUnixMillis && end > now && end - now <= 300_000 &&
            (end < policyEnd.first || end == policyEnd.first && originalEnd.second <= policyEnd.second) &&
            GeneralJson.string(g, "panel") == metadata.panelInstance && GeneralJson.long(g, "user") == metadata.userId &&
            GeneralJson.string(g, "installation") == installationId && GeneralJson.string(g, "room") == metadata.roomId &&
            GeneralJson.string(g, "country") == metadata.country && GeneralJson.string(g, "fingerprint") == fingerprint &&
            GeneralJson.string(target, "panelId") == metadata.panelInstance && GeneralJson.string(target, "subjectId") == metadata.userId.toString() &&
            GeneralJson.long(g, "revision") > 0 && GeneralJson.long(g, "level") in 0..0xffffffffL &&
            validHex(GeneralJson.string(g, "commandDigest"), 64) && validHex(GeneralJson.string(g, "destinationPolicy"), 64) &&
            limit >= 0 && used >= 0 && (limit == 0L || used < limit) &&
            (budget == "stock-common-delayed-cutoff" || budget == "unmetered-pending" && limit == 0L)) { DENIED }
        require(two.size > carrierPrefix.size && two.copyOfRange(0, carrierPrefix.size).contentEquals(carrierPrefix)) { DENIED }
        val fields = ByteBuffer.wrap(two, carrierPrefix.size, two.size - carrierPrefix.size)
        val expected = listOf(one, null, null, installationId.toByteArray(Charsets.UTF_8),
            metadata.roomId.toByteArray(Charsets.UTF_8), metadata.country.toByteArray(Charsets.US_ASCII))
        for (i in expected.indices) {
            require(fields.remaining() >= 4) { DENIED }
            val n = fields.int; require(n > 0 && n <= fields.remaining()) { DENIED }
            val field = ByteArray(n).also { fields.get(it) }
            if (i == 1 || i == 2) {
                val text = field.toString(Charsets.US_ASCII)
                require(validHex(text, if (i == 1) 32 else 64) && text.toByteArray(Charsets.US_ASCII).contentEquals(field)) { DENIED }
            } else require(field.contentEquals(expected[i]!!)) { DENIED }
        }
        require(!fields.hasRemaining()) { DENIED }
        checkKeyAndEnd(end)
        val first = signAndVerify(one, end)
        val second = signAndVerify(two, end)
        val proof = "{\"publicKey\":\"${encode(publicDer)}\",\"preparedProof\":\"${encode(first)}\",\"carrierProof\":\"${encode(second)}\"}"
        require(proof.toByteArray(Charsets.UTF_8).size <= 2048) { DENIED }
        checkKeyAndEnd(end)
        return proof
    }
    private fun checkKeyAndEnd(end: Long) {
        require(nowEpochMs() in 1 until end && installation.installationId == installationId &&
            installation.publicKeyDer.contentEquals(publicDer)) { DENIED }
    }
    private fun signAndVerify(transcript: ByteArray, end: Long): ByteArray {
        checkKeyAndEnd(end)
        val signature = try { installation.sign(transcript.copyOf()).copyOf() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IllegalStateException(DENIED) }
        require(signature.isNotEmpty() && signature.size <= 80) { DENIED }
        val valid = try { Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey); update(transcript)
        }.verify(signature) } catch (_: Exception) { false }
        require(valid) { DENIED }; checkKeyAndEnd(end)
        return signature
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun validHex(text: String, size: Int) = text.length == size && Regex("[0-9a-f]+").matches(text) && text.any { it != '0' }
    override fun toString() = "AndroidGeneralJoinSigner([redacted])"
    companion object {
        private const val DENIED = "GENERAL signature denied [redacted]"
        private val PREPARED_FIELDS = listOf("panel", "user", "installation", "fingerprint", "challenge", "session", "nonce", "target", "launch", "owner",
            "commandDigest", "level", "nativeEndpoint", "revision", "room", "country", "destinationPolicy", "notAfter", "policyExpiry", "limit", "used",
            "strategy", "createdAt", "resetAt", "resetPresent", "budgetIntent")
        private val NUMBER_FIELDS = setOf("user", "level", "revision", "limit", "used")
        private val STRING_FIELDS = PREPARED_FIELDS.toSet() - NUMBER_FIELDS - setOf("target", "launch", "resetPresent")
    }
}
