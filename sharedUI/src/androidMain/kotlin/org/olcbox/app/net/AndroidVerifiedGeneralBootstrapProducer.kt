package org.olcbox.app.net

import android.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import org.olcbox.app.vpn.OlcChannelRole

internal fun interface GeneralBootstrapHttpTransport {
    fun post(path: String, body: ByteArray): GeneralBootstrapHttpResponse?
}
internal class GeneralBootstrapHttpResponse(val status: Int, val contentType: String?, body: ByteArray, val cacheControl: String?) {
    private val value = body.copyOf()
    val body: ByteArray get() = value.copyOf()
    override fun toString() = "GeneralBootstrapHttpResponse([redacted])"
}

/** Only a supplied operator-owned transport; no endpoint/trust selection or runtime factory. */
internal class AndroidVerifiedGeneralBootstrapProducer private constructor(
    private val transport: GeneralBootstrapHttpTransport,
    private val managed: Boolean,
    private val channel: OlcChannelRole,
    private val nowEpochMs: () -> Long
) {
    constructor(transport: GeneralBootstrapHttpTransport, nowEpochMs: () -> Long = System::currentTimeMillis) :
        this(transport, false, OlcChannelRole.Primary, nowEpochMs)
    suspend fun completeLinkAndPrefetch(importedSubscriptionUrl: String, installation: PersonalJoinSigningKey): GeneralBootstrapV1? =
        withContext(Dispatchers.IO) {
            var keyCancellation: CancellationException? = null
            try {
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Link)
                coroutineContext.ensureActive()
                val bearer = RemnawavePersonalBearer.parse(importedSubscriptionUrl) ?: return@withContext null
                // Preserve cancellation even though the unchanged PERSONAL proof factory redacts key errors.
                val key = object : PersonalJoinSigningKey by installation {
                    override fun sign(transcript: ByteArray): ByteArray = try { installation.sign(transcript) }
                    catch (cancelled: CancellationException) { keyCancellation = cancelled; throw cancelled }
                }
                val factory = PersonalLinkProofFactory(key, nowEpochMs)
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Key)
                val der = factory.publicKeyDer()
                canonicalP256PublicKey(der)
                require(GeneralBootstrapV1.installationPattern.matches(factory.installationId))
                val shortUuid = bearer.shortUuidForBridgeRequest()
                val begin = JSONObject().put("shortUuid", shortUuid).put("installationId", factory.installationId)
                    .put("publicKeyDer", url64(der))
                val path = if (!managed) "/v1/general" else if (channel == OlcChannelRole.Reserve)
                    "/v1/managed/reserve" else "/v1/managed"
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Begin)
                val challengeJson = postJson("$path/link/begin", begin) ?: return@withContext null
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Challenge)
                require(challengeJson.keys == setOf("challengeId", "panelInstance", "nonce", "expiresAt"))
                val panel = GeneralJson.string(challengeJson, "panelInstance")
                val challengeId = GeneralJson.string(challengeJson, "challengeId")
                val nonceText = GeneralJson.string(challengeJson, "nonce")
                require(GeneralBootstrapV1.panelPattern.matches(panel) &&
                    GeneralBootstrapV1.installationPattern.matches(challengeId) && Regex("[A-Za-z0-9_-]{43}").matches(nonceText))
                val nonce = Base64.decode(nonceText, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                require(nonce.size == 32 && nonce.any { it != 0.toByte() } && url64(nonce) == nonceText)
                val expiry = GeneralJson.utcMillis(GeneralJson.string(challengeJson, "expiresAt"))
                val now = nowEpochMs()
                require(now > 0 && expiry > now && expiry - now <= 600_000)
                coroutineContext.ensureActive()
                val proof = factory.create(PersonalLinkChallenge(panel, challengeId, nonce, expiry))
                coroutineContext.ensureActive()
                val complete = JSONObject().put("shortUuid", shortUuid).put("challengeId", proof.challengeId)
                    .put("publicKeyDer", url64(proof.publicKeyDer())).put("signature", url64(proof.signature()))
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Complete)
                val response = postJson("$path/bootstrap", complete) ?: return@withContext null
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Metadata)
                coroutineContext.ensureActive()
                val metadata = GeneralBootstrapV1.fromPrivateBytes(GeneralJson.canonical(response).toByteArray(Charsets.UTF_8))
                    ?: return@withContext null
                metadata.takeIf { it.usesManagedLease == managed && it.matchesGeneralChannel(channel) && it.panelInstance == panel && it.installationId == factory.installationId &&
                    installation.installationId == factory.installationId && installation.publicKeyDer.contentEquals(der) &&
                    it.isUsableAt(nowEpochMs()) }
            } catch (cancelled: CancellationException) {
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Cancelled); throw cancelled
            }
            catch (_: Exception) {
                keyCancellation?.let { observeGeneralBootstrap(GeneralBootstrapDiagnostic.Cancelled); throw it }
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Denied); null
            }
        }

    private fun postJson(path: String, request: JSONObject): Map<String, Any?>? {
        val bytes = request.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096)
        val response = transport.post(path, bytes.copyOf()) ?: run {
            observeGeneralBootstrap(GeneralBootstrapDiagnostic.NoResponse); return null
        }
        observeGeneralBootstrap(if (response.status == 200) GeneralBootstrapDiagnostic.HttpAccepted else GeneralBootstrapDiagnostic.HttpDenied)
        val body = response.body
        if (response.status != 200 || response.contentType?.lowercase(Locale.ROOT) !in
            setOf("application/json", "application/json; charset=utf-8") ||
            response.cacheControl?.lowercase(Locale.ROOT)?.split(',')?.any { it.trim() == "no-store" } != true) return null
        return GeneralJson.parse(body, 8192)
    }

    override fun toString() = "AndroidVerifiedGeneralBootstrapProducer([redacted])"
    private fun url64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    companion object {
        fun managed(transport: GeneralBootstrapHttpTransport, nowEpochMs: () -> Long = System::currentTimeMillis) =
            AndroidVerifiedGeneralBootstrapProducer(transport, true, OlcChannelRole.Primary, nowEpochMs)
        fun managedReserve(transport: GeneralBootstrapHttpTransport, nowEpochMs: () -> Long = System::currentTimeMillis) =
            AndroidVerifiedGeneralBootstrapProducer(transport, true, OlcChannelRole.Reserve, nowEpochMs)
    }
}
