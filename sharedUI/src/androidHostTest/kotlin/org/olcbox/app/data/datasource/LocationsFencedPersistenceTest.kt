package org.olcbox.app.data.datasource

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlin.coroutines.coroutineContext
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry
import org.olcbox.app.data.repository.SubscriptionFetchFence
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
class LocationsFencedPersistenceTest {
    @Test
    fun missingAtomicCommitNeverFallsBackToOrdinarySave() = runBlocking {
        val source = LocationsDataSourceImpl(RuntimeEnvironment.getApplication() as Context)
        val before = bundle("kept")
        source.saveLocationBundle(before)
        val fence = SubscriptionFetchFence(allowsUrl = { true }, current = { true })

        assertFalse(source.saveLocationBundleFenced(bundle("rejected"), fence))
        assertEquals(before, source.loadLocationBundle())
    }

    @Test
    fun rejectedAtActualCommitPreservesTheAuthoritativeMaster() = runBlocking {
        val source = LocationsDataSourceImpl(RuntimeEnvironment.getApplication() as Context)
        val before = bundle("kept")
        source.saveLocationBundle(before)
        var commitCalls = 0
        val fence = SubscriptionFetchFence(allowsUrl = { true }, current = { true }, commit = {
            // The session retired while IO/preference work was pending. No disk
            // action is authorized merely by the earlier true current() result.
            commitCalls++
            false
        })

        assertFalse(source.saveLocationBundleFenced(bundle("rejected"), fence))
        assertEquals(1, commitCalls)
        assertEquals(before, source.loadLocationBundle())
    }

    @Test
    fun liveCommitWritesTheMasterOnlyInsideTheSuppliedAction() = runBlocking {
        val source = LocationsDataSourceImpl(RuntimeEnvironment.getApplication() as Context)
        source.saveLocationBundle(bundle("previous"))
        val next = bundle("accepted")
        var commitCalls = 0
        val fence = SubscriptionFetchFence(allowsUrl = { true }, current = { true }, commit = { persist ->
            commitCalls++
            persist()
            true
        })

        assertTrue(source.saveLocationBundleFenced(next, fence))
        assertEquals(1, commitCalls)
        assertEquals(next, source.loadLocationBundle())
    }

    @Test
    fun cancellationAfterCommitAuthorizationCannotWriteTheMaster() = runBlocking {
        val source = LocationsDataSourceImpl(RuntimeEnvironment.getApplication() as Context)
        val before = bundle("kept")
        source.saveLocationBundle(before)
        val pending = async {
            val fetchContext = coroutineContext
            val fence = SubscriptionFetchFence(allowsUrl = { true }, current = { true }, commit = { persist ->
                // Models cancellation while the IO thread was waiting for the
                // Service monitor: the live session guard alone still passes.
                fetchContext.cancel()
                persist()
                true
            })
            source.saveLocationBundleFenced(bundle("cancelled"), fence)
        }

        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(before, source.loadLocationBundle())
    }

    private fun bundle(id: String) = LocationBundleV4(locations = listOf(
        LocationEntry.from(id, LocationConfig(id, "invented-room", "a".repeat(64)))
    )).normalized()
}
