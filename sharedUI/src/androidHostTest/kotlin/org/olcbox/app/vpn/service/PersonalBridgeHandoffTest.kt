package org.olcbox.app.vpn.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalBridgeHandoffTest {
    @Test fun switchingStopsOldBridgeBeforeTransportAndOnlyReportsNewPathAfterVerification() = runBlocking {
        val steps = mutableListOf<String>()
        val outcome = handoff(steps).switch()

        assertEquals(PersonalBridgeOutcome.TargetReady, outcome)
        assertEquals(listOf("stop-bridge", "stop-source", "start-target", "start-bridge", "verify-target"), steps)
    }

    @Test fun failedManagedAdmissionRestoresTheOrdinaryPathWithoutStartingItsBridgeTwice() = runBlocking {
        val steps = mutableListOf<String>()
        val outcome = handoff(steps, startTarget = false).switch()

        assertEquals(PersonalBridgeOutcome.SourceRestored, outcome)
        assertEquals(listOf("stop-bridge", "stop-source", "start-target", "restore-source"), steps)
    }

    @Test fun bridgeStopTimeoutLeavesTunnelHeldAndNeverStartsEitherTransport() = runBlocking {
        val steps = mutableListOf<String>()
        val outcome = handoff(steps, stopBridge = false).switch()

        assertEquals(PersonalBridgeOutcome.TunHeld, outcome)
        assertEquals(listOf("stop-bridge"), steps)
    }

    @Test fun targetBridgeVerificationFailureRollsBackInsteadOfReportingConnected() = runBlocking {
        val steps = mutableListOf<String>()
        val outcome = handoff(steps, verifyTarget = false).switch()

        assertEquals(PersonalBridgeOutcome.SourceRestored, outcome)
        assertEquals(listOf(
            "stop-bridge", "stop-source", "start-target", "start-bridge", "verify-target", "restore-source"
        ), steps)
    }

    @Test fun supersededTransitionNeverStartsAStaleTargetOrRollsBackAcrossManualStop() = runBlocking {
        val steps = mutableListOf<String>()
        var current = true
        val outcome = PersonalBridgeHandoff(
            isCurrent = { current },
            stopCurrentBridge = { steps += "stop-bridge"; current = false; true },
            stopCurrentTransport = { steps += "stop-source"; true },
            startTargetTransport = { steps += "start-target"; true },
            startTargetBridge = { steps += "start-bridge"; true },
            verifyTarget = { steps += "verify-target"; true },
            restoreSource = { steps += "restore-source"; true }
        ).switch()

        assertEquals(PersonalBridgeOutcome.Superseded, outcome)
        assertEquals(listOf("stop-bridge"), steps)
    }

    private fun handoff(
        steps: MutableList<String>,
        stopBridge: Boolean = true,
        startTarget: Boolean = true,
        verifyTarget: Boolean = true
    ) = PersonalBridgeHandoff(
        isCurrent = { true },
        stopCurrentBridge = { steps += "stop-bridge"; stopBridge },
        stopCurrentTransport = { steps += "stop-source"; true },
        startTargetTransport = { steps += "start-target"; startTarget },
        startTargetBridge = { steps += "start-bridge"; true },
        verifyTarget = { steps += "verify-target"; verifyTarget },
        restoreSource = { steps += "restore-source"; true }
    )
}
