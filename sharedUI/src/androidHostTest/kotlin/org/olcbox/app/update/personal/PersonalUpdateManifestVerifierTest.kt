package org.olcbox.app.update.personal

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalUpdateManifestVerifierTest {
    private val keys = ecKeys("secp256r1")
    private val now = 1_800_000_000L
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val baseline = PersonalUpdateBaseline(
        "tech.gatealtas.rga.personal", 2, hashA, 35, setOf("arm64-v8a"),
        "tech.gatealtas.rga.personal.test", 1, hashB, hashA
    )
    private val payload = """
        APOLLO-RGA-PERSONAL-OTA/1
        sequence=1
        issuedAt=1800000000
        expiresAt=1800003600
        package=tech.gatealtas.rga.personal
        versionCode=3
        versionName=1.0.2
        minSdk=23
        abi=arm64-v8a
        apkPath=/updates/personal/v1/3/apollo-rga-personal.apk
        apkBytes=123456
        apkSha256=${"c".repeat(64)}
        apkCertSha256=${"a".repeat(64)}
        companionPackage=tech.gatealtas.rga.personal.test
        companionVersionCode=1
        companionSha256=${"b".repeat(64)}
        companionCertSha256=${"a".repeat(64)}
    """.trimIndent() + "\n"

    @Test fun validP256SignedFixtureReturnsVerifiedImmutableFields() {
        val result = verify() as? PersonalUpdateVerification.Success
        assertTrue("valid real-P256 envelope must pass", result != null)
        assertEquals(3L, result!!.manifest.versionCode)
        assertEquals(1L, result.manifest.sequence)
        assertEquals("/updates/personal/v1/3/apollo-rga-personal.apk", result.manifest.apkPath)
        assertEquals("c".repeat(64), result.manifest.apkSha256)
        assertEquals(digest(payload.toByteArray()), result.payloadSha256)
    }

    @Test fun tamperedPayloadFailsSignatureBeforeSchemaInterpretation() {
        val original = envelope(payload)
        val encoded = b64(payload.toByteArray())
        val tampered = original.toString(Charsets.US_ASCII)
            .replace(encoded, b64("not a manifest\n".toByteArray())).toByteArray()
        failure(PersonalUpdateFailure.INVALID_SIGNATURE, verify(envelope = tampered))
    }

    @Test fun wrongP256KeyAndMalformedSignatureFailClosed() {
        failure(PersonalUpdateFailure.INVALID_SIGNATURE, verify(pin = ecKeys("secp256r1").public.encoded))
        failure(PersonalUpdateFailure.INVALID_SIGNATURE, verify(envelope = envelope(payload, signature = byteArrayOf(1))))
    }

    @Test fun signedWrongCurveAndRsaPinsAreRejected() {
        val wrongCurve = ecKeys("secp384r1")
        failure(PersonalUpdateFailure.INVALID_KEY, verify(envelope = envelope(payload, wrongCurve), pin = wrongCurve.public.encoded))
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        failure(PersonalUpdateFailure.INVALID_KEY, verify(pin = rsa.public.encoded))
        failure(PersonalUpdateFailure.INVALID_KEY, verify(pin = byteArrayOf(1, 2)))
        failure(PersonalUpdateFailure.INVALID_KEY, verify(pin = keys.public.encoded + byteArrayOf(0)))
    }

    @Test fun envelopeMustHaveExactCanonicalFieldOrderAndNoExtraFields() {
        val text = envelope(payload).toString(Charsets.US_ASCII)
        val p = b64(payload.toByteArray())
        val s = text.substringAfter("\"signature\":\"").substringBefore('"')
        val bad = listOf(
            " $text", "$text\n", text.replace(":", ": "),
            "{\"signature\":\"$s\",\"payload\":\"$p\"}",
            text.dropLast(1) + ",\"unknown\":1}",
            text.dropLast(1) + ",\"payload\":\"$p\"}",
            "[]", "{}", text.dropLast(1), text.replace("payload", "pay\\u006coad")
        )
        bad.forEach { failure(PersonalUpdateFailure.INVALID_ENVELOPE, verify(envelope = it.toByteArray())) }
    }

    @Test fun base64urlRequiresNoPaddingAndCanonicalUnusedBits() {
        val text = envelope(payload).toString(Charsets.US_ASCII)
        listOf(text.replace("\",\"signature", "=\",\"signature"), text.replace("\"payload\":\"", "\"payload\":\"+")).forEach {
            failure(PersonalUpdateFailure.INVALID_ENVELOPE, verify(envelope = it.toByteArray()))
        }
        failure(PersonalUpdateFailure.INVALID_ENVELOPE,
            verify(envelope = "{\"payload\":\"AB\",\"signature\":\"AA\"}".toByteArray()))
    }

    @Test fun limitsRejectOversizedEnvelopeAndDecodedPayload() {
        failure(PersonalUpdateFailure.OVERSIZE, verify(envelope = ByteArray(8193) { 65 }))
        failure(PersonalUpdateFailure.OVERSIZE, verify(envelope = envelope("A".repeat(4097))))
    }

    @Test fun signedPayloadRequiresAsciiExactLinesAndTrailingLf() {
        assertTrue(verify() is PersonalUpdateVerification.Success)
        listOf(payload.dropLast(1), payload.replace("\n", "\r\n"), payload + "\n",
            payload + "unknown=x\n", payload.replace("versionName=1.0.2", "versionName=é"),
            payload.replace("sequence=1", "sequence=1\nsequence=1"),
            payload.replace("sequence=1\nissuedAt=1800000000", "issuedAt=1800000000\nsequence=1"),
            payload.replace("APOLLO-RGA-PERSONAL-OTA/1", "APOLLO-RGA-PERSONAL-OTA/2")
        ).forEach { failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = it)) }
    }

    @Test fun decimalsRejectLeadingZeroSignsWhitespaceAndOverflow() {
        assertTrue(verify() is PersonalUpdateVerification.Success)
        listOf("01", "-1", "+1", " 1", "1 ", "9223372036854775808", "1.0").forEach {
            failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = change("sequence", it)))
        }
        failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = change("sequence", "0")))
        failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = change("minSdk", "2147483648")))
    }

    @Test fun expiryAndClockSkewHaveExactBoundariesAndNoOverflow() {
        failure(PersonalUpdateFailure.INVALID_TIME, verify(text = change("expiresAt", now.toString())))
        failure(PersonalUpdateFailure.INVALID_TIME, verify(text = change("issuedAt", (now + 301).toString())))
        assertTrue(verify(text = change("issuedAt", (now + 300).toString())) is PersonalUpdateVerification.Success)
        failure(PersonalUpdateFailure.INVALID_TIME, verify(text = change("expiresAt", "1800604801")))
        assertTrue(verify(text = change("expiresAt", "1800604800")) is PersonalUpdateVerification.Success)
        failure(PersonalUpdateFailure.INVALID_TIME, verify(text = change("issuedAt", "1800003600")))
        failure(PersonalUpdateFailure.INVALID_TIME, verify(text = change("expiresAt", Long.MAX_VALUE.toString())))
        failure(PersonalUpdateFailure.INVALID_TIME, verify(at = Long.MAX_VALUE))
        failure(PersonalUpdateFailure.INVALID_TIME, verify(at = -1))
        val nearMax = change("issuedAt", (Long.MAX_VALUE - 300).toString())
            .replace("expiresAt=1800003600", "expiresAt=${Long.MAX_VALUE}")
        assertTrue(verify(text = nearMax, at = Long.MAX_VALUE - 301) is PersonalUpdateVerification.Success)
    }

    @Test fun versionMustBeStrictlyHigherThanInstalled() {
        listOf("1", "2").forEach { failure(PersonalUpdateFailure.DOWNGRADE, verify(text = change("versionCode", it))) }
    }

    @Test fun highWaterRejectsRollbackAndSameSequenceWithDifferentOrMissingHash() {
        val hash = digest(payload.toByteArray())
        assertTrue(verify(water = PersonalUpdateHighWater(1, hash)) is PersonalUpdateVerification.Success)
        failure(PersonalUpdateFailure.SEQUENCE_CONFLICT, verify(water = PersonalUpdateHighWater(1, hashA)))
        failure(PersonalUpdateFailure.SEQUENCE_CONFLICT, verify(water = PersonalUpdateHighWater(1, null)))
        failure(PersonalUpdateFailure.SEQUENCE_ROLLBACK, verify(water = PersonalUpdateHighWater(2, hash)))
        assertTrue(verify(text = change("sequence", "2"), water = PersonalUpdateHighWater(1, hash)) is PersonalUpdateVerification.Success)
    }

    @Test fun packageAndSignerMustMatchFixedPersonalBaseline() {
        failure(PersonalUpdateFailure.BASELINE_MISMATCH, verify(text = change("package", "org.example.app")))
        failure(PersonalUpdateFailure.BASELINE_MISMATCH, verify(installed = baseline.copy(packageName = "org.example.app")))
        failure(PersonalUpdateFailure.BASELINE_MISMATCH, verify(text = change("apkCertSha256", hashB)))
        failure(PersonalUpdateFailure.BASELINE_MISMATCH, verify(installed = baseline.copy(apkCertSha256 = hashB)))
    }

    @Test fun sdkAbiAndExactRelativeApkPathAreRequired() {
        listOf("22", "36").forEach { failure(PersonalUpdateFailure.INCOMPATIBLE_DEVICE, verify(text = change("minSdk", it))) }
        failure(PersonalUpdateFailure.INCOMPATIBLE_DEVICE, verify(text = change("abi", "x86_64")))
        failure(PersonalUpdateFailure.INCOMPATIBLE_DEVICE, verify(installed = baseline.copy(supportedAbis = setOf("x86_64"))))
        listOf("https://rga.apollot.ru/updates/personal/v1/3/apollo-rga-personal.apk", "/updates/personal/v1/2/apollo-rga-personal.apk", "/updates/personal/v1/3/../apollo-rga-personal.apk", "/updates/personal/v1/3/apollo-rga-personal.apk?x=1").forEach {
            failure(PersonalUpdateFailure.INVALID_PATH, verify(text = change("apkPath", it)))
        }
    }

    @Test fun companionMustMatchPackageVersionHashAndSavedSigner() {
        mapOf("companionPackage" to "org.example.service", "companionVersionCode" to "2",
            "companionSha256" to hashA, "companionCertSha256" to hashB).forEach { (key, value) ->
            failure(PersonalUpdateFailure.COMPANION_MISMATCH, verify(text = change(key, value)))
        }
        failure(PersonalUpdateFailure.COMPANION_MISMATCH, verify(installed = baseline.copy(companionPackage = "org.example.service")))
        failure(PersonalUpdateFailure.COMPANION_MISMATCH, verify(installed = baseline.copy(companionCertSha256 = hashB)))
    }

    @Test fun frozenCompanionVersionZeroIsAcceptedOnlyWhenInstalledBaselineMatches() {
        val frozenPayload = change("companionVersionCode", "0")
        val frozenBaseline = baseline.copy(companionVersionCode = 0)
        assertTrue("canonical companion version zero must pass against frozen baseline",
            verify(text = frozenPayload, installed = frozenBaseline) is PersonalUpdateVerification.Success)
        failure(PersonalUpdateFailure.COMPANION_MISMATCH, verify(text = frozenPayload))
        failure(PersonalUpdateFailure.COMPANION_MISMATCH, verify(installed = frozenBaseline))
        failure(PersonalUpdateFailure.INVALID_SCHEMA,
            verify(text = change("companionVersionCode", "-1"), installed = frozenBaseline))
        failure(PersonalUpdateFailure.INVALID_SCHEMA,
            verify(text = change("companionVersionCode", "00"), installed = frozenBaseline))
    }

    @Test fun apkSizeAndVersionNameAndHexHaveExactBounds() {
        listOf("0", "268435457").forEach { failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = change("apkBytes", it))) }
        listOf("1", "268435456").forEach { assertTrue(verify(text = change("apkBytes", it)) is PersonalUpdateVerification.Success) }
        listOf("", "v".repeat(65), "v 3", "v/3").forEach { failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = change("versionName", it))) }
        assertTrue(verify(text = change("versionName", "v".repeat(64))) is PersonalUpdateVerification.Success)
        listOf("apkSha256", "apkCertSha256", "companionSha256", "companionCertSha256").forEach { field ->
            listOf("A".repeat(64), "a".repeat(63), "g".repeat(64)).forEach { value ->
                failure(PersonalUpdateFailure.INVALID_SCHEMA, verify(text = change(field, value)))
            }
        }
    }

    private fun change(field: String, value: String): String = payload.replace(Regex("(?m)^$field=.*$"), "$field=$value")
    private fun verify(text: String = payload, envelope: ByteArray = envelope(text), pin: ByteArray = keys.public.encoded,
        installed: PersonalUpdateBaseline = baseline, water: PersonalUpdateHighWater = PersonalUpdateHighWater(0, null), at: Long = now
    ) = PersonalUpdateManifestVerifier.verify(envelope, pin, installed, water, at)
    private fun failure(reason: PersonalUpdateFailure, result: PersonalUpdateVerification) {
        assertEquals(PersonalUpdateVerification.Failure(reason), result)
    }
    private fun envelope(text: String, pair: KeyPair = keys, signature: ByteArray? = null): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val signed = signature ?: Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(bytes) }.sign()
        return "{\"payload\":\"${b64(bytes)}\",\"signature\":\"${b64(signed)}\"}".toByteArray(Charsets.US_ASCII)
    }
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun ecKeys(curve: String) = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
}
