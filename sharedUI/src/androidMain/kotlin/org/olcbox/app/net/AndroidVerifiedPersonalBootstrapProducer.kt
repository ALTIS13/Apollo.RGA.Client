package org.olcbox.app.net

import android.content.Context
import android.util.Base64
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.olcbox.app.GeneratedAppInfo

/** HTTP is deliberately behind a narrow interface so only a pinned TLS transport can be used. */
internal fun interface PersonalBootstrapHttpTransport {
    fun post(path: String, body: ByteArray): PersonalBootstrapHttpResponse?
}

internal class PersonalBootstrapHttpResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val cacheControl: String?
)

/** Same imported Remnawave link, same Keystore installation, fresh Bridge challenge. */
internal class AndroidVerifiedPersonalBootstrapProducer(
    private val transport: PersonalBootstrapHttpTransport,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) : VerifiedPersonalBootstrapProducer {
    override suspend fun completeLinkAndPrefetch(
        importedSubscriptionUrl: String,
        installation: PersonalJoinSigningKey
    ): PersonalBootstrapV1? = withContext(Dispatchers.IO) {
        try {
            val bearer = RemnawavePersonalBearer.parse(importedSubscriptionUrl) ?: return@withContext null
            val proofFactory = PersonalLinkProofFactory(installation, nowEpochMs)
            val publicDer = proofFactory.publicKeyDer()
            canonicalP256PublicKey(publicDer)
            val shortUuid = bearer.shortUuidForBridgeRequest()
            val begin = JSONObject()
                .put("shortUuid", shortUuid)
                .put("installationId", proofFactory.installationId)
                .put("publicKeyDer", url64(publicDer))
            val challenge = parseChallenge(postJson("/v1/personal/link/begin", begin) ?: return@withContext null)
                ?: return@withContext null
            val proof = proofFactory.create(challenge)
            val complete = JSONObject()
                .put("shortUuid", shortUuid)
                .put("challengeId", proof.challengeId)
                .put("publicKeyDer", url64(proof.publicKeyDer()))
                .put("signature", url64(proof.signature()))
            val bootstrap = PersonalBootstrapV1.fromPrivateJson(
                postJson("/v1/personal/bootstrap", complete) ?: return@withContext null
            ) ?: return@withContext null
            bootstrap.takeIf { it.isUsableAt(nowEpochMs()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        val encoded = body.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_REQUEST_BYTES) return null
        val response = transport.post(path, encoded) ?: return null
        if (response.status != 200 || response.body.isEmpty() || response.body.size > MAX_RESPONSE_BYTES ||
            response.contentType?.lowercase(Locale.ROOT) !in ACCEPTED_CONTENT_TYPES ||
            response.cacheControl?.lowercase(Locale.ROOT)?.split(',')
                ?.any { it.trim() == "no-store" } != true) return null
        return JSONObject(response.body.toString(Charsets.UTF_8))
    }

    private fun parseChallenge(json: JSONObject): PersonalLinkChallenge? {
        if (json.keys().asSequence().toSet() != CHALLENGE_FIELDS) return null
        val panelInstance = json.opt("panelInstance") as? String ?: return null
        val challengeId = json.opt("challengeId") as? String ?: return null
        val nonce = decodeUrl64(json.opt("nonce") as? String ?: return null) ?: return null
        val expiresAt = parseUtcTimestamp(json.opt("expiresAt") as? String ?: return null) ?: return null
        val now = nowEpochMs()
        if (expiresAt <= now || expiresAt > now + MAX_CHALLENGE_FUTURE_MS) return null
        return PersonalLinkChallenge(panelInstance, challengeId, nonce, expiresAt)
    }

    private fun parseUtcTimestamp(text: String): Long? {
        if (!UTC_TIMESTAMP.matches(text)) return null
        val secondsText = text.substring(0, 19)
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val position = ParsePosition(0)
        val seconds = parser.parse(secondsText, position)?.time ?: return null
        if (position.index != secondsText.length || parser.format(seconds) != secondsText) return null
        val fraction = text.substringAfter('.', "").substringBefore('Z')
        val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toIntOrNull()
            ?: return null
        return seconds + millis
    }

    private fun decodeUrl64(text: String): ByteArray? {
        if (!URL64.matches(text)) return null
        val bytes = try {
            Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
        return bytes.takeIf { it.size == 32 && it.any { byte -> byte != 0.toByte() } && url64(it) == text }
    }

    private fun url64(bytes: ByteArray): String = Base64.encodeToString(
        bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    companion object {
        val CHALLENGE_FIELDS = setOf("challengeId", "panelInstance", "nonce", "expiresAt")
        val ACCEPTED_CONTENT_TYPES = setOf("application/json", "application/json; charset=utf-8")
        val UTC_TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
        val URL64 = Regex("[A-Za-z0-9_-]{43}")
        const val MAX_CHALLENGE_FUTURE_MS = 10 * 60_000L
        const val MAX_REQUEST_BYTES = 4_096
        const val MAX_RESPONSE_BYTES = 8_192

        /** No personal Bridge path exists in the ordinary package or without an exact pin. */
        fun forPersonalUsbTest(context: Context): AndroidVerifiedPersonalBootstrapProducer? {
            val app = context.applicationContext
            if (app.packageName != "tech.gatealtas.rga.personal" ||
                AndroidRuntimeLocationPolicy.forApp(app) != RuntimeLocationPolicy.ExperimentalDebug
            ) return null
            val pin = GeneratedAppInfo.PERSONAL_BRIDGE_CERT_SHA256
            if (!Regex("[0-9a-f]{64}").matches(pin)) return null
            return try {
                AndroidVerifiedPersonalBootstrapProducer(AndroidPinnedLoopbackBootstrapTransport(pin))
            } catch (_: Exception) {
                null
            }
        }
    }
}
