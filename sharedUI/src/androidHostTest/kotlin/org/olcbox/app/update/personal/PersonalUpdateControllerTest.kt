package org.olcbox.app.update.personal

import android.content.ContextWrapper
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalUpdateControllerTest {
    @Test fun blockedRouteNeverCreatesHttpAndAbsentPinNeverChecks() = runBlocking {
        val f = Fixture(); f.route = PersonalUpdateFetchRoute.Blocked(PersonalUpdateRouteBlock.NOT_READY)
        f.controller.manualCheck(); assertEquals(PersonalUpdateState.Unavailable, f.controller.state.value)
        assertEquals(0, f.requests)
        val disabled = f.controller(null); disabled.manualCheck(); assertEquals(0, f.requests)
    }
    @Test fun realSignedManifestThenActualApkBytesProduceReadyWithoutInstaller() = runBlocking {
        val f = Fixture(); f.controller.manualCheck()
        assertTrue(f.controller.state.value is PersonalUpdateState.Available)
        f.controller.download(); assertTrue(f.controller.state.value is PersonalUpdateState.Ready)
        assertEquals(2, f.requests); assertEquals(0, f.handoffs)
        assertEquals(0, f.artifact.dir.listFiles()!!.count { it.name.startsWith("download-") && it != f.artifact.download })
    }
    @Test fun badRealSignatureCannotAcceptHighWaterOrDownload() = runBlocking {
        val f = Fixture(); val other = Fixture(); f.envelope = other.envelope
        f.artifact.installedMain = f.artifact.installedMain.copy(versionCode = 3)
        f.controller.manualCheck()
        assertEquals(PersonalUpdateState.Rejected(PersonalUpdateControllerRefusal.MANIFEST), f.controller.state.value)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
        f.controller.download(); assertEquals(1, f.requests)
        assertTrue(f.controller.install(true) is PersonalInstallResult.Refused)
        assertEquals(0, f.handoffs)
    }
    @Test fun signedEqualVersionIsNotNewerWithoutCandidateCommitOrInstaller() = runBlocking {
        assertNotNewerWithoutSideEffects(installedVersion = 3)
    }
    @Test fun signedOlderVersionIsNotNewerWithoutCandidateCommitOrInstaller() = runBlocking {
        assertNotNewerWithoutSideEffects(installedVersion = 4)
    }
    @Test fun expiredEqualVersionIsStillManifestRefusalNotArithmeticInformation() = runBlocking {
        val f = Fixture(); f.artifact.installedMain = f.artifact.installedMain.copy(versionCode = 3)
        f.artifact.clock = 2000
        f.controller.manualCheck()
        assertEquals(PersonalUpdateState.Rejected(PersonalUpdateControllerRefusal.MANIFEST), f.controller.state.value)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
        assertEquals(0, f.handoffs)
    }
    @Test fun nonNewerCheckRetiresPreviouslyAcceptedCandidateWithoutChangingHighWater() = runBlocking {
        val f = Fixture(); f.controller.manualCheck()
        assertTrue(f.controller.state.value is PersonalUpdateState.Available)
        val acceptedHighWater = f.store.read()
        f.artifact.installedMain = f.artifact.installedMain.copy(versionCode = 3)
        f.controller.manualCheck()
        val state = f.controller.state.value
        assertTrue(state is PersonalUpdateState.Rejected)
        assertEquals("NOT_NEWER", (state as PersonalUpdateState.Rejected).reason.name)
        assertEquals(acceptedHighWater, f.store.read())
        f.controller.download()
        assertTrue(f.controller.install(true) is PersonalInstallResult.Refused)
        assertEquals(2, f.requests)
        assertEquals(0, f.handoffs)
    }
    @Test fun routeRetirementDominatesSuspendedNonNewerResponse() = runBlocking {
        val f = Fixture(); f.artifact.installedMain = f.artifact.installedMain.copy(versionCode = 3)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeResponse = { entered.complete(Unit); release.await() }
        val check = launch { f.controller.manualCheck() }
        withTimeout(5000) { entered.await() }
        f.live = false; release.complete(Unit); check.join()
        assertEquals(PersonalUpdateState.SessionChanged, f.controller.state.value)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
        assertEquals(1, f.requests)
        assertEquals(0, f.handoffs)
    }
    @Test fun redirectAndOversizeNeverAcceptManifest() = runBlocking {
        for (mode in 0..1) { val f = Fixture(); if (mode == 0) f.code = 302 else f.envelope = ByteArray(8193)
            f.controller.manualCheck(); assertTrue(f.controller.state.value is PersonalUpdateState.Rejected)
            assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read()) }
    }
    @Test fun routeRetiredDuringSuspendedResponseNeverCommitsOrFallsBack() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeResponse = { entered.complete(Unit); release.await() }
        val check = launch { f.controller.manualCheck() }; withTimeout(5000) { entered.await() }; f.live = false; release.complete(Unit); check.join()
        assertEquals(PersonalUpdateState.SessionChanged, f.controller.state.value)
        assertEquals(1, f.requests)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
    }
    @Test fun bodyRetirementNeverProducesAvailable() = runBlocking {
        val f = Fixture(); f.body = { bytes -> object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); f.live = false; return n }
        } }
        f.controller.manualCheck(); assertEquals(PersonalUpdateState.SessionChanged, f.controller.state.value)
    }
    @Test fun retiredBodyIOExceptionIsSessionChangedNotNetworkFailure() = runBlocking {
        val f = Fixture(); f.body = { _ -> object : InputStream() {
            override fun read(): Int { f.live = false; throw java.io.IOException("not exported") }
        } }
        f.controller.manualCheck(); assertEquals(PersonalUpdateState.SessionChanged, f.controller.state.value)
    }
    @Test fun hashMismatchAndOversizedApkRemoveOnlyOwnedTemporaryFile() = runBlocking {
        for (extra in listOf(false, true)) { val f = Fixture(); f.controller.manualCheck()
            f.apk = if (extra) f.apk + byteArrayOf(1) else f.apk.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            f.controller.download(); assertTrue(f.controller.state.value is PersonalUpdateState.Rejected)
            assertTrue(f.artifact.download.exists())
            assertEquals(1, f.artifact.dir.listFiles()!!.count { it.name.startsWith("download-") }) }
    }
    @Test fun newerHighWaterRetiresAnOlderAvailableCandidate() = runBlocking {
        val f = Fixture(); f.controller.manualCheck()
        val newer = f.verified(f.signed(sequence = 2))
        assertEquals(PersonalHighWaterCommit.STORED, f.store.commit(newer) { action -> action(); true })
        f.controller.download(); assertTrue(f.controller.state.value is PersonalUpdateState.Rejected)
        assertEquals(1, f.requests)
    }
    @Test fun expirationAndBaselineDriftPreventInstall() = runBlocking {
        for (expired in listOf(false, true)) { val f = Fixture(); f.controller.manualCheck(); f.controller.download()
            assertTrue(f.controller.state.value is PersonalUpdateState.Ready)
            if (expired) f.artifact.clock = 2000 else f.artifact.installedMain = f.artifact.installedMain.copy(versionCode = 2)
            assertTrue(f.controller.install(true) is PersonalInstallResult.Refused); assertEquals(0, f.handoffs) }
    }
    @Test fun ownerActionIsRequiredAndPermissionResultDoesNotOpenSettings() = runBlocking {
        val f = Fixture(); f.controller.manualCheck(); f.controller.download()
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(false)
        assertEquals(PersonalInstallResult.Refused(PersonalInstallRefusal.OWNER_ACTION_REQUIRED), f.controller.install(false))
        assertEquals(0, f.handoffs)
        assertTrue(f.controller.install(true) is PersonalInstallResult.PermissionRequired)
        assertEquals(0, f.handoffs)
    }
    @Test fun explicitPermittedInstallHandsOffExactlyOnceAndPreservesFrozenBytes() = runBlocking {
        val f = Fixture(); f.controller.manualCheck(); f.controller.download()
        assertTrue(f.controller.state.value is PersonalUpdateState.Ready)
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(true)
        assertEquals(PersonalInstallResult.HandedOff, f.controller.install(true))
        assertEquals(PersonalUpdateState.HandedOff, f.controller.state.value); assertEquals(1, f.handoffs)
        assertTrue(f.artifact.dir.listFiles()!!.single { it.name.startsWith("verified-") }.exists())
        assertTrue(f.controller.install(true) is PersonalInstallResult.Refused); assertEquals(1, f.handoffs)
    }
    @Test fun foregroundGateIsDurableSixHoursAndManualRemainsExplicit() = runBlocking {
        val f = Fixture(); f.controller.foregroundCheck(false); assertEquals(0, f.requests)
        f.controller.foregroundCheck(true); assertEquals(1, f.requests)
        f.controller().foregroundCheck(true); assertEquals(1, f.requests)
        f.artifact.clock += 21599; f.controller.foregroundCheck(true); assertEquals(1, f.requests)
        f.artifact.clock++; f.controller.foregroundCheck(true); assertEquals(2, f.requests)
        f.controller.manualCheck(); assertEquals(3, f.requests)
    }
    @Test fun realDurableForegroundStoreSurvivesControllerAndStoreReopen() = runBlocking {
        val f = Fixture(); val app = RuntimeEnvironment.getApplication()
        val first = f.controller(lastStore = AndroidPersonalUpdateLastCheckStore(app))
        assertEquals(0, f.requests); first.foregroundCheck(true); assertEquals(1, f.requests)
        val reopened = AndroidPersonalUpdateLastCheckStore(app)
        assertEquals(1000L, reopened.read())
        f.controller(lastStore = reopened).foregroundCheck(true); assertEquals(1, f.requests)
    }
    @Test fun cancelledSuspendedResponseLeavesHighWaterUntouched() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>()
        f.beforeResponse = { entered.complete(Unit); awaitCancellation() }
        val job = launch { f.controller.manualCheck() }; withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
        assertEquals(0, f.handoffs)
    }
    @Test fun cancellationQueuedAtAuthorityCannotPersistHighWater() = runBlocking {
        val f = Fixture(); val lock = Any(); val held = CountDownLatch(1); val release = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val holder = thread { synchronized(lock) { held.countDown(); release.await() } }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        f.route = PersonalUpdateFetchRoute.Selected(PersonalUpdateFetchTicket(
            PersonalUpdateSocksEndpoint("127.0.0.1", 1080, null, null), { true }, { _, action ->
                queued.countDown(); synchronized(lock) { action(); true } }))
        val job = launch(Dispatchers.Default) { f.controller.manualCheck() }
        try { assertTrue(queued.await(10, TimeUnit.SECONDS)); job.cancel() }
        finally { release.countDown(); holder.join(5000) }
        job.join()
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
    }
    @Test fun cancellationQueuedAtInstallAuthorityCannotStartSystemInstaller() = runBlocking {
        val f = Fixture(); f.controller.manualCheck(); f.controller.download()
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(true)
        val held = CountDownLatch(1); val release = CountDownLatch(1); val queued = CountDownLatch(1); val lock = Any()
        val holder = thread { synchronized(lock) { held.countDown(); release.await() } }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        // The immutable candidate's ticket shares this fence, so queue the next action only.
        f.authority = { action -> queued.countDown(); synchronized(lock) { action(); true } }
        val job = launch(Dispatchers.Default) { f.controller.install(true) }
        try { assertTrue(queued.await(10, TimeUnit.SECONDS)); job.cancel() }
        finally { release.countDown(); holder.join(5000) }
        job.join(); assertEquals(0, f.handoffs)
    }
    @Test fun verifiedApkViewIsExactImmutableAndDoesNotMutateManifestTicket() {
        val f = Fixture(); val initial = (f.route as PersonalUpdateFetchRoute.Selected).ticket
        val success = f.verified(f.envelope)
        val view = (initial.withVerifiedApk(success) as PersonalUpdateFetchRoute.Selected).ticket
        val apk = "https://rga.apollot.ru/updates/personal/v1/3/apollo-rga-personal.apk"
        assertFalse(initial.allowsUrl(apk)); assertTrue(view.allowsUrl(apk)); assertTrue(view.allowsUrl(PERSONAL_UPDATE_MANIFEST_URL))
        for (url in listOf("$apk?x=1", "$apk#x", apk.replace("/3/", "/4/"), apk.replace("rga.apollot.ru", "other.invalid"),
            apk.replace("rga.apollot.ru", "rga.apollot.ru:443"), apk.replace("rga.apollot.ru", "u@rga.apollot.ru"))) assertFalse(view.allowsUrl(url))
        f.live = false; assertTrue(initial.withVerifiedApk(success) is PersonalUpdateFetchRoute.Blocked)
    }
    @Test fun metadataRetirementNeverCommitsReady() = runBlocking {
        val f = Fixture()
        val reader = object : PersonalApkMetadataReader {
            override fun installed(packageName: String) = f.artifact.reader.installed(packageName)
            override fun archive(file: File): PersonalApkMetadata? { f.live = false; return f.artifact.reader.archive(file) }
        }
        val gate = AndroidPersonalApkVerifier(f.artifact.dir, reader, 35, setOf("arm64-v8a")) { f.artifact.clock }
        val controller = f.controller(apkGate = gate)
        controller.manualCheck(); controller.download()
        assertEquals(PersonalUpdateState.SessionChanged, controller.state.value); assertEquals(0, f.handoffs)
    }
    @Test fun cancellationQueuedAtReadyAuthorityNeverPublishesReady() = runBlocking {
        val f = Fixture(); f.controller.manualCheck()
        val held = CountDownLatch(1); val release = CountDownLatch(1); val queued = CountDownLatch(1); val lock = Any()
        val holder = thread { synchronized(lock) { held.countDown(); release.await() } }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        f.authority = { action -> queued.countDown(); synchronized(lock) { action(); true } }
        val job = launch(Dispatchers.Default) { f.controller.download() }
        try { assertTrue(queued.await(10, TimeUnit.SECONDS)); job.cancel() }
        finally { release.countDown(); holder.join(5000) }
        job.join(); assertFalse(f.controller.state.value is PersonalUpdateState.Ready)
    }
    @Test fun positiveBodyReadRetirementRecordsReadBytesBeforePostReadGuard() = runBlocking {
        // Break caught: post-read retirement discards the actual returned count or reads a new guard reason.
        val f = Fixture(); var clock = 100L
        val owner = PersonalUpdateDiagnosticRecords.newOwner { clock }
        f.body = { object : InputStream() {
            override fun read(): Int = error("bulk read required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(1, offset, offset + 7); clock = 125; f.live = false; return 7
            }
        } }
        val controller = f.controller(diagnostics = owner)
        controller.manualCheck()
        assertEquals(PersonalUpdateState.SessionChanged, controller.state.value)
        val record = owner.terminal()
        assertNotNull("retired actual Controller body read has no terminal discriminant", record)
        assertEquals(7L, record!!.bodyBytesRead)
        assertEquals(125L, record.eventElapsedRealtimeMs)
        assertEquals(25L, record.elapsedMs)
        assertEquals(PersonalUpdateDiagnosticBranch.RETIRED, record.branch)
        assertTrue(record.retired)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
    }

    @Test fun genericBodyIoUsesOriginalFalseRouteEvaluationOnce() = assertDiagnosticBodyIo(false)
    @Test fun genericBodyIoOnCurrentRouteRemainsHttpRejection() = assertDiagnosticBodyIo(true)
    private fun assertDiagnosticBodyIo(liveAfterFailure: Boolean) = runBlocking {
        // Break caught: diagnostic reevaluation masks the original generic-catch route result.
        val f = Fixture(); var failed = false; var postFailureGuardCalls = 0; var clock = 10L
        f.route = PersonalUpdateFetchRoute.Selected(PersonalUpdateFetchTicket(
            PersonalUpdateSocksEndpoint("127.0.0.1", 1080, null, null),
            { if (failed) postFailureGuardCalls++; f.live }, { _, action -> action(); true }))
        f.body = { object : InputStream() {
            override fun read(): Int { failed = true; clock = 30; f.live = liveAfterFailure; throw java.io.IOException("never exported") }
        } }
        val owner = PersonalUpdateDiagnosticRecords.newOwner { clock }
        val controller = f.controller(diagnostics = owner)
        controller.manualCheck()
        assertEquals(if (liveAfterFailure) PersonalUpdateState.Rejected(PersonalUpdateControllerRefusal.HTTP)
            else PersonalUpdateState.SessionChanged, controller.state.value)
        assertEquals(1, postFailureGuardCalls)
        val record = owner.terminal()
        assertNotNull("actual generic IOException catch lacks terminal branch", record)
        assertEquals(PersonalUpdateDiagnosticBranch.HTTP_IO, record!!.branch)
        assertEquals(!liveAfterFailure, record.retired)
        assertEquals(0L, record.bodyBytesRead)
        assertEquals(30L, record.eventElapsedRealtimeMs)
        assertEquals(20L, record.elapsedMs)
    }

    @Test fun diagnosticClockFailureCannotChangeControllerRetirement() = runBlocking {
        val f = Fixture(); f.body = { bytes -> object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val result = super.read(buffer, offset, length); f.live = false; return result
            }
        } }
        val owner = PersonalUpdateDiagnosticRecords.newOwner { throw IllegalStateException("private clock") }
        val controller = f.controller(diagnostics = owner)
        controller.manualCheck()
        assertEquals(PersonalUpdateState.SessionChanged, controller.state.value)
        assertNull(owner.terminal())
    }

    @Test fun cancelledHttpOperationRecordsCancellationAndStillRethrows() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>()
        f.beforeResponse = { entered.complete(Unit); awaitCancellation() }
        val owner = PersonalUpdateDiagnosticRecords.newOwner { 100 }
        val controller = f.controller(diagnostics = owner)
        val job = launch { controller.manualCheck() }
        withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertEquals(PersonalUpdateState.Idle, controller.state.value)
        assertTrue(job.isCancelled)
        assertNotNull("actual cancelled operation lost terminal discriminant", owner.terminal())
        assertEquals(PersonalUpdateDiagnosticBranch.COROUTINE_CANCEL, owner.terminal()!!.branch)
        assertEquals(0L, owner.terminal()!!.bodyBytesRead)
    }

    private suspend fun assertNotNewerWithoutSideEffects(installedVersion: Long) {
        val f = Fixture(); f.artifact.installedMain = f.artifact.installedMain.copy(versionCode = installedVersion)
        f.controller.manualCheck()
        val state = f.controller.state.value
        assertTrue(state is PersonalUpdateState.Rejected)
        // Literal protocol expectation keeps this test compilable for root's pre-enum RED run.
        assertEquals("NOT_NEWER", (state as PersonalUpdateState.Rejected).reason.name)
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), f.store.read())
        f.controller.download()
        assertTrue(f.controller.install(true) is PersonalInstallResult.Refused)
        assertEquals(1, f.requests)
        assertEquals(0, f.handoffs)
    }

    private class Fixture {
        val artifact = AndroidPersonalApkVerifierTest.Fixture()
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val store = PersonalUpdateHighWaterStore(File(artifact.dir, "highwater"))
        var live = true; var requests = 0; var handoffs = 0; var code = 200
        var apk = artifact.download.readBytes()
        var beforeResponse: suspend () -> Unit = {}
        var authority: (action: () -> Unit) -> Boolean = { action -> action(); true }
        var body: (ByteArray) -> InputStream = { ByteArrayInputStream(it) }
        var route: PersonalUpdateFetchRoute = PersonalUpdateFetchRoute.Selected(PersonalUpdateFetchTicket(
            PersonalUpdateSocksEndpoint("127.0.0.1", 1080, null, null), { live }, { _, action -> if (!live) false else authority(action) }))
        val last = object : PersonalUpdateLastCheckStore { var value: Long? = null
            override fun read() = value
            override fun write(epochSeconds: Long): Boolean { value = epochSeconds; return true } }
        val installer = AndroidPersonalUpdateInstaller(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = AndroidPersonalApkVerifierTest.PACKAGE
            override fun startActivity(intent: android.content.Intent) { handoffs++ }
        }, artifact.gate) { Uri.parse("content://${AndroidPersonalApkVerifierTest.PACKAGE}.fileprovider/apk") }
        var envelope = signed()
        val controller = controller()
        fun controller(pin: ByteArray? = key.public.encoded, apkGate: AndroidPersonalApkVerifier = artifact.gate,
            lastStore: PersonalUpdateLastCheckStore = last, diagnostics: PersonalUpdateDiagnosticOwner? = null) = PersonalUpdateController(pin, apkGate::readBaseline,
            store, apkGate, installer, { route }, lastStore, { artifact.clock }, object : PersonalUpdateHttp {
                override suspend fun open(route: PersonalUpdateFetchRoute, exactUrl: String): PersonalUpdateHttpResponse {
                    requests++; beforeResponse()
                    return PersonalUpdateHttpResponse(code, body(if (exactUrl == PERSONAL_UPDATE_MANIFEST_URL) envelope else apk))
                }
            }, diagnostics)
        fun verified(bytes: ByteArray) = PersonalUpdateManifestVerifier.verify(bytes, key.public.encoded, artifact.baseline,
            PersonalUpdateHighWater(0, null), artifact.clock) as PersonalUpdateVerification.Success
        fun signed(sequence: Long = 1): ByteArray {
            val m = artifact.manifest
            val payload = ("APOLLO-RGA-PERSONAL-OTA/1\nsequence=$sequence\nissuedAt=${m.issuedAt}\nexpiresAt=${m.expiresAt}\n" +
                "package=${m.packageName}\nversionCode=${m.versionCode}\nversionName=${m.versionName}\nminSdk=${m.minSdk}\nabi=${m.abi}\n" +
                "apkPath=${m.apkPath}\napkBytes=${m.apkBytes}\napkSha256=${m.apkSha256}\napkCertSha256=${m.apkCertSha256}\n" +
                "companionPackage=${m.companionPackage}\ncompanionVersionCode=${m.companionVersionCode}\ncompanionSha256=${m.companionSha256}\ncompanionCertSha256=${m.companionCertSha256}\n").toByteArray()
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(payload) }.sign()
            val b64 = Base64.getUrlEncoder().withoutPadding()
            return "{\"payload\":\"${b64.encodeToString(payload)}\",\"signature\":\"${b64.encodeToString(signature)}\"}".toByteArray()
        }
    }
}
