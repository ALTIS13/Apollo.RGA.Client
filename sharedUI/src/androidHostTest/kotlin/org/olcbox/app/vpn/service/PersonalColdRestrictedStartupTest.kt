package org.olcbox.app.vpn.service

import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.net.DirectProbeRound
import org.olcbox.app.net.ProbeOutcome
import org.olcbox.app.net.SelectedPathEvidence
import org.olcbox.app.net.RestrictedNetworkCandidate
import org.olcbox.app.net.PersonalBootstrapV1
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PersonalColdRestrictedStartupTest {
    @Test fun missingNetworkEarlyReturnIsNotAHandledColdHandoff() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        setField(service, "connectivityManager", service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE))
        val file = File.createTempFile("rga-cold-early", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        try {
            setField(service, "vpnInterface", pfd)
            setField(service, "connectedStorageId", "ordinary")
            OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.setStatus(VpnStatus.Connecting)
            val result = invokeEarlyHandoff(service)
            assertEquals("an early return must not be inferred from live PFD/status", false, result)
            val assessment: suspend (Long) -> Boolean = { invokeEarlyHandoff(service) == true }
            setField(service, "coldStartupRestrictionAssessment", assessment)
            (field(service, "tunnelMutex") as Mutex).withLock { failOrdinaryVerification(service) }
            assertFalse(pfd.fileDescriptor.valid())
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
        } finally {
            runCatching { pfd.close() }; file.delete()
            OlcboxVpnState.activeLocation = null
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    private suspend fun invokeEarlyHandoff(service: OlcboxVpnService): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = service.javaClass.declaredMethods.single { it.name == "handoffToPersonal" }
                .apply { isAccessible = true }
            val args = mutableListOf<Any?>(RestrictedNetworkCandidate(0, 1, 1),
                PersonalBootstrapV1(1, "telemost", "vp8channel", "room-12345", "NL", "a".repeat(64),
                    System.currentTimeMillis() + 60_000, 1), "https://pan.gate-altas.tech/SelectedBearer_01")
            if (method.parameterCount > 4) args += null // optional cold transition guard
            args += continuation
            method.invoke(service, *args.toTypedArray())
        }

    @Test fun sameNetworkRevisionDriftAfterSourceStopNeverStartsNativeAdmission() = runBlocking {
        val steps = mutableListOf<String>()
        var snapshot = AssessmentFixture().snapshot
        val expected = snapshot
        val warmIdentity: suspend () -> Boolean = { true } // Network/storage identity stayed unchanged.
        val coldValidity: suspend () -> Boolean = { snapshot == expected && !snapshot.captive }
        val callbacks = listOf<suspend () -> Boolean>(
            warmIdentity,
            { steps += "stop-bridge"; true },
            { steps += "stop-source"; snapshot = snapshot.copy(revision = 2, captive = true); true },
            { steps += "native-admission"; true },
            { steps += "start-bridge"; true },
            { steps += "verify"; true },
            { steps += "restore"; true }
        )
        val constructors = PersonalBridgeHandoff::class.java.declaredConstructors
        val constructor = constructors.single { it.parameterCount == 8 }
        val args = callbacks.toMutableList()
        args += coldValidity
        val handoff = constructor.newInstance(*args.toTypedArray()) as PersonalBridgeHandoff
        assertEquals(PersonalBridgeOutcome.Superseded, handoff.switch())
        assertEquals(listOf("stop-bridge", "stop-source"), steps)
    }

    @Test fun coldSubscriptionDenialDuringTargetStartCannotPublishTargetReadyOrRestoreSource() = runBlocking {
        val steps = mutableListOf<String>()
        var allowed = true
        val outcome = PersonalBridgeHandoff(
            isCurrent = { true },
            stopCurrentBridge = { steps += "stop-bridge"; true },
            stopCurrentTransport = { steps += "stop-source"; true },
            startTargetTransport = { steps += "admission-attempt"; allowed = false; true },
            startTargetBridge = { steps += "start-target-bridge"; true },
            verifyTarget = { steps += "verify-target"; true },
            restoreSource = { steps += "restore-source"; true },
            coldTransitionCurrent = { allowed }
        ).switch()
        assertEquals(PersonalBridgeOutcome.Superseded, outcome)
        assertEquals(listOf("stop-bridge", "stop-source", "admission-attempt"), steps)
    }

    @Test fun coldValidityDoesNotRequireTheAlreadyStoppedOrdinaryTransportToBeAlive() = runBlocking {
        var ordinaryAlive = true
        val outcome = PersonalBridgeHandoff(
            isCurrent = { true },
            stopCurrentBridge = { true },
            stopCurrentTransport = { ordinaryAlive = false; true },
            startTargetTransport = { assertFalse(ordinaryAlive); true },
            startTargetBridge = { true }, verifyTarget = { true }, restoreSource = { false },
            coldTransitionCurrent = { true }
        ).switch()
        assertEquals(PersonalBridgeOutcome.TargetReady, outcome)
    }
    // Regression: failure cleanup used to close the cold Connecting TUN while
    // callback assessment was blocked on this exact startup mutex.
    @Test fun coldFailureAwaitsAssessmentWithoutReenteringMutexOrClosingTun() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-cold-tun", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val mutex = field(service, "tunnelMutex") as Mutex
        var assessed = false
        try {
            setField(service, "vpnInterface", pfd)
            OlcboxVpnState.setStatus(VpnStatus.Connecting)
            val assessment: suspend (Long) -> Boolean = {
                assertTrue(mutex.isLocked)
                assertTrue(pfd.fileDescriptor.valid())
                assertTrue(OlcboxVpnState.status.value is VpnStatus.Connecting)
                delay(1)
                assessed = true
                true
            }
            setField(service, "coldStartupRestrictionAssessment", assessment)
            mutex.withLock { failOrdinaryVerification(service) }
            assertTrue("cold startup assessment was bypassed", assessed)
            assertTrue("failure cleanup closed TUN before alternate assessment", pfd.fileDescriptor.valid())
            assertFalse(OlcboxVpnState.status.value is VpnStatus.Connected)
        } finally {
            runCatching { pfd.close() }
            file.delete()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    @Test fun inconclusiveColdAttemptCleansUpAndASecondConnectIsNotARestart() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-cold-fail", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        try {
            setField(service, "vpnInterface", pfd)
            OlcboxVpnState.setStatus(VpnStatus.Connecting)
            val assessment: suspend (Long) -> Boolean = { false }
            setField(service, "coldStartupRestrictionAssessment", assessment)
            (field(service, "tunnelMutex") as Mutex).withLock { failOrdinaryVerification(service) }
            assertFalse(pfd.fileDescriptor.valid())
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
            assertFalse(service.javaClass.getDeclaredMethod("shouldRestartForStartCommand")
                .apply { isAccessible = true }.invoke(service) as Boolean)
        } finally {
            runCatching { pfd.close() }; file.delete()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    @Test fun assessmentExceptionCannotLeaveColdConnectingResourcesAlive() = runBlocking {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val file = File.createTempFile("rga-cold-exception", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        try {
            setField(service, "vpnInterface", pfd)
            OlcboxVpnState.setStatus(VpnStatus.Connecting)
            val assessment: suspend (Long) -> Boolean = { throw IllegalStateException("unknown probe") }
            setField(service, "coldStartupRestrictionAssessment", assessment)
            (field(service, "tunnelMutex") as Mutex).withLock { failOrdinaryVerification(service) }
            assertFalse(pfd.fileDescriptor.valid())
            assertTrue(OlcboxVpnState.status.value is VpnStatus.Error)
        } finally {
            runCatching { pfd.close() }; file.delete()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    @Test fun twoRealProbeRoundsAndFailedSelectedPathAreRequired() = runBlocking {
        val fixture = AssessmentFixture()
        val result = fixture.assessment().assess(fixture.snapshot)
        assertNotNull(result)
        assertEquals(2, fixture.rounds)
        assertEquals(1, fixture.selectedCalls)
    }

    @Test fun unknownOrAnsweredSelectedPathNeverBecomesRestrictionEvidence() = runBlocking {
        for (outcome in listOf(ProbeOutcome.Unknown, ProbeOutcome.Answered)) {
            val fixture = AssessmentFixture()
            fixture.selected = SelectedPathEvidence(outcome, ProbeOutcome.Unknown)
            assertNull(fixture.assessment().assess(fixture.snapshot))
        }
    }

    @Test fun domesticFailureOrOneForeignAnswerNeverAdmits() = runBlocking {
        for (evidence in listOf(
            listOf(ProbeOutcome.Failed, ProbeOutcome.Failed, ProbeOutcome.Failed),
            listOf(ProbeOutcome.Answered, ProbeOutcome.Failed, ProbeOutcome.Answered),
            listOf(ProbeOutcome.Failed, ProbeOutcome.Unknown, ProbeOutcome.Answered)
        )) {
            val fixture = AssessmentFixture()
            fixture.direct = evidence
            assertNull(fixture.assessment().assess(fixture.snapshot))
        }
    }

    @Test fun noLiveCoreOrBridgeStopsBeforeAnyProbe() = runBlocking {
        val fixture = AssessmentFixture()
        fixture.current = false
        assertNull(fixture.assessment().assess(fixture.snapshot))
        assertEquals(0, fixture.rounds)
    }

    @Test fun stopOrSnapshotChangeDuringFirstRoundPreventsSecondRoundAndSelectedProbe() = runBlocking {
        val fixture = AssessmentFixture()
        fixture.afterRound = { fixture.current = false }
        assertNull(fixture.assessment().assess(fixture.snapshot))
        assertEquals(1, fixture.rounds)
        assertEquals(0, fixture.selectedCalls)
    }

    @Test fun staleRoundIdentityCannotBeUsedForAdmission() = runBlocking {
        val fixture = AssessmentFixture()
        fixture.roundRevision = 2
        assertNull(fixture.assessment().assess(fixture.snapshot))
        assertEquals(1, fixture.rounds)
    }

    @Test fun stopDuringSelectedProbeRejectsOtherwiseRestrictedEvidence() = runBlocking {
        val fixture = AssessmentFixture()
        val assessment = PersonalColdRestrictedStartup(
            isCurrent = { fixture.current },
            physicalRound = {
                DirectProbeRound(1, 1, 1, fixture.now,
                    ProbeOutcome.Failed, ProbeOutcome.Failed, ProbeOutcome.Answered)
            },
            selectedPath = {
                fixture.current = false
                SelectedPathEvidence(ProbeOutcome.Failed, ProbeOutcome.Unknown)
            },
            clockMs = { fixture.now }, waitMs = { fixture.now += it }
        )
        assertNull(assessment.assess(fixture.snapshot))
    }

    @Test fun assessmentHasOneOverallThirtySecondDeadline() = runBlocking {
        val snapshot = AssessmentFixture().snapshot
        val completed = withTimeoutOrNull(32_000L) {
            val result = PersonalColdRestrictedStartup(
                isCurrent = { true },
                physicalRound = { error("settle has not completed") },
                selectedPath = { error("no path probe before settle") },
                waitMs = { delay(60_000L) }
            ).assess(snapshot)
            assertNull(result)
            true
        }
        assertEquals("assessment exceeded its shared deadline", true, completed)
    }

    @Test fun wifiCaptiveAndIncompleteSnapshotsNeverProbe() = runBlocking {
        for (snapshot in listOf(
            AssessmentFixture().snapshot.copy(transport = UpstreamTransport.Wifi),
            AssessmentFixture().snapshot.copy(captive = true),
            AssessmentFixture().snapshot.copy(dnsServers = null)
        )) {
            val fixture = AssessmentFixture()
            assertNull(fixture.assessment().assess(snapshot))
            assertEquals(0, fixture.rounds)
        }
    }

    private class AssessmentFixture {
        val snapshot = PhysicalNetworkSnapshot(1, 1, 1, UpstreamTransport.Cellular,
            true, false, false, listOf("192.0.2.1"))
        var now = 0L
        var current = true
        var rounds = 0
        var selectedCalls = 0
        var roundRevision = 1L
        var selected = SelectedPathEvidence(ProbeOutcome.Failed, ProbeOutcome.Unknown)
        var direct = listOf(ProbeOutcome.Failed, ProbeOutcome.Failed, ProbeOutcome.Answered)
        var afterRound: () -> Unit = {}
        fun assessment() = PersonalColdRestrictedStartup(
            isCurrent = { current },
            physicalRound = {
                rounds++
                afterRound()
                DirectProbeRound(1, 1, roundRevision, now, direct[0], direct[1], direct[2])
            },
            selectedPath = { selectedCalls++; selected },
            clockMs = { now }, waitMs = { now += it }
        )
    }

    private fun field(service: OlcboxVpnService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun setField(service: OlcboxVpnService, name: String, value: Any?) =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    private suspend fun failOrdinaryVerification(service: OlcboxVpnService): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            service.javaClass.declaredMethods.single { it.name == "failUnverifiedTunnel" }
                .apply { isAccessible = true }
                .invoke(service, "VPN tunnel", false, 0L, continuation)
        }
}
