package org.olcbox.app.ui.features.home

import org.olcbox.app.data.repository.SubscriptionRefreshError
import org.olcbox.app.data.repository.SubscriptionRefreshFailure
import org.olcbox.app.data.repository.SubscriptionRefreshReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun deviceRefusalNoticeIsOnlyNewOnFirstObservedDenial() {
        val url = "https://example.test/sub"
        val report = SubscriptionRefreshReport(failures = listOf(
            SubscriptionRefreshFailure(url, SubscriptionRefreshError.DeviceDenied, 200)
        ))
        assertTrue(report.hasNewDeviceDenial(emptySet()))
        assertFalse(report.hasNewDeviceDenial(setOf(url)))
        assertFalse(SubscriptionRefreshReport(failures = listOf(
            SubscriptionRefreshFailure(url, SubscriptionRefreshError.Unreachable)
        )).hasNewDeviceDenial(emptySet()))
    }
}
