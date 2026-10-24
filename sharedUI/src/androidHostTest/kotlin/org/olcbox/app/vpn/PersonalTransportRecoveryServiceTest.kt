package org.olcbox.app.vpn

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStoreSpi
import java.security.MessageDigest
import java.security.Provider
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.data.model.RoutingSettings
import org.olcbox.app.data.model.SubscriptionMetadata
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.net.AndroidKeystoreInstallationIdentity
import org.olcbox.app.net.AndroidPhysicalNetworkSnapshots
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.PersonalBootstrapV1
import org.olcbox.app.net.PersonalJoinProofFactory
import org.olcbox.app.net.PhysicalNetworkSnapshotLedger
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.net.TunnelExit
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.PersonalTunProof
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers

/** Production watchdog/guards; storage, Keystore, native admission, physical facts and TUN holder are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalTransportRecoveryServiceTest {
    @Test fun stoppedWorkingPersonalNativeAutomaticallyRequestsFreshAdmissionOnHeldTun() = withFixture { f ->
        // Break caught: personal watchdog settles the dead native transport but never schedules recovery.
        f.startWatchdogAndStopNative()
        f.scheduler.advanceTimeBy(45_000)
        f.scheduler.runCurrent()

        f.assertNoUnhandledFailure()
        assertTrue("watchdog never requested a new managed server admission", f.admissionTimes.isNotEmpty())
        assertTrue("one native-stop recovery cycle exceeded three admission attempts", f.admissionTimes.size <= 3)
        assertTrue("failed admission was retried in a tight loop",
            f.admissionTimes.zipWithNext().all { (before, after) -> after - before >= 1_000 })
        assertSame("personal recovery must retain the original fail-closed TUN", f.pfd, f.get("vpnInterface"))
        assertTrue(f.pfd.fileDescriptor.valid())
        assertEquals("personal recovery cannot turn into a new Connect generation", 7L, f.get("generation"))
        assertFalse("server refusal must not restore Connected", OlcboxVpnState.status.value is VpnStatus.Connected)
        assertFalse("old Working proof survived native loss", OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)

        val exhaustedAdmissions = f.admissionTimes.size
        f.scheduler.advanceTimeBy(180_000)
        f.scheduler.runCurrent()
        f.assertNoUnhandledFailure()
        assertEquals("exhausted recovery silently rearmed new room admissions", exhaustedAdmissions, f.admissionTimes.size)
        assertEquals("healthy Cellular recovery abandoned its admitted retry budget", 3, f.admissionTimes.size)
        assertEquals("exhaustion must remain honest Unavailable", PersonalTunnelPhase.Unavailable,
            OlcboxVpnState.personalTunnel.value.phase)
        assertFalse("exhausted recovery invented Connected", OlcboxVpnState.status.value is VpnStatus.Connected)
        assertSame(f.pfd, f.get("vpnInterface"))
        assertTrue(f.pfd.fileDescriptor.valid())
    }

    @Test
    @Config(shadows = [PersonalRecoveryTunnelVerifierShadow::class], instrumentedPackages = ["org.olcbox.app.net"])
    fun rejectedFinalPublicationWithCurrentOwnerRetriesRatherThanAbandoningHeldTun() =
        withFixture(boundary = RecoveryBoundary.FinalPublicationRace) { f ->
            // Break caught: a rejected final snapshot commit ends the cycle despite retained ownership/budget.
            f.startWatchdogAndStopNative()
            f.scheduler.advanceTimeBy(45_000)
            f.scheduler.runCurrent()
            f.assertNoUnhandledFailure()
            assertTrue("actual final publication did not suspend at its repository boundary", f.publicationPaused)
            assertEquals(1, f.admissionTimes.size)
            assertTrue("first native admission must have succeeded before publication", f.nativeIsRunning)

            f.releaseFinalPublicationWithNewCellularRevision()
            f.scheduler.runCurrent()
            f.assertNoUnhandledFailure()
            assertTrue("rejected publication did not safely settle the started transport", f.failureHoldCalls >= 2)
            assertFalse("publication rejection left an unmonitored native transport", f.nativeIsRunning)
            assertSame(f.pfd, f.get("vpnInterface"))
            assertTrue(f.pfd.fileDescriptor.valid())
            assertEquals(7L, f.get("generation"))
            assertFalse(OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)

            f.scheduler.advanceTimeBy(180_000)
            f.scheduler.runCurrent()
            f.assertNoUnhandledFailure()
            assertTrue("current-owner publication rejection abandoned remaining managed recovery attempts",
                f.admissionTimes.size >= 2)
            assertEquals("one publication rejection must not discard or exceed the bounded admission budget",
                3, f.admissionTimes.size)
            assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
            assertSame(f.pfd, f.get("vpnInterface"))
            assertTrue(f.pfd.fileDescriptor.valid())
        }

    @Test fun bridgeLauncherErrorAfterManagedAdmissionStillSettlesOriginatingTransport() =
        withFixture(boundary = RecoveryBoundary.BridgeLaunchFailure) { f ->
            // Break caught: Error is mistaken for replacement ownership and skips cleanup of a started room.
            f.startWatchdogAndStopNative()
            f.scheduler.advanceTimeBy(45_000)
            f.scheduler.runCurrent()
            f.assertNoUnhandledFailure()
            assertTrue("recovery did not reach managed native admission", f.admissionTimes.isNotEmpty())
            assertEquals("actual bridge start must reach the throwing launcher boundary", 1, f.bridgeLaunchCalls)
            assertTrue("bridge Error skipped settlement of the originating successful native admission",
                f.failureHoldCalls >= 2)
            assertFalse("failed bridge left admitted native transport running without a watchdog", f.nativeIsRunning)
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
            assertFalse(OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
            assertSame(f.pfd, f.get("vpnInterface"))
            assertTrue(f.pfd.fileDescriptor.valid())
        }

    @Test fun selectedCellularLossWithLivingNativeWaitsForCompleteRestoredFactsBeforeFreshAdmission() = withFixture { f ->
        // Break caught: selected loss/restore only updates DNS while living native bypasses transport recovery.
        assertTrue(f.nativeIsRunning)
        f.loseInitialSelectedNetwork()
        f.assertWorkingAuthorityRetired()
        f.advanceWithoutManagedAdmission(10_000, "NoNetwork cannot authorize a room join")
        assertNull("loss grace did not withdraw the old underlying network", f.get("currentNetwork"))

        val replacement = f.announceCellularReplacement()
        f.advanceWithoutManagedAdmission(10_000, "Available without capabilities cannot authorize a room join")
        f.deliverCellularCapabilities(replacement, captive = false)
        f.advanceWithoutManagedAdmission(10_000, "Cellular without link/DNS facts cannot authorize a room join")
        f.deliverCellularCapabilities(replacement, captive = true)
        f.deliverCellularLink(replacement)
        f.advanceWithoutManagedAdmission(30_000, "captive Cellular cannot authorize a room join")

        f.deliverCellularCapabilities(replacement, captive = false)
        f.assertBoundedLossRecovery(replacement,
            "complete restored Cellular never requested fresh admission for the living native episode")
    }

    @Test fun selectedCellularLossWithImmediateUsableFallbackStillRequestsFreshAdmission() = withFixture { f ->
        // Break caught: loss grace sees a complete fallback and returns through metadata-only personal handling.
        val replacement = f.announceCellularReplacement()
        f.deliverCellularCapabilities(replacement, captive = false)
        f.deliverCellularLink(replacement)
        f.scheduler.runCurrent()
        assertTrue("unselected fallback must not readmit an intact Working path", f.admissionTimes.isEmpty())
        assertEquals(PersonalTunnelPhase.Working, OlcboxVpnState.personalTunnel.value.phase)
        assertTrue(f.nativeIsRunning)

        f.loseInitialSelectedNetwork(keepFallback = true)
        f.assertWorkingAuthorityRetired()
        f.assertBoundedLossRecovery(replacement,
            "selected loss with immediate Cellular fallback never requested fresh admission")
    }

    @Test fun completeCellularReplacementDuringLossGraceCannotAbandonTheOriginatingRecovery() = withFixture { f ->
        // Break caught: callback metadata moves currentNetwork to B before the old A loss-grace job runs.
        assertTrue(f.nativeIsRunning)
        f.loseInitialSelectedNetwork()
        f.assertWorkingAuthorityRetired()
        val replacement = f.announceCellularReplacement()
        f.deliverCellularCapabilities(replacement, captive = false)
        f.deliverCellularLink(replacement)
        // All replacement callbacks run at the same virtual instant as loss, before its grace expires.
        assertEquals(replacement, f.get("currentNetwork"))
        f.assertBoundedLossRecovery(replacement,
            "complete Cellular replacement during loss grace abandoned originating fresh admission")
    }

    @Test fun queuedReplacementBeforeLostOuterTaskRetainsOriginatingRecoveryTrigger() = withFixture { f ->
        // Break caught: B's queued metadata callback wins the mutex before A's asynchronous loss capture.
        val replacement = f.announceCellularReplacement(flush = false)
        f.deliverCellularCapabilities(replacement, captive = false, flush = false)
        f.deliverCellularLink(replacement, flush = false)
        assertNotEquals(replacement, f.get("currentNetwork"))
        assertEquals(PersonalTunnelPhase.Working, OlcboxVpnState.personalTunnel.value.phase)

        // onLost removes A's snapshot synchronously, but its Service outer task is queued after B's tasks.
        f.loseInitialSelectedNetwork(keepFallback = true, flush = false)
        f.scheduler.runCurrent()
        f.assertNoUnhandledFailure()
        assertEquals("queued B callback did not win the metadata ordering", replacement, f.get("currentNetwork"))
        assertNotEquals("test must not invent an independently proven Working owner on B",
            replacement, f.get("personalWorkingNetwork"))
        // Exclude subsequent external helper liveness, never callback/owner/admission predicates.
        (f.get("personalProofJob") as? Job)?.cancel()
        f.scheduler.runCurrent()
        f.assertBoundedLossRecovery(replacement,
            "queued replacement before selected-loss outer capture discarded fresh admission")
    }

    @Test fun unacceptedProofAttemptBeforeLostOuterCannotMasqueradeAsNewWorkingReceipt() = withFixture { f ->
        // Break caught: an attempt epoch advances before external proof but is mistaken for accepted Working B.
        val replacement = f.announceCellularReplacement(flush = false)
        f.deliverCellularCapabilities(replacement, captive = false, flush = false)
        f.deliverCellularLink(replacement, flush = false)
        val capture = f.queueCurrentProofCaptureKeepingOriginalAcceptedReceipt(replacement)
        f.loseInitialSelectedNetwork(keepFallback = true, flush = false)
        f.scheduler.runCurrent()
        f.assertNoUnhandledFailure()
        assertTrue("actual pending-attempt capture did not run before the lost outer task", capture.isCompleted)
        assertNotNull("actual current B proof snapshot was not captured", capture.getCompleted())
        assertEquals(replacement, f.get("currentNetwork"))
        (f.get("personalProofJob") as? Job)?.cancel()
        f.scheduler.runCurrent()
        f.assertBoundedLossRecovery(replacement,
            "unaccepted proof attempt before selected-loss outer discarded fresh admission")
    }

    @Test fun selectedLossRecoveryAndExistingWatchdogShareOneBoundedAdmissionCycle() = withFixture { f ->
        // Break caught: callback holder stops native, then watchdog replaces/reset the same recovery budget.
        f.startWatchdogKeepingNative()
        f.loseInitialSelectedNetwork()
        f.keepExternalCompanionBoundaryBusy()
        f.advanceWithoutManagedAdmission(10_000, "watchdog cannot admit while selected physical path is absent")
        val replacement = f.announceCellularReplacement()
        f.deliverCellularCapabilities(replacement, captive = false)
        f.deliverCellularLink(replacement)
        f.assertBoundedLossRecovery(replacement,
            "selected loss with a living watchdog never requested fresh managed admission")
    }

    @Test fun manualStopDuringDeadNativeSettlementPreventsAnyLateManagedRestart() = withFixture(pausedHold = true) { f ->
        // Break caught: recovery survives the actual synchronous cleanup/Stop fence.
        f.enterDeadNativeSettlement()
        f.service.onStartCommand(Intent(f.service, OlcboxVpnService::class.java).apply {
            action = OlcboxVpnService.ACTION_STOP_VPN
        }, 0, 1)
        f.releaseHold.complete(Unit)
        f.advancePastRecoveryWindow()

        assertTrue("real Stop must advance the cleanup generation", (f.get("generation") as Long) > 7L)
        assertTrue("stale recovery joined a room after Stop", f.admissionTimes.isEmpty())
        assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        assertFalse(OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
        f.assertNoUnhandledFailure()
    }

    @Test fun replacementGenerationDuringDeadNativeSettlementNeverRevivesTheOldAttempt() = withFixture(pausedHold = true) { f ->
        // Break caught: a held recovery resumes using a newer generation instead of its captured owner.
        f.enterDeadNativeSettlement()
        f.set("generation", 8L)
        f.releaseHold.complete(Unit)
        f.advancePastRecoveryWindow()

        assertTrue("old recovery requested admission in the replacement generation", f.admissionTimes.isEmpty())
        assertEquals(8L, f.get("generation"))
        assertSame(f.pfd, f.get("vpnInterface"))
        assertTrue(f.pfd.fileDescriptor.valid())
        f.assertNoUnhandledFailure()
    }

    @Test fun subscriptionDenialArrivingDuringSettlementPreventsManagedReadmission() = withFixture(pausedHold = true) { f ->
        // Break caught: retry trusts the former Working admission instead of re-reading subscription denial.
        f.enterDeadNativeSettlement()
        f.bundle = f.bundle.copy(locations = listOf(f.bundle.locations.single().copy(
            metadata = LocationMetadata(subscription = SubscriptionMetadata(deviceDenied = true))
        )))
        f.releaseHold.complete(Unit)
        f.advancePastRecoveryWindow()

        assertTrue("denied subscription was sent for managed admission", f.admissionTimes.isEmpty())
        assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        assertFalse(OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
        f.assertNoUnhandledFailure()
    }

    @Test fun selectedSubscriptionChangeDuringSettlementCannotBorrowTheOldRoom() = withFixture(pausedHold = true) { f ->
        // Break caught: retry bypasses exact selected storage/link ownership.
        f.enterDeadNativeSettlement()
        f.bundle = f.bundle.copy(activeLocationId = "replacement")
        f.releaseHold.complete(Unit)
        f.advancePastRecoveryWindow()

        assertTrue("old room was readmitted after selected subscription changed", f.admissionTimes.isEmpty())
        assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        assertFalse(OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
        f.assertNoUnhandledFailure()
    }

    @Test fun sameSelectedStorageWithReplacedSubscriptionUrlCannotBorrowTheOldRoom() = withFixture(pausedHold = true) { f ->
        // Break caught: an unchanged selected ID conceals replacement of the admitted subscription link.
        f.enterDeadNativeSettlement()
        val selectedId = f.bundle.activeLocationId
        f.bundle = f.bundle.copy(locations = listOf(f.bundle.locations.single().copy(
            subscriptionUrl = "https://pan.gate-altas.tech/ReplacementBearer_02"
        )))
        assertEquals(selectedId, f.bundle.activeLocationId)
        assertEquals(selectedId, f.bundle.locations.single().storageId)
        f.releaseHold.complete(Unit)
        f.advancePastRecoveryWindow()

        assertTrue("old room was readmitted after same-storage subscription link replacement", f.admissionTimes.isEmpty())
        assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        assertFalse(OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
        f.assertNoUnhandledFailure()
    }

    private enum class RecoveryBoundary { DenyAdmission, FinalPublicationRace, BridgeLaunchFailure }

    private fun withFixture(
        pausedHold: Boolean = false,
        boundary: RecoveryBoundary = RecoveryBoundary.DenyAdmission,
        test: (Fixture) -> Unit
    ) {
        val fixture = Fixture(pausedHold, boundary)
        try { test(fixture) } finally { fixture.close() }
    }

    private class Fixture(pausedHold: Boolean, private val boundary: RecoveryBoundary) {
        val scheduler = TestCoroutineScheduler()
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        private val file = File.createTempFile("rga-native-recovery", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val releaseHold = CompletableDeferred<Unit>()
        private val holdEntered = CompletableDeferred<Unit>()
        private val bridgeRelease = CountDownLatch(1)
        private val bridge = thread(name = "rga-recovery-test-bridge", isDaemon = true) {
            try { bridgeRelease.await() } catch (_: InterruptedException) { /* native bridge stop */ }
        }
        private val failures = mutableListOf<String>()
        val admissionTimes = mutableListOf<Long>()
        private var nativeRunning = true
        val nativeIsRunning get() = nativeRunning
        var failureHoldCalls = 0
            private set
        var bridgeLaunchCalls = 0
            private set
        private var publicationContinuation: Continuation<LocationBundleV4>? = null
        private var publicationGateConsumed = false
        val publicationPaused get() = publicationContinuation != null
        private var recoveryBridge: Thread? = null
        private var recoveryBridgeRelease: CountDownLatch? = null
        private val bridgeDuplicates = mutableListOf<ParcelFileDescriptor>()
        private var previousNativeLibrariesLoaded: Boolean? = null
        private lateinit var initialNetwork: Network
        private lateinit var callback: ConnectivityManager.NetworkCallback
        private var pendingClaims: String? = null
        private val admittedNonces = mutableSetOf<String>()
        private val keyProvider = RecoveryKeyStoreProvider()
        private val bootstrap = PersonalBootstrapV1(1, "telemost", "vp8channel", ROOM, "NL",
            "a".repeat(64), System.currentTimeMillis() + 600_000L, 1)
        private lateinit var oldNonce: String
        var bundle = LocationBundleV4(activeLocationId = "selected", locations = listOf(
            LocationEntry(storageId = "selected", kind = LocationKind.Vless, subscriptionUrl = URL,
                rawLink = "vless://00000000-0000-0000-0000-000000000001@ordinary.invalid:443?security=tls")
        ), routing = RoutingSettings(mode = RoutingMode.Global), apolloVpnDisclosureAcceptedAt = 1L,
            apolloVpnDisclosureVersion = 1)

        init {
            try {
                RecoveryKeyStoreMaterial.pair = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()
                check(Security.insertProviderAt(keyProvider, 1) > 0)
                ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    override fun getPackageName() = "tech.gatealtas.rga.personal"
                })
                Shadows.shadowOf(service.packageManager).addResolveInfoForIntent(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(service.packageName),
                    ResolveInfo().apply { activityInfo = ActivityInfo().apply {
                        packageName = service.packageName; name = "org.olcbox.app.AppActivity"
                    } }
                )
                seedInstallationPreferences()
                val identity = AndroidKeystoreInstallationIdentity.forApp(service).loadOrCreate()
                oldNonce = JSONObject(PersonalJoinProofFactory(identity, nonceBytes = { it.fill(0x44) })
                    .create(ROOM, "NL").toJsonString()).getString("nonce")

                val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val shadow = Shadows.shadowOf(cm)
                shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                    ConnectivityManager.TYPE_MOBILE, 0, true, true))
                val network = cm.activeNetwork!!
                initialNetwork = network
                val caps = ShadowNetworkCapabilities.newInstance().also {
                    Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                val link = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName("192.0.2.1"))) }
                shadow.setNetworkCapabilities(network, caps)
                shadow.setLinkProperties(network, link)
                val snapshots = AndroidPhysicalNetworkSnapshots(7, PhysicalNetworkSnapshotLedger())
                snapshots.onAvailable(network)
                snapshots.onCapabilitiesChanged(network, caps)
                val physical = snapshots.onLinkPropertiesChanged(network, link)!!

                val runtime = Proxy.newProxyInstance(AndroidOlcRtcRuntime::class.java.classLoader,
                    arrayOf(AndroidOlcRtcRuntime::class.java)) { _, method, args ->
                    when (method.name) {
                        "isRunning" -> return@newProxyInstance nativeRunning
                        "stop" -> nativeRunning = false
                        "setClaimsJSON" -> pendingClaims = (args!![0] as String).takeIf { it.isNotEmpty() }
                        "start" -> {
                            checkFreshSignedAdmission(pendingClaims)
                            admissionTimes += scheduler.currentTime
                            // Fails a busy retry loop rather than letting the virtual scheduler hang.
                            if (admissionTimes.size > 8) throw AssertionError("unbounded managed admission burst")
                            if (boundary != RecoveryBoundary.DenyAdmission && admissionTimes.size == 1) {
                                nativeRunning = true
                                return@newProxyInstance null
                            }
                            throw IllegalStateException("fixture server denied fresh admission")
                        }
                    }
                    null
                } as AndroidOlcRtcRuntime
                val repository = Proxy.newProxyInstance(LocationsRepository::class.java.classLoader,
                    arrayOf(LocationsRepository::class.java)) { _, method, args ->
                    when (method.name) {
                        "getBundle", "getVisibleBundle" -> {
                            @Suppress("UNCHECKED_CAST")
                            val continuation = args?.lastOrNull() as? Continuation<LocationBundleV4>
                            // Gate only the real final publication, not earlier admission/current checks.
                            if (boundary == RecoveryBoundary.FinalPublicationRace && !publicationGateConsumed &&
                                continuation?.javaClass?.name?.contains("\$publishPersonalTransition\$") == true
                            ) {
                                publicationGateConsumed = true
                                publicationContinuation = continuation
                                COROUTINE_SUSPENDED
                            } else bundle
                        }
                        "getRoutingSettings" -> bundle.routing
                        "getChanges" -> MutableStateFlow(0L)
                        else -> throw AssertionError("unexpected repository boundary: ${method.name}")
                    }
                } as LocationsRepository
                val storage = Proxy.newProxyInstance(LocationsDataSource::class.java.classLoader,
                    arrayOf(LocationsDataSource::class.java)) { _, method, _ ->
                    when (method.name) {
                        "loadDeviceIdentity" -> "fixture-device-identity"
                        else -> throw AssertionError("unexpected device storage boundary: ${method.name}")
                    }
                } as LocationsDataSource
                set("scope", CoroutineScope(StandardTestDispatcher(scheduler) + (get("job") as Job) +
                    CoroutineExceptionHandler { _, error -> failures += error.javaClass.simpleName }))
                set("olcrtc\$delegate", lazy { runtime })
                set("repository\$delegate", lazy { repository })
                set("deviceIdentityProvider\$delegate", lazy { PersistentDeviceIdentityProvider(storage) })
                set("runtimePolicy\$delegate", lazy { RuntimeLocationPolicy.ExperimentalDebug })
                set("connectivityManager", cm)
                set("routingSettings", bundle.routing)
                set("generation", 7L)
                set("vpnInterface", pfd)
                set("physicalNetworkSnapshots", snapshots)
                callback = service.javaClass.getDeclaredMethod("createNetworkCallback",
                    Long::class.javaPrimitiveType, AndroidPhysicalNetworkSnapshots::class.java)
                    .apply { isAccessible = true }.invoke(service, 7L, snapshots) as ConnectivityManager.NetworkCallback
                set("currentNetwork", network)
                // A valid unused host port also satisfies the real bridge endpoint guard.
                val socksPort = ServerSocket(0).use { it.localPort }
                set("socksListenPort", socksPort)
                set("tun2socksBackendPort", socksPort)
                set("tun2socksStarted", true)
                set("tun2socksThread", bridge)
                set("personalOrdinaryLocation", bundle.locations.single().location)
                set("personalActiveBootstrap", bootstrap)
                set("connectedStorageId", "selected")
                set("connectedPersonalSubscriptionUrl", URL)
                if (boundary != RecoveryBoundary.DenyAdmission) installSyntheticBridgeBoundary()
                val holder: suspend (ParcelFileDescriptor) -> Boolean = { observed ->
                    assertSame(pfd, observed)
                    failureHoldCalls++
                    holdEntered.complete(Unit)
                    releaseHold.await()
                    stopFixtureBridge()
                    nativeRunning = false
                    set("tun2socksThread", null)
                    set("tun2socksStarted", false)
                    set("tun2socksBackendPort", null)
                    pfd.fileDescriptor.valid() && get("vpnInterface") === pfd
                }
                set("personalFailureTunHolder", holder)
                if (!pausedHold) releaseHold.complete(Unit)

                OlcboxVpnState.activeLocation = LocationConfig(id = ROOM, key = "a".repeat(64),
                    kind = LocationKind.Olcrtc, bypassProvider = "telemost", transport = "vp8channel")
                val stamp = PersonalTunProof.RuntimeStamp(7, physical.networkId, physical.revision, 11, 13, 17)
                (get("personalTransportEpoch") as AtomicLong).set(11)
                (get("personalBridgeEpoch") as AtomicLong).set(13)
                (get("personalProofEpoch") as AtomicLong).set(17)
                OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
                OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
                val evidence = PersonalTunProof.validateExactFlow(stamp, stamp, 99, "10.0.88.88", "10.0.88.88",
                    longArrayOf(99, 1, 1, 240, 360, 0x0A005858))!!
                check(OlcboxVpnState.publishPersonalWorking(OlcboxVpnState.personalPhaseEpoch(), evidence))
                // The accepted proof publishes these Service facts as well as the UI Working receipt.
                set("personalWorkingNetwork", network)
                set("personalWorkingSnapshot", physical)
                OlcboxVpnState.setStatus(VpnStatus.Connected)
                check(service.javaClass.getDeclaredMethod("isTunBridgeReadyForActiveTransport")
                    .apply { isAccessible = true }.invoke(service) == true)
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        fun startWatchdogKeepingNative() {
            assertEquals(PersonalTunnelPhase.Working, OlcboxVpnState.personalTunnel.value.phase)
            assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
            service.javaClass.getDeclaredMethod("startWatchdog").apply { isAccessible = true }.invoke(service)
            scheduler.runCurrent()
        }

        fun startWatchdogAndStopNative() {
            startWatchdogKeepingNative()
            nativeRunning = false
        }

        fun enterDeadNativeSettlement() {
            startWatchdogAndStopNative()
            scheduler.advanceTimeBy(15_000)
            scheduler.runCurrent()
            assertTrue("watchdog did not reach the real dead-native settlement branch", holdEntered.isCompleted)
            assertEquals(VpnStatus.Reconnecting, OlcboxVpnState.status.value)
            assertEquals(PersonalTunnelPhase.Checking, OlcboxVpnState.personalTunnel.value.phase)
            assertTrue(admissionTimes.isEmpty())
        }

        fun advancePastRecoveryWindow() {
            scheduler.runCurrent()
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
        }

        fun assertNoUnhandledFailure() {
            assertTrue("fixture failed before the admission oracle: $failures", failures.isEmpty())
        }

        fun loseInitialSelectedNetwork(keepFallback: Boolean = false, flush: Boolean = true) {
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val shadow = Shadows.shadowOf(cm)
            if (keepFallback) shadow.removeNetwork(initialNetwork) else shadow.setActiveNetworkInfo(null)
            callback.onLost(initialNetwork)
            if (!flush) return
            scheduler.runCurrent()
            // Actual onLost has retired Working and the first attempt has no current snapshot.
            // Exclude external companion/DNS/JNI traffic without replacing callback/recovery predicates.
            (get("personalProofJob") as? Job)?.cancel()
            scheduler.runCurrent()
        }

        fun assertWorkingAuthorityRetired() {
            assertFalse("selected loss left old Working authority visible",
                OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
            assertNull(get("personalWorkingSnapshot"))
        }

        fun keepExternalCompanionBoundaryBusy() {
            // Model only an outstanding external helper operation, never a validated receipt.
            // Prevent baseline watchdog from launching real DNS/IPC; the production holder owns cancellation.
            set("personalProofJob", Job(get("job") as Job))
        }

        fun queueCurrentProofCaptureKeepingOriginalAcceptedReceipt(replacement: Network): CompletableDeferred<Any?> {
            val completed = CompletableDeferred<Any?>()
            val originalWorkingSnapshot = get("personalWorkingSnapshot")
            val originalAcceptedSnapshot = get("personalRefreshSnapshot")
            val beforeAttempt = (get("personalProofEpoch") as AtomicLong).get()
            (get("scope") as CoroutineScope).launch {
                (get("tunnelMutex") as Mutex).withLock {
                    assertEquals(replacement, get("currentNetwork"))
                    val captured = suspendCoroutine<Any?> { continuation ->
                        val returned = service.javaClass.getDeclaredMethod("capturePersonalProofSnapshot",
                            Continuation::class.java).apply { isAccessible = true }.invoke(service, continuation)
                        if (returned !== COROUTINE_SUSPENDED) continuation.resume(returned)
                    }
                    // Real capture is the boundary before DNS/IPC, not a completed full-TUN receipt.
                    assertTrue((get("personalProofEpoch") as AtomicLong).get() > beforeAttempt)
                    assertEquals(PersonalTunnelPhase.Working, OlcboxVpnState.personalTunnel.value.phase)
                    assertSame(initialNetwork, get("personalWorkingNetwork"))
                    assertSame(originalWorkingSnapshot, get("personalWorkingSnapshot"))
                    assertSame(originalAcceptedSnapshot, get("personalRefreshSnapshot"))
                    completed.complete(captured)
                }
            }
            return completed
        }

        fun announceCellularReplacement(flush: Boolean = true): Network {
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val replacement = ShadowNetwork.newInstance(201)
            assertNotEquals(initialNetwork, replacement)
            Shadows.shadowOf(cm).addNetwork(replacement, ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 0, true, true))
            Shadows.shadowOf(cm).setNetworkCapabilities(replacement, ShadowNetworkCapabilities.newInstance())
            callback.onAvailable(replacement)
            if (flush) scheduler.runCurrent()
            return replacement
        }

        fun deliverCellularCapabilities(network: Network, captive: Boolean, flush: Boolean = true) {
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = ShadowNetworkCapabilities.newInstance().also {
                Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (captive) Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                else Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            Shadows.shadowOf(cm).setNetworkCapabilities(network, caps)
            callback.onCapabilitiesChanged(network, caps)
            if (flush) scheduler.runCurrent()
        }

        fun deliverCellularLink(network: Network, flush: Boolean = true) {
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val link = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName("192.0.2.2"))) }
            Shadows.shadowOf(cm).setLinkProperties(network, link)
            callback.onLinkPropertiesChanged(network, link)
            if (flush) scheduler.runCurrent()
        }

        fun advanceWithoutManagedAdmission(windowMs: Long, reason: String) {
            scheduler.advanceTimeBy(windowMs)
            scheduler.runCurrent()
            assertNoUnhandledFailure()
            assertTrue(reason, admissionTimes.isEmpty())
        }

        fun assertBoundedLossRecovery(replacement: Network, missingAdmission: String) {
            val selected = service.javaClass.getDeclaredMethod("findActiveUpstreamNetwork")
                .apply { isAccessible = true }.invoke(service)
            assertEquals("complete replacement is not the selected physical network", replacement, selected)
            scheduler.advanceTimeBy(180_000)
            scheduler.runCurrent()
            assertNoUnhandledFailure()
            assertTrue(missingAdmission, admissionTimes.isNotEmpty())
            assertEquals("selected-loss recovery must consume exactly its three refused admission attempts",
                3, admissionTimes.size)
            assertTrue("selected-loss admissions were retried in a tight loop",
                admissionTimes.zipWithNext().all { (before, after) -> after - before >= 1_000 })
            assertEquals(7L, get("generation"))
            assertSame(pfd, get("vpnInterface"))
            assertTrue(pfd.fileDescriptor.valid())
            assertEquals(PersonalTunnelPhase.Unavailable, OlcboxVpnState.personalTunnel.value.phase)
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
            assertFalse("failed admission invented a new Working proof",
                OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
        }

        fun releaseFinalPublicationWithNewCellularRevision() {
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork!!
            val snapshots = get("physicalNetworkSnapshots") as AndroidPhysicalNetworkSnapshots
            val before = snapshots.current(network)!!
            val link = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName("192.0.2.2"))) }
            Shadows.shadowOf(cm).setLinkProperties(network, link)
            val after = snapshots.onLinkPropertiesChanged(network, link)!!
            assertEquals(before.generation, after.generation)
            assertEquals(before.networkId, after.networkId)
            assertTrue("race must change the current same-network snapshot revision", after.revision > before.revision)
            val continuation = checkNotNull(publicationContinuation)
            publicationContinuation = null
            continuation.resume(bundle)
        }

        private fun installSyntheticBridgeBoundary() {
            val loaded = service.javaClass.getDeclaredField("nativeLibrariesLoaded").apply { isAccessible = true }
            previousNativeLibrariesLoaded = loaded.getBoolean(null)
            loaded.setBoolean(null, true)
            val duplicate: (ParcelFileDescriptor) -> ParcelFileDescriptor = {
                assertSame(pfd, it)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).also(bridgeDuplicates::add)
            }
            set("tun2socksFdDuplicator", duplicate)
            set("tun2socksNativePreparer", { 72L })
            set("tun2socksNativeAbandoner", { _: Long -> true })
            set("tun2socksNativeStopper", { _: Long -> true })
            val launcher: (() -> Unit) -> Thread = {
                bridgeLaunchCalls++
                if (boundary == RecoveryBoundary.BridgeLaunchFailure) {
                    throw IllegalStateException("fixture bridge launcher failed")
                }
                // JNI work is not run; the real Service bridge setup/port/readiness guards remain active.
                val release = CountDownLatch(1)
                recoveryBridgeRelease = release
                thread(name = "rga-recovery-test-started-bridge", isDaemon = true) {
                    try { release.await() } catch (_: InterruptedException) { /* synthetic JNI stop */ }
                    finally { set("tun2socksStarted", false); set("tun2socksNativeEpoch", 0L) }
                }.also { recoveryBridge = it }
            }
            set("tun2socksThreadLauncher", launcher)
        }

        private fun checkFreshSignedAdmission(raw: String?) {
            if (raw == null) throw AssertionError("managed restart omitted fresh claims")
            val claims = JSONObject(raw)
            val nonce = claims.getString("nonce")
            assertNotEquals("managed restart reused the previous Working PoP", oldNonce, nonce)
            assertTrue("managed retry reused a consumed nonce", admittedNonces.add(nonce))
            assertEquals(INSTALLATION_ID, claims.getString("installationId"))
            val pair = RecoveryKeyStoreMaterial.pair!!
            assertArrayEquals(pair.public.encoded, decode(claims.getString("publicKey")))
            // Independently frame the Bridge's wire transcript, not the production builder.
            val transcript = ByteArrayOutputStream().also { bytes ->
                bytes.write("Apollo.RGA/olcrtc-test-join/v1".toByteArray(Charsets.US_ASCII))
                bytes.write(0)
                val framed = DataOutputStream(bytes)
                for (part in listOf(INSTALLATION_ID.toByteArray(),
                    MessageDigest.getInstance("SHA-256").digest(pair.public.encoded),
                    ROOM.toByteArray(), "NL".toByteArray(), claims.getString("issuedAt").toByteArray(), decode(nonce))) {
                    framed.writeShort(part.size)
                    framed.write(part)
                }
            }.toByteArray()
            assertTrue("managed restart supplied a proof that server admission cannot verify",
                Signature.getInstance("SHA256withECDSA").apply {
                    initVerify(pair.public); update(transcript)
                }.verify(decode(claims.getString("signature"))))
        }

        private fun seedInstallationPreferences() {
            val publicDer = RecoveryKeyStoreMaterial.pair!!.public.encoded
            service.applicationContext.getSharedPreferences("olcrtc_personal_join_installation_v1", Context.MODE_PRIVATE)
                .edit().putString("installation_id", INSTALLATION_ID)
                .putString("public_key_sha256", Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicDer),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)).commit()
        }

        private fun stopFixtureBridge() {
            bridgeRelease.countDown()
            bridge.interrupt()
            bridge.join(1_000)
            recoveryBridgeRelease?.countDown()
            recoveryBridge?.interrupt()
            recoveryBridge?.join(1_000)
        }

        fun get(name: String): Any? = service.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(service)
        fun set(name: String, value: Any?) { service.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value) }
        fun close() {
            (get("job") as Job).cancel()
            scheduler.runCurrent()
            stopFixtureBridge()
            bridgeDuplicates.forEach { runCatching { it.close() } }
            previousNativeLibrariesLoaded?.let { previous ->
                service.javaClass.getDeclaredField("nativeLibrariesLoaded").apply { isAccessible = true }
                    .setBoolean(null, previous)
            }
            runCatching { pfd.close() }
            file.delete()
            Security.removeProvider(keyProvider.name)
            RecoveryKeyStoreMaterial.pair = null
            OlcboxVpnState.channelProxy = null
            OlcboxVpnState.personalUpdateTicket = null
            OlcboxVpnState.activeLocation = null
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    private companion object {
        const val URL = "https://pan.gate-altas.tech/SelectedBearer_01"
        const val ROOM = "room-recovery-01"
        const val INSTALLATION_ID = "fixture_installation_0123456789"
        fun decode(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}

/** Test-process-only JCA adapter: real ECDSA signing, no Android hardware or persisted key. */
private class RecoveryKeyStoreProvider : Provider("RgaRecoveryFixtureKeys", 1.0, "Host-test installation key") {
    init { put("KeyStore.AndroidKeyStore", RecoveryFixtureKeyStore::class.java.name) }
}

private object RecoveryKeyStoreMaterial { var pair: KeyPair? = null }

/** External HTTPS verification only: no request, Android TUN or independent-UID proof is simulated as real. */
@Implements(value = TunnelVerifier::class, isInAndroidSdk = false)
class PersonalRecoveryTunnelVerifierShadow {
    @Implementation
    @Suppress("UNUSED_PARAMETER")
    fun verify(
        socksHost: String,
        socksPort: Int,
        username: String,
        password: String,
        timeoutMs: Long,
        probeUrls: List<String>,
        continuation: Continuation<TunnelExit?>
    ): Any? = TunnelExit("203.0.113.9", "NL")
}

class RecoveryFixtureKeyStore : KeyStoreSpi() {
    private val alias = "org.olcbox.app.olcrtc_personal_join_p256_v1"
    private fun certificate(): Certificate? = RecoveryKeyStoreMaterial.pair?.let { pair ->
        object : Certificate("X.509") {
            override fun getEncoded(): ByteArray = pair.public.encoded
            override fun verify(key: PublicKey) = Unit
            override fun verify(key: PublicKey, sigProvider: String) = Unit
            override fun toString(): String = "Host-test certificate"
            override fun getPublicKey(): PublicKey = pair.public
        }
    }
    override fun engineGetKey(alias: String?, password: CharArray?): Key? =
        if (alias == this.alias) RecoveryKeyStoreMaterial.pair?.private else null
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? =
        engineGetCertificate(alias)?.let { arrayOf(it) }
    override fun engineGetCertificate(alias: String?): Certificate? = if (alias == this.alias) certificate() else null
    override fun engineGetCreationDate(alias: String?): Date? = if (engineContainsAlias(alias)) Date(1) else null
    override fun engineContainsAlias(alias: String?): Boolean = alias == this.alias && RecoveryKeyStoreMaterial.pair != null
    override fun engineIsKeyEntry(alias: String?): Boolean = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String?): Boolean = false
    override fun engineSize(): Int = if (RecoveryKeyStoreMaterial.pair != null) 1 else 0
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(
        if (engineSize() == 1) listOf(alias) else emptyList())
    override fun engineGetCertificateAlias(cert: Certificate?): String? =
        if (cert != null && cert.publicKey == RecoveryKeyStoreMaterial.pair?.public) alias else null
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException("test-only key store")
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException("test-only key store")
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException("test-only key store")
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException("test-only key store")
    override fun engineDeleteEntry(alias: String?) = throw AssertionError("fixture identity unexpectedly rotated")
}
