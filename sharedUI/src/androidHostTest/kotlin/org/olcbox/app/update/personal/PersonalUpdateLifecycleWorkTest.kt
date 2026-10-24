package org.olcbox.app.update.personal

import androidx.lifecycle.Lifecycle
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalUpdateLifecycleWorkTest {
    @Test fun pauseSynchronouslyDeniesQueuedForegroundAuthorityBeforeSchedulerOrComposition() = runTest {
        var captured: CoroutineContext? = null
        var queuedAuthority: (() -> Unit)? = null
        var commits = 0
        val work = PersonalUpdateLifecycleWork(this) {
            withContext(StandardTestDispatcher(testScheduler)) {
                val cancellation = currentCoroutineContext()
                captured = cancellation
                queuedAuthority = { cancellation.ensureActive(); commits++ }
                awaitCancellation()
            }
        }
        work.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        runCurrent()
        assertTrue(captured!!.isActive)
        work.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // No runCurrent/advanceUntilIdle/recomposition occurs after ON_PAUSE.
        assertFalse(captured.isActive)
        assertThrows(CancellationException::class.java) { queuedAuthority!!.invoke() }
        assertEquals(0, commits)
        runCurrent()
    }

    @Test fun pauseSynchronouslyDeniesQueuedManualHandoffBeforeSchedulerOrComposition() = runTest {
        var captured: CoroutineContext? = null
        var queuedAuthority: (() -> Unit)? = null
        var handoffs = 0
        val work = PersonalUpdateLifecycleWork(this) { }
        work.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val manual = work.launchManual {
            withContext(StandardTestDispatcher(testScheduler)) {
                val cancellation = currentCoroutineContext()
                captured = cancellation
                queuedAuthority = { cancellation.ensureActive(); handoffs++ }
                awaitCancellation()
            }
        }
        assertNotNull(manual)
        runCurrent()
        assertTrue(captured!!.isActive)
        work.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertFalse(captured.isActive)
        assertTrue(manual!!.isCancelled)
        assertThrows(CancellationException::class.java) { queuedAuthority!!.invoke() }
        assertEquals(0, handoffs)
        runCurrent()
    }

    @Test fun pausedStateDeniesNewManualJobEvenWithStaleUiBoolean() = runTest {
        var actions = 0
        val work = PersonalUpdateLifecycleWork(this) { }
        assertNull(work.launchManual { actions++ })
        work.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        work.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertNull(work.launchManual { actions++ })
        runCurrent()
        assertEquals(0, actions)
    }

    @Test fun resumeNeverResurrectsCancelledManualInstall() = runTest {
        var checks = 0
        var manualStarts = 0
        val work = PersonalUpdateLifecycleWork(this) { checks++ }
        work.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val manual = work.launchManual { manualStarts++; awaitCancellation() }
        runCurrent()
        work.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertTrue(manual!!.isCancelled)
        work.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        runCurrent()
        assertEquals(2, checks)
        assertEquals(1, manualStarts)
        work.pause()
        runCurrent()
    }

    @Test fun disposalSynchronouslyCancelsBothCurrentJobReferences() = runTest {
        var foreground: CoroutineContext? = null
        var manual: CoroutineContext? = null
        val work = PersonalUpdateLifecycleWork(this) {
            foreground = currentCoroutineContext(); awaitCancellation()
        }
        work.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        work.launchManual { manual = currentCoroutineContext(); awaitCancellation() }
        runCurrent()
        work.pause()
        assertFalse(foreground!!.isActive)
        assertFalse(manual!!.isActive)
        assertNull(work.launchManual { fail("Disposed work restarted") })
        runCurrent()
    }
}
