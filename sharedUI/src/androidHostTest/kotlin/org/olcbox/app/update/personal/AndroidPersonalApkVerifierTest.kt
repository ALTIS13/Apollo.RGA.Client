package org.olcbox.app.update.personal

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidPersonalApkVerifierTest {
    @Test fun freezesActualBytesAndRevalidatesBeforeHandoff() {
        val f = Fixture()
        val token = (f.verify() as? PersonalApkVerification.Verified)?.apk
        assertNotNull("matching actual APK bytes and installed metadata must pass", token)
        f.download.writeText("changed original download")
        assertTrue(f.gate.revalidate(token!!) is PersonalApkVerification.Verified)
        val frozen = f.dir.listFiles()!!.single { it.name.startsWith("verified-") }
        frozen.setWritable(true)
        frozen.writeText("changed frozen APK")
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE), f.gate.revalidate(token))
    }
    @Test fun changedHashSizeMissingOrUnownedFileNeverCreatesToken() {
        for (mutation in listOf<(Fixture) -> Unit>(
            { it.download.appendText("extra") },
            { val b = it.download.readBytes(); b[b.lastIndex] = (b.last().toInt() xor 1).toByte(); it.download.writeBytes(b) },
            { it.download.delete() },
        )) {
            val f = Fixture(); mutation(f)
            assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE), f.verify())
        }
        val f = Fixture()
        val outside = File(f.dir.parentFile, "outside-${System.nanoTime()}.apk").apply { writeBytes(f.download.readBytes()) }
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE), f.gate.verifyApk(outside, f.verified, f.baseline))
    }
    @Test fun archiveMustMatchPackageVersionCertificateSdkAndAbi() {
        for (change in listOf<(PersonalApkMetadata) -> PersonalApkMetadata>(
            { it.copy(packageName = "other") }, { it.copy(versionCode = 2) },
            { it.copy(certSha256 = "a".repeat(64)) }, { it.copy(minSdk = 24) },
            { it.copy(abis = setOf("x86_64")) },
        )) {
            val f = Fixture(); f.archive = change(f.archive)
            assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.METADATA), f.verify())
        }
    }
    @Test fun companionVersionZeroBaselineAndSignerAreRequiredAgainAtInstallTime() {
        val f = Fixture()
        assertEquals(PersonalBaselineRead.Ready(f.baseline), f.gate.readBaseline())
        val token = (f.verify() as PersonalApkVerification.Verified).apk
        f.companion.writeText("companion changed")
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.BASELINE), f.gate.revalidate(token))
        val g = Fixture(); g.installedCompanion = g.installedCompanion.copy(versionCode = 1)
        assertEquals(PersonalBaselineRead.Refused, g.gate.readBaseline())
    }
    @Test fun expiredOrWrongBaselineAndCrossVerifierTokenFailClosed() {
        val f = Fixture(); f.clock = 2000
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.EXPIRED), f.verify())
        val g = Fixture()
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.BASELINE),
            g.gate.verifyApk(g.download, g.verified, g.baseline.copy(apkCertSha256 = "f".repeat(64))))
        val token = (g.verify() as PersonalApkVerification.Verified).apk
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE), Fixture().gate.revalidate(token))
    }
    @Test fun downloadNamesAreUniqueAndBelowPersonalDirectory() {
        val f = Fixture(); val other = f.gate.newDownloadFile()
        assertNotEquals(f.download, other)
        assertEquals(f.dir.canonicalFile, other.canonicalFile.parentFile)
    }
    @Test fun ownProducerFromDotDotCacheDirectoryVerifiesAndItsFrozenTokenRevalidates() {
        val f = Fixture()
        val child = File(f.dir, "alias-child").apply { assertTrue(mkdirs()) }
        val aliasedCache = File(child, "..")
        assertNotEquals("fixture must contain a real path alias", aliasedCache.absoluteFile, aliasedCache.canonicalFile)
        assertEquals(f.dir.canonicalFile, aliasedCache.canonicalFile)
        val gate = AndroidPersonalApkVerifier(aliasedCache, f.reader, 35, setOf("arm64-v8a")) { f.clock }
        val produced = gate.newDownloadFile().apply { writeBytes(f.download.readBytes()) }
        assertEquals(PersonalBaselineRead.Ready(f.baseline), gate.readBaseline())
        val result = gate.verifyApk(produced, f.verified, f.baseline)
        assertTrue("own matching producer must not be rejected because its cache path is aliased",
            result is PersonalApkVerification.Verified)
        val token = (result as PersonalApkVerification.Verified).apk
        assertEquals(PersonalApkVerification.Verified(token), gate.revalidate(token))
    }
    @Test fun callerDotDotAliasOfRegisteredProducerIsStillRefused() {
        val f = Fixture()
        val child = File(f.dir, "caller-alias-child").apply { assertTrue(mkdirs()) }
        val alias = File(child, "../${f.download.name}")
        assertNotEquals(alias.absoluteFile, alias.canonicalFile)
        assertEquals(f.download.canonicalFile, alias.canonicalFile)
        assertArrayEquals(f.download.readBytes(), alias.readBytes())
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE),
            f.gate.verifyApk(alias, f.verified, f.baseline))
        assertTrue("refusing a caller alias must not poison the registered real producer",
            f.verify() is PersonalApkVerification.Verified)
    }
    @Test fun callerSymlinkOutsideCacheToRegisteredProducerIsStillRefused() {
        val f = Fixture()
        val alias = File(f.dir.parentFile, "caller-link-${System.nanoTime()}.apk")
        try {
            Files.createSymbolicLink(alias.toPath(), f.download.canonicalFile.toPath())
        } catch (unsupported: Exception) {
            assumeNoException("host must support symbolic links for this negative boundary proof", unsupported)
            return
        }
        try {
            assertTrue(Files.isSymbolicLink(alias.toPath()))
            assertEquals(f.download.canonicalFile, alias.canonicalFile)
            assertArrayEquals(f.download.readBytes(), alias.readBytes())
            assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE),
                f.gate.verifyApk(alias, f.verified, f.baseline))
            assertTrue(f.verify() is PersonalApkVerification.Verified)
        } finally { Files.deleteIfExists(alias.toPath()) }
    }
    @Test fun metadataChangeExpiryAndRemovalInvalidatePreviouslyVerifiedCandidate() {
        val changed = Fixture(); val token = (changed.verify() as PersonalApkVerification.Verified).apk
        changed.archive = changed.archive.copy(versionCode = 4)
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.METADATA), changed.gate.revalidate(token))
        val expired = Fixture(); val expiredToken = (expired.verify() as PersonalApkVerification.Verified).apk
        expired.clock = 2000
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.EXPIRED), expired.gate.revalidate(expiredToken))
        val removed = Fixture(); val removedToken = (removed.verify() as PersonalApkVerification.Verified).apk
        removed.dir.listFiles()!!.single { it.name.startsWith("verified-") }.apply { setWritable(true); delete() }
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.FILE), removed.gate.revalidate(removedToken))
    }
    @Test fun api23RefusesWithoutInvokingMetadataAndZipAbiMustMatchActualBytes() {
        val f = Fixture()
        val unsupported = AndroidPersonalApkVerifier(f.dir, f.reader, 23, setOf("arm64-v8a")) { 1000 }
        assertEquals(PersonalBaselineRead.Refused, unsupported.readBaseline())
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.UNSUPPORTED_PLATFORM),
            unsupported.verifyApk(f.download, f.verified, f.baseline))
        val g = Fixture()
        ZipOutputStream(g.download.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/x86_64/libtest.so")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
        }
        val candidate = g.verified.copy(manifest = g.manifest.copy(apkBytes = g.download.length(), apkSha256 = sha(g.download)))
        assertEquals(PersonalApkVerification.Refused(PersonalApkRefusal.METADATA), g.gate.verifyApk(g.download, candidate, g.baseline))
    }

    internal class Fixture {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "updates/personal/test-${System.nanoTime()}").apply { mkdirs() }
        val companion = File(dir.parentFile, "companion-${System.nanoTime()}.apk").apply { writeText("frozen companion bytes") }
        var clock = 1000L
        var archive = PersonalApkMetadata(PACKAGE, 3, CERT, 23, setOf("arm64-v8a"))
        var installedMain = archive.copy(versionCode = 1)
        var installedCompanion = archive.copy(packageName = COMPANION, versionCode = 0, source = companion)
        val reader = object : PersonalApkMetadataReader {
            override fun installed(packageName: String) = if (packageName == PACKAGE) installedMain else installedCompanion
            override fun archive(file: File) = archive
        }
        val gate = AndroidPersonalApkVerifier(dir, reader, 35, setOf("arm64-v8a")) { clock }
        val download = gate.newDownloadFile().apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libtest.so")); zip.write(byteArrayOf(1,2,3)); zip.closeEntry()
            }
        }
        val baseline = PersonalUpdateBaseline(PACKAGE, 1, CERT, 35, setOf("arm64-v8a"), COMPANION, 0, sha(companion), CERT)
        val manifest = PersonalUpdateManifest(1, 1000, 2000, PACKAGE, 3, "1.0.2", 23, "arm64-v8a",
            "/updates/personal/v1/3/apollo-rga-personal.apk", download.length(), sha(download), CERT, COMPANION, 0, sha(companion), CERT)
        val verified = PersonalUpdateVerification.Success(manifest, "b".repeat(64))
        fun verify() = gate.verifyApk(download, verified, baseline)
    }
    companion object {
        const val PACKAGE = "tech.gatealtas.rga.personal"
        const val COMPANION = "tech.gatealtas.rga.personal.test"
        const val CERT = "11cfb810016a9f39d75cc517e9f3a8c731e6d0b4e9156e5872c6138cadd3a017"
        fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
