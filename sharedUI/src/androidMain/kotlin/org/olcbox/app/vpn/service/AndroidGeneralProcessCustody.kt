package org.olcbox.app.vpn.service

import java.io.Closeable
import java.util.IdentityHashMap
import kotlinx.coroutines.Job

/** GENERAL-only same-process replacement fence, not a native/global runtime registry or admission. */
internal class AndroidGeneralProcessCustody(private val registrationObserver: (() -> Unit)? = null) {
    private val lock = Any()
    private var current: Lease? = null

    fun acquire(service: Any, create: () -> AndroidGeneralServiceConsumer, retryCleanup: () -> Unit): Lease? = synchronized(lock) {
        current?.let { return@synchronized it.takeIf { lease -> lease.service === service } }
        // This allocation is inert. Factory/native Start is still entered only by the captured consumer.
        Lease(this, service, create(), retryCleanup).also { current = it }
    }
    fun owns(lease: Lease): Boolean = synchronized(lock) { current === lease }
    fun retryForeign(service: Any): Boolean {
        val retained = synchronized(lock) { current?.takeIf { it.service !== service } } ?: return false
        retained.requestCleanup() // Reserve SAME lease before calling old Service; never under process lock.
        return true // Even a just-completed retry does not authorize this Start command.
    }
    private fun release(lease: Lease): Boolean = synchronized(lock) {
        if (current !== lease) false else { current = null; true }
    }

    class Lease internal constructor(private val registry: AndroidGeneralProcessCustody,
        internal val service: Any, val consumer: AndroidGeneralServiceConsumer, private val retryCleanup: () -> Unit) {
        private val lock = Any()
        private val descriptors = IdentityHashMap<Closeable, Boolean>()
        private val bridges = IdentityHashMap<Thread, Boolean>()
        private val jobs = mutableSetOf<Job>()
        private var ticket: AndroidGeneralServiceConsumer.CleanupTicket? = null
        private var retiring = false
        private var transportStopped = false
        private var released = false
        private var epoch = 0L
        private var cleanupRequests = 0
        private fun changed() {
            check(epoch < Long.MAX_VALUE) { "GENERAL custody exhausted [redacted]" }
            epoch++
        }
        internal fun requestCleanup(): Boolean {
            synchronized(lock) {
                if (released || !registry.owns(this)) return false
                check(cleanupRequests < Int.MAX_VALUE) { "GENERAL cleanup exhausted [redacted]" }
                cleanupRequests++; changed()
            }
            try { retryCleanup() } // No lease/process lock around foreign callback or its cleanup registration.
            finally {
                synchronized(lock) { cleanupRequests--; changed() }
                tryRelease()
            }
            return true
        }

        fun isRetiring(): Boolean = synchronized(lock) { retiring }
        fun captureTun(tun: Closeable) = synchronized(lock) {
            check(!retiring && !released) { "GENERAL custody retired [redacted]" }
            if (!descriptors.containsKey(tun)) { descriptors[tun] = false; changed() }
        }
        fun beginCleanup(captured: AndroidGeneralServiceConsumer.CleanupTicket?, tun: Closeable?, bridge: Thread?, capturedJobs: List<Job>) {
            val added = synchronized(lock) {
                if (released) return
                changed() // A new cleanup registration invalidates any in-flight readiness snapshot.
                retiring = true
                if (captured != null) ticket = captured // An UNKNOWN ticket is never discarded by a later null capture.
                if (tun != null && !descriptors.containsKey(tun)) descriptors[tun] = false
                if (bridge != null) bridges[bridge] = false
                registerJobsLocked(capturedJobs) // SAME critical section as ticket/resources, before any unlock.
            }
            registry.registrationObserver?.invoke() // Inert actual registry; controlled observation outside locks.
            installCompletionObservers(added)
        }
        fun observeJobs(captured: List<Job>) {
            val added = synchronized(lock) {
                if (released) return
                registerJobsLocked(captured)
            }
            installCompletionObservers(added)
        }
        private fun registerJobsLocked(captured: List<Job>): List<Job> =
            captured.filter { jobs.add(it) }.also { if (it.isNotEmpty()) changed() }
        private fun installCompletionObservers(added: List<Job>) {
            for (job in added) job.invokeOnCompletion { tryRelease() }
        }
        /** The actual close invocation, not a caller Boolean or a detached closed flag, mints this receipt. */
        fun closeTun(tun: Closeable) {
            synchronized(lock) {
                check(!released) { "GENERAL custody retired [redacted]" }
                descriptors[tun] = false; changed() // Even a new close attempt is not its prior successful receipt.
            }
            tun.close() // A thrown/held close leaves SAME descriptor and lease unresolved.
            synchronized(lock) { descriptors[tun] = true; changed() }
        }
        /** Necessary legacy transport result; never sufficient to release owner/probe/TUN/jobs custody. */
        fun recordTransportStopped() { synchronized(lock) { if (!released) { transportStopped = true; changed() } } }
        fun tryRelease(): Boolean {
            val capturedTicket: AndroidGeneralServiceConsumer.CleanupTicket?
            val capturedBridges: List<Thread>
            val capturedEpoch: Long
            fun locallyReady() = !released && retiring && transportStopped && cleanupRequests == 0 &&
                descriptors.values.all { it } && jobs.all { it.isCompleted }
            synchronized(lock) {
                if (!locallyReady()) return false
                capturedTicket = ticket
                capturedBridges = bridges.keys.toList()
                capturedEpoch = epoch
            }
            if (consumer.hasOwnedRuntime() || capturedTicket != null && !consumer.hasKnownSettlement(capturedTicket)) return false
            for (bridge in capturedBridges) {
                if (bridge.isAlive) return false
                try { bridge.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
                if (bridge.isAlive) return false
            }
            // Foreign owner checks and physical joins above are NEVER under the process lock.
            return synchronized(lock) {
                if (epoch != capturedEpoch || !locallyReady()) false
                else registry.release(this).also { if (it) released = true }
            } // Atomic final recheck + release seals the lease against late callback/job/resource registration.
        }
        override fun toString() = "GeneralProcessLease([redacted])"
    }
    override fun toString() = "AndroidGeneralProcessCustody([redacted])"
    companion object { val actual = AndroidGeneralProcessCustody(registrationObserver = null) }
}
