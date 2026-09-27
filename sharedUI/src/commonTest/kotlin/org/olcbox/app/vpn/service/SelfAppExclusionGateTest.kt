package org.olcbox.app.vpn.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfAppExclusionGateTest {
    @Test fun failedSelfExclusionRejectsTunnelAndReportsFailure() {
        var failureReported = false

        val mayEstablish = SelfAppExclusionGate.apply(
            excludeSelf = { false },
            onFailure = { failureReported = true }
        )

        assertFalse(mayEstablish)
        assertTrue(failureReported)
    }

    @Test fun successfulSelfExclusionAllowsTunnelWithoutError() {
        var failureReported = false

        val mayEstablish = SelfAppExclusionGate.apply(
            excludeSelf = { true },
            onFailure = { failureReported = true }
        )

        assertTrue(mayEstablish)
        assertFalse(failureReported)
    }
}
