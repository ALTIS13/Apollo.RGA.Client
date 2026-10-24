package org.olcbox.app.update.personal

import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Prepared SOURCE regressions; keys below are synthetic and generated only during tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublicOwnServerUpdateTest {
    @Before fun arm64Fixture() { ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a")) }
    @Test fun signedPublicWithoutCompanionIsSeparateFromUnchangedPersonalGrammar() {
        val f = Fixture()
        assertTrue(f.verify(f.signed()) is PersonalUpdateVerification.Success)
        assertEquals(listOf(PACKAGE), f.lookups.distinct())
        assertFailure(PersonalUpdateFailure.INVALID_SCHEMA, PersonalUpdateManifestVerifier.verify(
            f.signed(), f.key.public.encoded, f.baseline, PersonalUpdateHighWater(0, null), f.clock))
        val personal = AndroidPersonalApkVerifierTest.Fixture(); val m = personal.manifest
        val payload = "APOLLO-RGA-PERSONAL-OTA/1\nsequence=1\nissuedAt=${m.issuedAt}\nexpiresAt=${m.expiresAt}\n" +
            "package=${m.packageName}\nversionCode=${m.versionCode}\nversionName=${m.versionName}\nminSdk=${m.minSdk}\nabi=${m.abi}\n" +
            "apkPath=${m.apkPath}\napkBytes=${m.apkBytes}\napkSha256=${m.apkSha256}\napkCertSha256=${m.apkCertSha256}\n" +
            "companionPackage=${m.companionPackage}\ncompanionVersionCode=${m.companionVersionCode}\n" +
            "companionSha256=${m.companionSha256}\ncompanionCertSha256=${m.companionCertSha256}\n"
        assertTrue(PersonalUpdateManifestVerifier.verify(f.sign(payload), f.key.public.encoded,
            personal.baseline, PersonalUpdateHighWater(0, null), personal.clock) is PersonalUpdateVerification.Success)
        assertFailure(PersonalUpdateFailure.INVALID_SCHEMA, f.verify(f.sign(payload)))
    }

    @Test fun authenticatedWrongChannelPackageSignerPathAndDowngradeNeverCommit() = runBlocking {
        for ((field, value, expected) in listOf(
            Triple("channel", "other-channel", PersonalUpdateFailure.BASELINE_MISMATCH),
            Triple("package", "tech.gatealtas.rga.personal", PersonalUpdateFailure.BASELINE_MISMATCH),
            Triple("apkCertSha256", "b".repeat(64), PersonalUpdateFailure.BASELINE_MISMATCH),
            Triple("apkPath", "/updates/personal/v1/3/apollo-rga-personal.apk", PersonalUpdateFailure.INVALID_PATH),
            Triple("versionCode", "1", PersonalUpdateFailure.DOWNGRADE))) {
            val f = Fixture(); f.envelope = f.signed(mapOf(field to value))
            assertFailure(expected, f.verify(f.envelope)); f.controller.manualCheck()
            assertTrue(f.controller.state.value is PersonalUpdateState.Rejected)
            assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
            assertEquals(0, f.handoffs)
        }
    }

    @Test fun absentBindingsWrongVariantAndDebugAreInertBeforeRouteOrController() {
        val context = publicContext()
        val pin = Base64.getEncoder().encodeToString(Fixture().key.public.encoded); var routes = 0
        fun create(enabled: Boolean, variant: String, spki: String, signer: String, debug: Boolean = false) =
            AndroidPublicOwnServerUpdateRuntimeFactory.create(context, enabled, variant, spki, signer, debug) {
                routes++; PersonalUpdateFetchRoute.Blocked(PersonalUpdateRouteBlock.NOT_READY)
            }
        assertNull(create(false, "githubRelease", pin, CERT))
        assertNull(create(true, "playRelease", pin, CERT)); assertNull(create(true, "githubDebug", pin, CERT))
        assertNull(create(true, "githubRelease", "", CERT)); assertNull(create(true, "githubRelease", pin, ""))
        assertNull(create(true, "githubRelease", pin, CERT, true)); assertEquals(0, routes)
        assertNotNull(create(true, "githubRelease", pin, CERT)); assertEquals(0, routes)
    }

    @Test fun archiveAndInstalledSignerChannelPackageAndOwnedHashAreAllRequired() {
        for (fault in listOf("signer", "channel", "package", "hash", "installed-signer", "installed-channel")) {
            val f = Fixture(); val m = f.verify(f.envelope) as PersonalUpdateVerification.Success
            when (fault) {
                "signer" -> f.archive = f.archive.copy(certSha256 = "b".repeat(64))
                "channel" -> f.archive = f.archive.copy(channel = "other-channel")
                "package" -> f.archive = f.archive.copy(packageName = "tech.gatealtas.rga.personal")
                "hash" -> f.download.appendBytes(byteArrayOf(1))
                "installed-signer" -> f.installed = f.installed.copy(certSha256 = "b".repeat(64))
                "installed-channel" -> f.installed = f.installed.copy(channel = "other-channel")
            }
            assertTrue(f.gate.verifyApk(f.download, m, f.baseline) is PersonalApkVerification.Refused)
            assertFalse(f.dir.listFiles()!!.any { it.name.startsWith("verified-") })
        }
    }

    @Test fun publicOlcTicketNeverReachesHttpOrCommits() = runBlocking {
        val f = Fixture()
        f.route = PersonalUpdateFetchRoute.Selected(PersonalUpdateFetchTicket(
            PersonalUpdateSocksEndpoint("127.0.0.1", 1080, null, null), { true }, { _, action -> action(); true }))
        f.controller.manualCheck(); assertEquals(PersonalUpdateState.Unavailable, f.controller.state.value)
        assertEquals(0, f.requests); assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
    }

    @Test fun suspendedOrdinaryResponseRetirementAndCancellationHaveNoFallback() = runBlocking {
        for (cancel in listOf(false, true)) {
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.beforeResponse = { entered.complete(Unit); release.await() }
            val check = launch { f.controller.manualCheck() }; withTimeout(5000) { entered.await() }
            if (cancel) check.cancelAndJoin() else { f.live = false; release.complete(Unit); check.join() }
            assertEquals(if (cancel) PersonalUpdateState.Idle else PersonalUpdateState.SessionChanged, f.controller.state.value)
            assertEquals(1, f.requests); assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
        }
    }

    @Test fun publicHighWaterCannotConsumePersonalHeaderOrResetCorruption() = runBlocking {
        val f = Fixture(); f.controller.manualCheck(); assertTrue(f.controller.state.value is PersonalUpdateState.Available)
        assertEquals(PersonalHighWaterRead.Corrupt, PersonalUpdateHighWaterStore(f.waterFile).read())
        f.waterFile.writeText("corrupt"); f.controller.manualCheck()
        assertTrue(f.controller.state.value is PersonalUpdateState.Rejected); assertEquals("corrupt", f.waterFile.readText())
    }

    @Test fun downloadHashFailureAndCancelledDownloadNeverOfferInstallOrTouchUserData() = runBlocking {
        for (cancel in listOf(false, true)) {
            val f = Fixture(); f.controller.manualCheck()
            if (cancel) {
                val entered = CompletableDeferred<Unit>(); f.beforeResponse = { entered.complete(Unit); awaitCancellation() }
                val job = launch { f.controller.download() }; withTimeout(5000) { entered.await() }; job.cancelAndJoin()
                assertEquals(PersonalUpdateState.Idle, f.controller.state.value)
            } else { f.apk += byteArrayOf(1); f.controller.download(); assertTrue(f.controller.state.value is PersonalUpdateState.Rejected) }
            assertTrue(f.controller.install(true) is PersonalInstallResult.Refused); assertEquals(0, f.handoffs)
            assertEquals("subscription and installation stay", f.userData.readText())
        }
    }

    @Test fun explicitSameFenceInstallerOnlyHandsOffAndPreservesData() = runBlocking {
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(true)
        val f = Fixture(); f.controller.manualCheck(); f.controller.download()
        assertTrue(f.controller.state.value is PersonalUpdateState.Ready)
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.OWNER_ACTION_REQUIRED), f.controller.install(false))
        f.live = false; assertTrue(f.controller.install(true) is PersonalInstallResult.Refused); assertEquals(0, f.handoffs)
        val good = Fixture(); good.controller.manualCheck(); good.controller.download()
        assertEquals(PersonalInstallResult.HandedOff, good.controller.install(true))
        assertEquals(PersonalUpdateState.HandedOff, good.controller.state.value); assertEquals(1, good.handoffs)
        assertEquals("subscription and installation stay", good.userData.readText())
        assertTrue(good.dir.listFiles()!!.any { it.name.startsWith("verified-") })
    }

    private class Fixture {
        val profile = OwnServerUpdateProfile.publicBinding(CERT)!!
        val dir = Files.createTempDirectory("public-ota-test").toFile()
        val userData = File(dir, "subscription-data").apply { writeText("subscription and installation stay") }
        val installedFile = File(dir, "installed.apk").apply { writeText("installed baseline") }
        var clock = 1000L; var live = true; var requests = 0; var handoffs = 0
        val lookups = mutableListOf<String>()
        var installed = PersonalApkMetadata(PACKAGE, 1, CERT, 23, setOf("arm64-v8a"), installedFile, CHANNEL)
        var archive = installed.copy(versionCode = 3, source = null)
        val reader = object : PersonalApkMetadataReader {
            override fun installed(packageName: String): PersonalApkMetadata? {
                lookups += packageName; check(packageName == PACKAGE) { "Public updater queried companion" }; return installed
            }
            override fun archive(file: File) = archive
        }
        val gate = AndroidPersonalApkVerifier(dir, reader, 35, setOf("arm64-v8a"), { clock }, profile)
        val download = gate.newDownloadFile().apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libcore.so")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
        }
        var apk = download.readBytes()
        val baseline = (gate.readBaseline() as PersonalBaselineRead.Ready).baseline
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val waterFile = File(dir, "public-high-water"); val store = PersonalUpdateHighWaterStore(waterFile, profile)
        var route: PersonalUpdateFetchRoute = PersonalUpdateFetchRoute.Ordinary(org.robolectric.shadows.ShadowNetwork.newInstance(71), { live },
            { action -> if (!live) false else { action(); true } }, profile = profile)
        var beforeResponse: suspend () -> Unit = {}; var envelope = signed()
        val installer = AndroidPersonalUpdateInstaller(object : ContextWrapper(publicContext()) {
            override fun startActivity(intent: android.content.Intent) { handoffs++ }
        }, gate) { Uri.parse("content://tech.gatealtas.rga.fileprovider/apk") }
        val controller = PersonalUpdateController(key.public.encoded, gate::readBaseline, store, gate, installer,
            { route }, object : PersonalUpdateLastCheckStore {
                override fun read(): Long? = null
                override fun write(epochSeconds: Long) = true
            }, { clock }, object : PersonalUpdateHttp {
                override suspend fun open(route: PersonalUpdateFetchRoute, exactUrl: String): PersonalUpdateHttpResponse {
                    requests++; beforeResponse()
                    return PersonalUpdateHttpResponse(200, ByteArrayInputStream(if (exactUrl == profile.manifestUrl) envelope else apk))
                }
            }, profile = profile)
        fun verify(bytes: ByteArray) = PersonalUpdateManifestVerifier.verify(bytes, key.public.encoded, baseline,
            PersonalUpdateHighWater(0, null), clock, profile)
        fun signed(changes: Map<String, String> = emptyMap()): ByteArray {
            val values = linkedMapOf("sequence" to "1", "issuedAt" to "900", "expiresAt" to "2000",
                "package" to PACKAGE, "versionCode" to "3", "versionName" to "3.0", "minSdk" to "23",
                "abi" to "arm64-v8a", "apkPath" to "/updates/public/v1/3/apollo-rga.apk",
                "apkBytes" to apk.size.toString(), "apkSha256" to hex(MessageDigest.getInstance("SHA-256").digest(apk)),
                "apkCertSha256" to CERT, "channel" to CHANNEL)
            changes.forEach { (field, value) -> values[field] = value }
            return sign("APOLLO-RGA-PUBLIC-OTA/1\n" + values.entries.joinToString("") { "${it.key}=${it.value}\n" })
        }
        fun sign(payload: String): ByteArray {
            val bytes = payload.toByteArray(Charsets.US_ASCII)
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(bytes) }.sign()
            val b64 = Base64.getUrlEncoder().withoutPadding()
            return "{\"payload\":\"${b64.encodeToString(bytes)}\",\"signature\":\"${b64.encodeToString(signature)}\"}".toByteArray()
        }
    }
    companion object {
        private const val PACKAGE = "tech.gatealtas.rga"
        private const val CHANNEL = "own-server-stable"
        private val CERT = "a".repeat(64) // Only synthetic; no production signer is bound.
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun assertFailure(reason: PersonalUpdateFailure, result: PersonalUpdateVerification) =
            assertEquals(PersonalUpdateVerification.Failure(reason), result)
        private fun publicContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = PACKAGE
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo() = android.content.pm.ApplicationInfo(super.getApplicationInfo()).apply {
                flags = flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
        }
    }
}
