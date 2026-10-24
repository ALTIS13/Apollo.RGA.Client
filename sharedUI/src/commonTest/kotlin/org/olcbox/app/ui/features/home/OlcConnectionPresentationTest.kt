package org.olcbox.app.ui.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import multiplatform_app.sharedui.generated.resources.*
import org.olcbox.app.vpn.OlcChannelRole
import org.olcbox.app.vpn.OlcConnectionProgress
import org.olcbox.app.vpn.OlcConnectionStage

class OlcConnectionPresentationTest {
    @Test fun inactiveDoesNotOverrideOrdinaryConnection() {
        assertNull(olcConnectionMessage(OlcConnectionProgress()))
    }

    @Test fun reserveEntryDoesNotInventPrimaryFailure() {
        assertEquals(Res.string.apollo_olc_connecting_reserve,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.Connecting, OlcChannelRole.Reserve)))
        assertEquals(Res.string.apollo_olc_primary_failed_reserve,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.ConnectingReserve, OlcChannelRole.Reserve)))
    }

    @Test fun checkingNamesSelectedChannelWithoutConnectedClaim() {
        assertEquals(Res.string.apollo_olc_checking_primary,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.Checking, OlcChannelRole.Primary)))
        assertEquals(Res.string.apollo_olc_checking_reserve,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.Checking, OlcChannelRole.Reserve)))
    }

    @Test fun terminalStatesHaveTheirOwnHonestMessages() {
        assertEquals(Res.string.apollo_olc_unavailable,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.Unavailable)))
        assertEquals(Res.string.apollo_olc_denied,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.Denied)))
        assertEquals(Res.string.apollo_olc_stopping,
            olcConnectionMessage(OlcConnectionProgress(OlcConnectionStage.Stopping)))
    }

    @Test fun completedChannelNamesTheActualPathWithoutRemainingBusy() {
        val primary = OlcConnectionProgress(OlcConnectionStage.ChannelReady, OlcChannelRole.Primary)
        val reserve = OlcConnectionProgress(OlcConnectionStage.ChannelReady, OlcChannelRole.Reserve)
        assertEquals(Res.string.apollo_olc_ready_primary, olcConnectionMessage(primary))
        assertEquals(Res.string.apollo_olc_ready_reserve, olcConnectionMessage(reserve))
        kotlin.test.assertFalse(primary.connecting)
        kotlin.test.assertFalse(reserve.connecting)
        kotlin.test.assertTrue(reserve.active)
    }
}
