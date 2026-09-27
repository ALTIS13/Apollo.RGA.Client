package org.olcbox.app.net

import org.olcbox.app.data.model.LocationBundleV4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApolloVpnDisclosureGateTest {
    @Test fun quickSettingsOrAlwaysOnStartCannotUseLegacyOrPreviousNotice() {
        assertEquals("Open Apollo.RGA and accept the VPN notice",
            ApolloVpnDisclosureGate.denial(LocationBundleV4(vpnDisclosureAcceptedAt = 1_000L)))
        assertEquals("Open Apollo.RGA and accept the VPN notice",
            ApolloVpnDisclosureGate.denial(LocationBundleV4(
                apolloVpnDisclosureAcceptedAt = 1_000L, apolloVpnDisclosureVersion = 0
            )))
    }

    @Test fun onlyCurrentVersionWithTimestampMayStart() {
        assertNull(ApolloVpnDisclosureGate.denial(LocationBundleV4(
            apolloVpnDisclosureAcceptedAt = 2_000L, apolloVpnDisclosureVersion = 1
        )))
        assertEquals("Open Apollo.RGA and accept the VPN notice",
            ApolloVpnDisclosureGate.denial(LocationBundleV4(apolloVpnDisclosureVersion = 1)))
    }
}
