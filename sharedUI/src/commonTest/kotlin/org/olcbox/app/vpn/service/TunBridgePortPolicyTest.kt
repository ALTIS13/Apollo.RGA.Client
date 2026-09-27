package org.olcbox.app.vpn.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunBridgePortPolicyTest {
    @Test fun firstCoreStartUsesFreshPort() {
        assertEquals(
            49152,
            TunBridgePortPolicy.corePortForStart(
                tunMode = true, reuseBridge = false, bridgePort = null,
                freshPort = 49152, proxyPort = 1080
            )
        )
    }

    @Test fun inPlaceCoreRecoveryReusesTheBridgePort() {
        assertTrue(
            TunBridgePortPolicy.canReuseBridge(
                bridgePort = 49152, currentCorePort = 49152,
                targetUsesCore = true, proxyPort = 1080
            )
        )
        assertEquals(
            49152,
            TunBridgePortPolicy.corePortForStart(
                tunMode = true, reuseBridge = true, bridgePort = 49152,
                freshPort = 49200, proxyPort = 1080
            )
        )
        assertTrue(TunBridgePortPolicy.matchesActivePort(49152, 49152, 1080))
    }

    @Test fun fullRestartMaySelectAnewPort() {
        assertEquals(
            49200,
            TunBridgePortPolicy.corePortForStart(
                tunMode = true, reuseBridge = false, bridgePort = 49152,
                freshPort = 49200, proxyPort = 1080
            )
        )
    }

    @Test fun missingOrInvalidBridgeCannotBeReusedOrReadAsConnected() {
        for (bridgePort in listOf(null, 0, -1, 65536)) {
            assertFalse(TunBridgePortPolicy.canReuseBridge(bridgePort, 49152, true, 1080))
            assertNull(TunBridgePortPolicy.corePortForStart(true, true, bridgePort, 49200, 1080))
            assertFalse(TunBridgePortPolicy.matchesActivePort(bridgePort, 49152, 1080))
        }
        assertFalse(TunBridgePortPolicy.matchesActivePort(49152, 49200, 1080))
    }

    @Test fun switchingBetweenOlcrtcAndCoreRequiresBridgeRebuild() {
        assertFalse(TunBridgePortPolicy.canReuseBridge(1080, null, true, 1080))
        assertFalse(TunBridgePortPolicy.canReuseBridge(49152, 49152, false, 1080))
        assertTrue(TunBridgePortPolicy.canReuseBridge(1080, null, false, 1080))
        assertTrue(TunBridgePortPolicy.matchesActivePort(1080, null, 1080))
    }
}
