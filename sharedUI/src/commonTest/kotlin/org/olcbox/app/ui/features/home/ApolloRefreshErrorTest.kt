package org.olcbox.app.ui.features.home

import org.olcbox.app.data.repository.SubscriptionRefreshError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApolloRefreshErrorTest {
    @Test fun manualFailureSurvivesUnchangedSnapshotButClearsAfterSuccessfulAutoRefresh() {
        val failure = ManualRefreshFailure(SubscriptionRefreshError.Unreachable, lastSuccessfulRefreshAt = 100L)
        assertEquals(SubscriptionRefreshError.Unreachable, failure.currentError(lastSuccessfulRefreshAt = 100L))
        assertNull(failure.currentError(lastSuccessfulRefreshAt = 200L))
        assertEquals(SubscriptionRefreshError.Unreachable,
            failure.currentError(lastSuccessfulRefreshAt = 100L))
    }

    @Test fun previouslyUnknownTimestampClearsOnlyWhenSuccessIsRecorded() {
        val failure = ManualRefreshFailure(SubscriptionRefreshError.Rejected, lastSuccessfulRefreshAt = null)
        assertEquals(SubscriptionRefreshError.Rejected, failure.currentError(lastSuccessfulRefreshAt = null))
        assertNull(failure.currentError(lastSuccessfulRefreshAt = 500L))
    }
}
