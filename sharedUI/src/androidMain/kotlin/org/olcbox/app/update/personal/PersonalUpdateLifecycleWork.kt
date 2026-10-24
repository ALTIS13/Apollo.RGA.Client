package org.olcbox.app.update.personal

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Called on the Activity's main thread. Lifecycle cancellation never waits for composition. */
internal class PersonalUpdateLifecycleWork(private val scope: CoroutineScope,
    private val foregroundCheck: suspend () -> Unit) {
    var isResumed: Boolean = false
        private set
    private var foreground: Job? = null
    private var manual: Job? = null

    fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                if (!isResumed) {
                    isResumed = true
                    foreground = scope.launch(start = CoroutineStart.LAZY) { foregroundCheck() }
                    foreground?.start()
                }
            }
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> pause()
            else -> Unit
        }
    }
    fun launchManual(action: suspend () -> Unit): Job? {
        if (!isResumed || manual?.isActive == true) return null
        return scope.launch(start = CoroutineStart.LAZY) { action() }.also {
            manual = it
            it.start()
        }
    }
    fun pause() {
        // Both captured coroutine trees become inactive synchronously before returning.
        isResumed = false
        foreground?.cancel()
        manual?.cancel()
    }
}
