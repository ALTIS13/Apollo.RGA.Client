package org.olcbox.app.ui.activities

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalImportConfirmationStateTest {
    private val syntheticLink = "https://subscription.example.test/sub?token=synthetic"

    @Test fun externalLinkHasNoImportEffectUntilUserConfirmsAndOnlyImportsOnce() {
        val state = ExternalImportConfirmationState()
        val imports = mutableListOf<String>()

        state.confirm { imports.add(it) }
        state.offer(syntheticLink)
        assertEquals(syntheticLink, state.pendingLink)
        assertEquals(emptyList(), imports)

        state.confirm { imports.add(it) }
        state.confirm { imports.add(it) }
        assertEquals(listOf(syntheticLink), imports)
        assertNull(state.pendingLink)
    }

    @Test fun dismissDiscardsLinkWithoutImporting() {
        val state = ExternalImportConfirmationState()
        val imports = mutableListOf<String>()

        state.offer(syntheticLink)
        state.dismiss()
        state.confirm { imports.add(it) }

        assertNull(state.pendingLink)
        assertEquals(emptyList(), imports)
    }

    @Test fun conflictingIntentsStayBlockedUntilUserDismissesConflict() {
        val state = ExternalImportConfirmationState()
        val imports = mutableListOf<String>()
        val newestLink = "https://subscription.example.test/next?token=synthetic2"
        val thirdLink = "https://subscription.example.test/third?token=synthetic3"

        assertEquals(ExternalImportConfirmationState.OfferResult.ACCEPTED, state.offer(syntheticLink))
        assertEquals(ExternalImportConfirmationState.OfferResult.CONFLICT, state.offer(newestLink))
        assertNull(state.pendingLink)
        assertEquals(true, state.hasConflict)
        assertEquals(ExternalImportConfirmationState.OfferResult.CONFLICT, state.offer(thirdLink))
        state.confirm { imports.add(it) }
        assertEquals(emptyList(), imports)

        state.dismiss()
        assertEquals(false, state.hasConflict)
        assertEquals(ExternalImportConfirmationState.OfferResult.ACCEPTED, state.offer(newestLink))
        state.confirm { imports.add(it) }

        assertEquals(listOf(newestLink), imports)
        assertNull(state.pendingLink)
    }

    @Test fun repeatedSameIntentCannotChangePendingConsentTarget() {
        val state = ExternalImportConfirmationState()

        assertEquals(ExternalImportConfirmationState.OfferResult.ACCEPTED, state.offer(syntheticLink))
        assertEquals(ExternalImportConfirmationState.OfferResult.ALREADY_PENDING, state.offer(syntheticLink))
        assertEquals(syntheticLink, state.pendingLink)
    }

    @Test fun invalidNewIntentRevokesOldConsentTarget() {
        val state = ExternalImportConfirmationState()
        val imports = mutableListOf<String>()

        state.receiveExternalIntent(syntheticLink)
        state.receiveExternalIntent(null)
        state.confirm { imports.add(it) }

        assertNull(state.pendingLink)
        assertEquals(false, state.hasConflict)
        assertEquals(emptyList(), imports)
    }

    @Test fun rapidDistinctIntentsBecomeConflictBeforeUiCollects() {
        val state = ExternalImportConfirmationState()
        val secondLink = "https://subscription.example.test/second?token=synthetic2"

        state.receiveExternalIntent(syntheticLink)
        state.receiveExternalIntent(secondLink)

        assertNull(state.pendingLink)
        assertEquals(true, state.hasConflict)
    }

    @Test fun recreationNeverReoffersStoredViewIntentAndRetainsConflictWithoutUrl() {
        val secondLink = "https://subscription.example.test/second?token=synthetic2"
        val before = ExternalImportConfirmationState()
        before.receiveExternalIntent(syntheticLink)
        before.receiveExternalIntent(secondLink)
        assertEquals(true, before.hasConflict)

        val restored = ExternalImportConfirmationState()
        restored.receiveInitialIntent(secondLink, restoredConflict = before.hasConflict)
        assertNull(restored.pendingLink)
        assertEquals(true, restored.hasConflict)

        val dismissed = ExternalImportConfirmationState()
        dismissed.receiveInitialIntent(syntheticLink, restoredConflict = false)
        assertNull(dismissed.pendingLink)
        assertEquals(false, dismissed.hasConflict)
    }
}
