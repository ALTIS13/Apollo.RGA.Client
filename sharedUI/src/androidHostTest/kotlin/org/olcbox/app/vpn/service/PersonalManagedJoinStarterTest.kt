package org.olcbox.app.vpn.service

import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.olcbox.app.vpn.AndroidOlcRtcRuntime

class PersonalManagedJoinStarterTest {
    @Test fun proofIsInstalledImmediatelyBeforeNativeStart() {
        val calls = mutableListOf<String>()
        val runtime = recordingRuntime(calls)

        PersonalManagedJoinStarter.start(runtime, "{\"nonce\":\"proof\"}")

        assertEquals(listOf("claims:{\"nonce\":\"proof\"}", "start"), calls)
    }

    @Test fun rejectedProofNeverStartsNative() {
        val calls = mutableListOf<String>()
        val runtime = recordingRuntime(calls, rejectClaims = true)

        assertThrows(IllegalArgumentException::class.java) {
            PersonalManagedJoinStarter.start(runtime, "{\"nonce\":\"proof\"}")
        }

        assertEquals(listOf("claims:{\"nonce\":\"proof\"}"), calls)
    }

    @Test fun failedStartClearsPendingProofBeforeAnotherAttempt() {
        val calls = mutableListOf<String>()
        val runtime = recordingRuntime(calls, rejectStart = true)

        assertThrows(IllegalStateException::class.java) {
            PersonalManagedJoinStarter.start(runtime, "{\"nonce\":\"proof\"}")
        }

        assertEquals(listOf("claims:{\"nonce\":\"proof\"}", "start", "claims:"), calls)
    }

    private fun recordingRuntime(
        calls: MutableList<String>,
        rejectClaims: Boolean = false,
        rejectStart: Boolean = false
    ): AndroidOlcRtcRuntime = Proxy.newProxyInstance(
        AndroidOlcRtcRuntime::class.java.classLoader,
        arrayOf(AndroidOlcRtcRuntime::class.java)
    ) { _, method, args ->
        when (method.name) {
            "setClaimsJSON" -> {
                calls += "claims:${args?.firstOrNull()}"
                if (rejectClaims) throw IllegalArgumentException("invalid")
            }
            "start" -> {
                calls += "start"
                if (rejectStart) throw IllegalStateException("failed")
            }
            "isRunning" -> return@newProxyInstance false
        }
        null
    } as AndroidOlcRtcRuntime
}
