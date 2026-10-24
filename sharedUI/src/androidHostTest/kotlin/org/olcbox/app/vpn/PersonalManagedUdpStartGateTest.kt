package org.olcbox.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonalManagedUdpStartGateTest {
    @Test fun constructionDoesNotInvokeNativeOrPackageDelegates() {
        val calls = mutableListOf<String>()

        PersonalManagedUdpStartGate(
            isPersonalPackage = { calls += "package"; true },
            setNativeClaims = { calls += "claims" },
            setNativeContract = { calls += "selector" },
            startNative = { calls += "start" }
        )

        assertEquals(emptyList<String>(), calls)
    }

    @Test fun successfulPersonalClaimsRequestManagedBeforeNativeStart() {
        val native = RecordingNative()
        val gate = native.gate()

        gate.setClaimsJSON("proof-a")
        gate.start()

        assertEquals(listOf("claims:proof-a", "selector:2", "start"), native.calls)
    }

    @Test fun nextStartWithoutFreshClaimsRequestsLegacyBeforeNativeStart() {
        val native = RecordingNative()
        val gate = native.gate()
        gate.setClaimsJSON("proof-a")
        gate.start()
        native.calls.clear()

        gate.start()

        assertEquals(listOf("selector:0", "start"), native.calls)
    }

    @Test fun firstStartWithoutClaimsExplicitlyRequestsLegacy() {
        val native = RecordingNative()

        native.gate().start()

        assertEquals(listOf("selector:0", "start"), native.calls)
    }

    @Test fun nonPersonalStartConsumesClaimsEvenIfNextStartIsPersonal() {
        val native = RecordingNative()
        var personal = false
        val gate = native.gate { personal }
        gate.setClaimsJSON("proof-a")

        gate.start()
        personal = true
        gate.start()

        assertEquals(
            listOf("claims:proof-a", "selector:0", "start", "selector:0", "start"),
            native.calls
        )
    }

    @Test fun emptyClaimsErasePreviouslyArmedRequest() {
        val native = RecordingNative()
        val gate = native.gate()
        gate.setClaimsJSON("proof-a")
        native.calls.clear()

        gate.setClaimsJSON("")
        gate.start()

        assertEquals(listOf("claims:", "selector:0", "start"), native.calls)
    }

    @Test fun whitespaceClaimsDoNotArmManagedRequest() {
        val native = RecordingNative()
        val gate = native.gate()

        gate.setClaimsJSON(" \t\n")
        gate.start()

        assertEquals(listOf("claims: \t\n", "selector:0", "start"), native.calls)
    }

    @Test fun rejectedClaimsPropagateOriginalErrorAndDoNotArmRequest() {
        val native = RecordingNative()
        val gate = native.gate()
        val failure = IllegalArgumentException("claims-rejected")
        native.claimsFailure = failure

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            gate.setClaimsJSON("proof-a")
        })
        native.claimsFailure = null
        gate.start()

        assertEquals(listOf("claims:proof-a", "selector:0", "start"), native.calls)
    }

    @Test fun failedReplacementClaimsErasePreviousSuccessfulArm() {
        val native = RecordingNative()
        val gate = native.gate()
        gate.setClaimsJSON("proof-a")
        native.calls.clear()
        native.claimsFailure = IllegalArgumentException("replacement-rejected")

        assertThrows(IllegalArgumentException::class.java) { gate.setClaimsJSON("proof-b") }
        native.claimsFailure = null
        gate.start()

        assertEquals(listOf("claims:proof-b", "selector:0", "start"), native.calls)
    }

    @Test fun selectorFailureNeverStartsAndConsumesManagedRequest() {
        val native = RecordingNative()
        val gate = native.gate()
        gate.setClaimsJSON("proof-a")
        native.calls.clear()
        val failure = IllegalStateException("selector-rejected")
        native.selectorFailure = failure

        assertSame(failure, assertThrows(IllegalStateException::class.java) { gate.start() })
        assertEquals(listOf("selector:2"), native.calls)
        native.selectorFailure = null
        native.calls.clear()
        gate.start()

        assertEquals(listOf("selector:0", "start"), native.calls)
    }

    @Test fun nativeStartFailureCannotResurrectManagedRequest() {
        val native = RecordingNative()
        val gate = native.gate()
        gate.setClaimsJSON("proof-a")
        native.calls.clear()
        val failure = IllegalStateException("start-rejected")
        native.startFailure = failure

        assertSame(failure, assertThrows(IllegalStateException::class.java) { gate.start() })
        assertEquals(listOf("selector:2", "start"), native.calls)
        native.startFailure = null
        native.calls.clear()
        gate.start()

        assertEquals(listOf("selector:0", "start"), native.calls)
    }

    @Test fun replacementClearsArmBeforeNativeClaimsAndArmsOnlyAfterSuccess() {
        val calls = mutableListOf<String>()
        lateinit var gate: PersonalManagedUdpStartGate
        gate = PersonalManagedUdpStartGate(
            isPersonalPackage = { true },
            setNativeClaims = { value ->
                calls += "claims:$value"
                if (value == "proof-b") gate.start()
            },
            setNativeContract = { calls += "selector:$it" },
            startNative = { calls += "start" }
        )
        gate.setClaimsJSON("proof-a")
        calls.clear()

        gate.setClaimsJSON("proof-b")
        gate.start()

        assertEquals(
            listOf("claims:proof-b", "selector:0", "start", "selector:2", "start"),
            calls
        )
    }

    private class RecordingNative {
        val calls = mutableListOf<String>()
        var claimsFailure: RuntimeException? = null
        var selectorFailure: RuntimeException? = null
        var startFailure: RuntimeException? = null

        fun gate(isPersonal: () -> Boolean = { true }) = PersonalManagedUdpStartGate(
            isPersonalPackage = isPersonal,
            setNativeClaims = { calls += "claims:$it"; claimsFailure?.let { throw it } },
            setNativeContract = { calls += "selector:$it"; selectorFailure?.let { throw it } },
            startNative = { calls += "start"; startFailure?.let { throw it } }
        )
    }
}
