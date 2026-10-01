package org.olcbox.app.update.personal

import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Public-only publisher artifacts; absent opt-in skips, a present broken fixture fails. */
class PersonalUpdatePublisherRoundtripTest {
    @Test fun publisherFixturePassesTheProductionVerifier() {
        val directory = System.getenv("RGA_PERSONAL_OTA_FIXTURE_DIR")
        assumeTrue("publisher fixture opt-in absent", directory != null)
        require(!directory.isNullOrBlank()) { "Invalid fixture directory" }
        val dir = Paths.get(directory)
        val props = Properties().apply {
            Files.newInputStream(dir.resolve("baseline.properties")).use { load(it) }
        }
        fun text(name: String): String = props.getProperty(name)?.takeIf { it.isNotEmpty() }
            ?: error("Missing fixture property")
        fun number(name: String): Long = text(name).takeIf { Regex("0|[1-9][0-9]*").matches(it) }
            ?.toLongOrNull() ?: error("Malformed numeric fixture property")
        val sdk = number("sdk").takeIf { it <= Int.MAX_VALUE }?.toInt() ?: error("Invalid fixture SDK")
        val baseline = PersonalUpdateBaseline(text("packageName"), number("versionCode"), text("apkCertSha256"),
            sdk, text("supportedAbis").split(',').toSet(), text("companionPackage"),
            number("companionVersionCode"), text("companionSha256"), text("companionCertSha256"))
        val water = PersonalUpdateHighWater(number("highWaterSequence"),
            props.getProperty("highWaterPayloadSha256")?.takeIf { it.isNotEmpty() })
        val bytes = Files.readAllBytes(dir.resolve("manifest.json"))
        val publicSpki = Files.readAllBytes(dir.resolve("manifest-public-spki.der"))
        val result = PersonalUpdateManifestVerifier.verify(bytes, publicSpki, baseline, water, number("nowEpochSeconds"))
        assertTrue("publisher manifest must pass production verifier", result is PersonalUpdateVerification.Success)
        val success = result as PersonalUpdateVerification.Success
        val wire = bytes.toString(Charsets.US_ASCII)
        val match = Regex("\\{\"payload\":\"([A-Za-z0-9_-]+)\",\"signature\":\"([A-Za-z0-9_-]+)\"\\}").matchEntire(wire)
        assertTrue("publisher envelope must be exact canonical v1", match != null)
        val payload = Base64.getUrlDecoder().decode(match!!.groupValues[1])
        val signature = Base64.getUrlDecoder().decode(match.groupValues[2])
        val encode = Base64.getUrlEncoder().withoutPadding()
        assertEquals("{\"payload\":\"${encode.encodeToString(payload)}\",\"signature\":\"${encode.encodeToString(signature)}\"}", wire)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        assertEquals(hash, success.payloadSha256)
        assertTrue(success.manifest.versionCode > baseline.versionCode)
    }
}
