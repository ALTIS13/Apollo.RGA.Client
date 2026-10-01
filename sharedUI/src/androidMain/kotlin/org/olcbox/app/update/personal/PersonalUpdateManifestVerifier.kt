package org.olcbox.app.update.personal

import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Pure source gate: no network, persistent state, APK admission or installer side effects. */
@OptIn(ExperimentalEncodingApi::class)
object PersonalUpdateManifestVerifier {
    private const val PACKAGE = "tech.gatealtas.rga.personal"
    private const val COMPANION_PACKAGE = "tech.gatealtas.rga.personal.test"
    private val envelopePattern = Regex("\\{\"payload\":\"([A-Za-z0-9_-]+)\",\"signature\":\"([A-Za-z0-9_-]+)\"}")
    private val decimalPattern = Regex("0|[1-9][0-9]*")
    private val hexPattern = Regex("[0-9a-f]{64}")
    private val url64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val fields = listOf("sequence", "issuedAt", "expiresAt", "package", "versionCode", "versionName",
        "minSdk", "abi", "apkPath", "apkBytes", "apkSha256", "apkCertSha256", "companionPackage",
        "companionVersionCode", "companionSha256", "companionCertSha256")

    fun verify(envelope: ByteArray, pinnedSpki: ByteArray, baseline: PersonalUpdateBaseline,
        highWater: PersonalUpdateHighWater, nowEpochSeconds: Long): PersonalUpdateVerification {
        if (envelope.size > 8192) return failure(PersonalUpdateFailure.OVERSIZE)
        if (envelope.any { it.toInt() !in 32..126 }) return failure(PersonalUpdateFailure.INVALID_ENVELOPE)
        val match = envelopePattern.matchEntire(envelope.toString(Charsets.US_ASCII))
            ?: return failure(PersonalUpdateFailure.INVALID_ENVELOPE)
        val payload = decodeCanonical(match.groupValues[1]) ?: return failure(PersonalUpdateFailure.INVALID_ENVELOPE)
        val signature = decodeCanonical(match.groupValues[2]) ?: return failure(PersonalUpdateFailure.INVALID_ENVELOPE)
        if (payload.size > 4096) return failure(PersonalUpdateFailure.OVERSIZE)
        val publicKey = p256Key(pinnedSpki) ?: return failure(PersonalUpdateFailure.INVALID_KEY)
        val validSignature = try {
            Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(payload) }.verify(signature)
        } catch (_: GeneralSecurityException) { false }
        if (!validSignature) return failure(PersonalUpdateFailure.INVALID_SIGNATURE)
        // Nothing in the payload is interpreted until the pinned-key signature passes.
        val manifest = parse(payload) ?: return failure(PersonalUpdateFailure.INVALID_SCHEMA)
        if (nowEpochSeconds < 0 || manifest.expiresAt <= nowEpochSeconds ||
            manifest.issuedAt > nowEpochSeconds && manifest.issuedAt - nowEpochSeconds > 300 ||
            manifest.expiresAt <= manifest.issuedAt || manifest.expiresAt - manifest.issuedAt > 604800
        ) return failure(PersonalUpdateFailure.INVALID_TIME)
        if (manifest.versionCode <= baseline.versionCode) return failure(PersonalUpdateFailure.DOWNGRADE)
        if (manifest.packageName != PACKAGE || baseline.packageName != PACKAGE ||
            manifest.apkCertSha256 != baseline.apkCertSha256
        ) return failure(PersonalUpdateFailure.BASELINE_MISMATCH)
        if (manifest.minSdk < 23 || manifest.minSdk > baseline.sdk || manifest.abi != "arm64-v8a" ||
            manifest.abi !in baseline.supportedAbis
        ) return failure(PersonalUpdateFailure.INCOMPATIBLE_DEVICE)
        if (manifest.apkPath != "/updates/personal/v1/${manifest.versionCode}/apollo-rga-personal.apk") {
            return failure(PersonalUpdateFailure.INVALID_PATH)
        }
        if (manifest.companionPackage != COMPANION_PACKAGE || baseline.companionPackage != COMPANION_PACKAGE ||
            manifest.companionVersionCode != baseline.companionVersionCode ||
            manifest.companionSha256 != baseline.companionSha256 ||
            manifest.companionCertSha256 != baseline.companionCertSha256
        ) return failure(PersonalUpdateFailure.COMPANION_MISMATCH)
        val payloadHash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            val value = byte.toInt() and 255
            "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 15]
        }
        if (manifest.sequence < highWater.sequence) return failure(PersonalUpdateFailure.SEQUENCE_ROLLBACK)
        if (manifest.sequence == highWater.sequence && payloadHash != highWater.payloadSha256) {
            return failure(PersonalUpdateFailure.SEQUENCE_CONFLICT)
        }
        return PersonalUpdateVerification.Success(manifest, payloadHash)
    }

    private fun failure(reason: PersonalUpdateFailure) = PersonalUpdateVerification.Failure(reason)

    private fun decodeCanonical(text: String): ByteArray? = try {
        url64.decode(text).takeIf {
            url64.encode(it) == text
        }
    } catch (_: IllegalArgumentException) { null }

    private fun p256Key(spki: ByteArray): ECPublicKey? = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as? ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        key?.takeIf {
            spki.contentEquals(it.encoded) && it.params.curve == expected.curve &&
                it.params.generator == expected.generator && it.params.order == expected.order &&
                it.params.cofactor == expected.cofactor
        }
    } catch (_: GeneralSecurityException) { null }

    private fun parse(payload: ByteArray): PersonalUpdateManifest? {
        if (payload.any { it.toInt() !in 0..127 }) return null
        val lines = payload.toString(Charsets.US_ASCII).split('\n')
        if (lines.size != 18 || lines[0] != "APOLLO-RGA-PERSONAL-OTA/1" || lines.last() != "") return null
        val values = fields.mapIndexed { index, field ->
            val line = lines[index + 1]
            if (!line.startsWith("$field=")) return null
            line.substring(field.length + 1)
        }
        fun number(index: Int): Long? = values[index].takeIf(decimalPattern::matches)?.toLongOrNull()
        val sequence = number(0)?.takeIf { it > 0 } ?: return null
        val issuedAt = number(1) ?: return null
        val expiresAt = number(2) ?: return null
        val versionCode = number(4)?.takeIf { it > 0 } ?: return null
        if (!Regex("[A-Za-z0-9.+_-]{1,64}").matches(values[5])) return null
        val minSdk = number(6)?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        val apkBytes = number(9)?.takeIf { it in 1..268435456 } ?: return null
        val companionVersionCode = number(13) ?: return null
        if (listOf(10, 11, 14, 15).any { !hexPattern.matches(values[it]) }) return null
        return PersonalUpdateManifest(sequence, issuedAt, expiresAt, values[3], versionCode, values[5],
            minSdk, values[7], values[8], apkBytes, values[10], values[11], values[12],
            companionVersionCode, values[14], values[15])
    }
}
