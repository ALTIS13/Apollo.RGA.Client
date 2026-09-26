package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApolloConsentGateTest {
    @Test fun legacyConsentCannotSkipApolloDisclosure() {
        assertTrue(requiresVpnDisclosure(
            isAndroid = true, legacyAccepted = true, apolloAccepted = false,
            isConnected = false, isConnecting = false
        ))
        assertFalse(requiresVpnDisclosure(
            isAndroid = false, legacyAccepted = true, apolloAccepted = false,
            isConnected = false, isConnecting = false
        ))
    }

    @Test fun stopAndCancelNeverAskForNewConsent() {
        assertFalse(requiresVpnDisclosure(
            isAndroid = true, legacyAccepted = false, apolloAccepted = false,
            isConnected = true, isConnecting = false
        ))
        assertFalse(requiresVpnDisclosure(
            isAndroid = true, legacyAccepted = false, apolloAccepted = false,
            isConnected = false, isConnecting = true
        ))
    }

    @Test fun connectOnLaunchCannotUseLegacyOrUnloadedApolloConsent() {
        assertFalse(autoConnectMayStart(isAndroid = true, apolloAccepted = null))
        assertFalse(autoConnectMayStart(isAndroid = true, apolloAccepted = false))
        assertTrue(autoConnectMayStart(isAndroid = true, apolloAccepted = true))
        assertTrue(autoConnectMayStart(isAndroid = false, apolloAccepted = null))
    }
}
