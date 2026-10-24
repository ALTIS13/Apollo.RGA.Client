package org.olcbox.app.update.personal

import org.junit.Assert.*
import org.junit.Test

class PersonalUpdateUiModelTest {
    @Test fun uncheckedAndUnavailableNeverClaimCurrentOrOfferInstall() {
        for (state in listOf(PersonalUpdateState.Idle, PersonalUpdateState.Unavailable,
            PersonalUpdateState.SessionChanged, PersonalUpdateState.HandedOff)) {
            val ui = personalUpdateUiModel(state)
            assertEquals(PersonalUpdateAction.CHECK, ui.primary)
            assertNull(ui.secondary)
            assertFalse(ui.warnVpnRestart)
        }
        assertEquals(PersonalUpdateMessage.UNAVAILABLE, personalUpdateUiModel(PersonalUpdateState.Unavailable).message)
        assertEquals(PersonalUpdateMessage.HANDED_OFF, personalUpdateUiModel(PersonalUpdateState.HandedOff).message)
    }
    @Test fun pendingWorkDisablesEveryOwnerAction() {
        for (state in listOf(PersonalUpdateState.Checking, PersonalUpdateState.Downloading)) {
            val ui = personalUpdateUiModel(state)
            assertTrue(ui.busy)
            assertNull(ui.primary)
            assertNull(ui.secondary)
        }
    }
    @Test fun signedOfferOnlyPermitsDownloadBeforeInstall() {
        val ui = personalUpdateUiModel(PersonalUpdateState.Available(3, "1.0.3"))
        assertEquals(PersonalUpdateAction.DOWNLOAD, ui.primary)
        assertEquals(PersonalUpdateAction.CHECK, ui.secondary)
        assertEquals("1.0.3", ui.versionName)
        assertFalse(ui.warnVpnRestart)
    }
    @Test fun readyRequiresExplicitInstallAndWarnsAboutVpnRestart() {
        val ui = personalUpdateUiModel(PersonalUpdateState.Ready(3, "1.0.3"))
        assertEquals(PersonalUpdateAction.INSTALL, ui.primary)
        assertEquals(PersonalUpdateAction.CHECK, ui.secondary)
        assertTrue(ui.warnVpnRestart)
    }
    @Test fun permissionRequestOffersSettingsButNeverAutomaticInstall() {
        val ui = personalUpdateUiModel(PersonalUpdateState.Ready(3, "1.0.3"), PersonalUpdateInstallNotice.PERMISSION_REQUIRED)
        assertEquals(PersonalUpdateAction.OPEN_SETTINGS, ui.primary)
        assertEquals(PersonalUpdateMessage.PERMISSION_REQUIRED, ui.message)
        assertTrue(ui.warnVpnRestart)
    }
    @Test fun returningFromPermissionSettingsNeedsAnotherExplicitInstallTap() {
        val ui = personalUpdateUiModel(PersonalUpdateState.Ready(3, "1.0.3"), PersonalUpdateInstallNotice.PERMISSION_RETURNED)
        assertEquals(PersonalUpdateAction.INSTALL, ui.primary)
        assertEquals(PersonalUpdateMessage.PERMISSION_RETURNED, ui.message)
        assertFalse(ui.busy)
    }
    @Test fun everyControllerRefusalIsCheckOnlyAndCannotExposeRawErrors() {
        for (reason in PersonalUpdateControllerRefusal.entries) {
            val ui = personalUpdateUiModel(PersonalUpdateState.Rejected(reason))
            assertEquals(PersonalUpdateAction.CHECK, ui.primary)
            assertNull(ui.secondary)
            assertNull(ui.versionName)
            assertFalse(ui.warnVpnRestart)
        }
        assertEquals(PersonalUpdateMessage.NETWORK_REFUSED,
            personalUpdateUiModel(PersonalUpdateState.Rejected(PersonalUpdateControllerRefusal.HTTP)).message)
        assertEquals(PersonalUpdateMessage.COMPATIBILITY_REFUSED,
            personalUpdateUiModel(PersonalUpdateState.Rejected(PersonalUpdateControllerRefusal.BASELINE)).message)
    }
    @Test fun stalePermissionNoticeCannotOfferSettingsOnRejectedCandidate() {
        val ui = personalUpdateUiModel(PersonalUpdateState.SessionChanged, PersonalUpdateInstallNotice.PERMISSION_REQUIRED)
        assertEquals(PersonalUpdateAction.CHECK, ui.primary)
        assertEquals(PersonalUpdateMessage.SESSION_CHANGED, ui.message)
    }
    @Test fun nonNewerRefusalExplainsArithmeticAndOnlyOffersManualCheck() {
        val refusal = PersonalUpdateControllerRefusal.entries.singleOrNull { it.name == "NOT_NEWER" }
        assertNotNull("The controller must preserve the signed non-newer outcome", refusal)
        val ui = personalUpdateUiModel(PersonalUpdateState.Rejected(refusal!!),
            PersonalUpdateInstallNotice.PERMISSION_RETURNED)
        assertEquals("NOT_NEWER", ui.message.name)
        assertEquals(PersonalUpdateAction.CHECK, ui.primary)
        assertNull(ui.secondary)
        assertNull(ui.versionName)
        assertFalse(ui.busy)
        assertFalse(ui.warnVpnRestart)
    }
}
