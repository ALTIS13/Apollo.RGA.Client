package org.olcbox.app.vpn

import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.olcbox.app.net.GeneralBootstrapV1
import org.olcbox.app.net.GeneralBootstrapSelection
import org.olcbox.app.net.PersonalJoinSigningKey

/** An inert handle: create must not allocate JNI; initialize is owned separately. */
internal fun interface AndroidGeneralRuntimeHandleFactory {
    fun create(metadata: GeneralBootstrapV1, installation: PersonalJoinSigningKey): AndroidGeneralRuntimeHandle
    fun create(selection: GeneralBootstrapSelection, installation: PersonalJoinSigningKey): AndroidGeneralRuntimeHandle =
        throw IllegalStateException(GENERAL_RUNTIME_DENIED)
}

/** A real typed implementation is required; legacy/default factories cannot acquire it by forwarding. */
internal interface AndroidGeneralSelectionRuntimeHandleFactory : AndroidGeneralRuntimeHandleFactory {
    override fun create(selection: GeneralBootstrapSelection, installation: PersonalJoinSigningKey): AndroidGeneralRuntimeHandle
}

/** Selection is immutable, even for callers retaining the old mutable interface. */
internal abstract class AndroidGeneralRuntimeHandle : AndroidOlcRtcRuntime {
    private val listenerPort = AtomicLong()
    protected val capturedSocksPort: Long get() = listenerPort.get()
    /** Pure Kotlin custody, before allocation/setters; same-value retries never change it. */
    fun captureSocksPort(value: Long) {
        check(value in 1..65535) { GENERAL_RUNTIME_DENIED }
        check(listenerPort.compareAndSet(0, value) || listenerPort.get() == value) { GENERAL_RUNTIME_DENIED }
    }
    protected fun validateCapturedSocksPort(value: Long) {
        check(value in 1..65535 && capturedSocksPort == value) { GENERAL_RUNTIME_DENIED }
    }
    abstract fun initialize()
    abstract fun listenerReleased(): Boolean
    open fun readLoadDiagnostics(): String = ""
    /** Legacy/unsupported bindings are not Healthy. Separate from isRunning custody. */
    open fun transportHealth(): GeneralTransportHealth33 = GeneralTransportHealth33.UNKNOWN
    final override fun setProvider(value: String): Unit = deny()
    final override fun setRoom(value: String): Unit = deny()
    final override fun setDeviceID(value: String): Unit = deny()
    final override fun setKey(value: String): Unit = deny()
    final override fun setTransport(value: String): Unit = deny()
    final override fun setClaimsJSON(value: String): Unit = deny()
    final override fun check(provider: String, transport: String, room: String, deviceId: String, key: String,
                             socksPort: Long, timeoutMs: Long, fps: Long, batch: Long): Long = deny()
    final override fun ping(provider: String, transport: String, room: String, deviceId: String, key: String,
                            socksPort: Long, timeoutMs: Long, url: String, fps: Long, batch: Long): Long = deny()
    private fun deny(): Nothing = throw IllegalStateException(GENERAL_RUNTIME_DENIED)
}

internal class AndroidGeneralRuntimeOptions(
    val protect: (Long) -> Boolean,
    val socksPort: Long,
    val socksUsername: String,
    val socksPassword: String,
    val dns: String,
    val directRules: String,
    val fps: Long = 30,
    val batch: Long = 1,
    val udp: Boolean = false,
    val log: (String) -> Unit = {}
) {
    init { require(socksPort in 1..65535 && fps > 0 && batch > 0) { GENERAL_RUNTIME_DENIED } }
    override fun toString() = "AndroidGeneralRuntimeOptions([redacted])"
}

internal enum class AndroidGeneralRuntimeResult { READY, STOPPED, BUSY, UNKNOWN, DENIED }
internal const val GENERAL_RUNTIME_DENIED = "GENERAL runtime denied [redacted]"

/** One immutable cleanup cutoff, not a session/admission capability. */
internal class GeneralCleanupBudget private constructor(private val end: Long, private val elapsedMs: () -> Long) {
    fun remainingMs(): Long = (end - elapsedMs()).coerceIn(0, 30_000)
    companion object {
        fun capture(timeoutMs: Long, elapsedMs: () -> Long): GeneralCleanupBudget? {
            val now = elapsedMs()
            return if (timeoutMs in 1..30_000 && now >= 0 && now <= Long.MAX_VALUE - timeoutMs)
                GeneralCleanupBudget(now + timeoutMs, elapsedMs) else null
        }
    }
}

/**
 * One captured owner until every registered Kotlin worker physically joins and owned
 * Stop + idle + exact listener witness succeed. No native call occurs under [lock].
 * Startup end is NOT a session/grant lifetime. Cleanup retries retain this same owner
 * and get a new finite operational end only after the previous workers have joined.
 */
internal class AndroidGeneralRuntimeOwner(
    private val factory: AndroidGeneralRuntimeHandleFactory,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val epochMs: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private var current: Entry? = null
    private var highGeneration = 0L

    private class Job(action: () -> Unit) {
        val done = CountDownLatch(1)
        val thread = Thread({ try { action() } finally { done.countDown() } }, "rga-general-owner")
            .apply { isDaemon = true }
    }
    private class Entry(val generation: Long, val startupEnd: Long, val log: (String) -> Unit) {
        var handle: AndroidGeneralRuntimeHandle? = null
        lateinit var startup: Job
        val launchDone = CountDownLatch(1)
        var stop: Job? = null
        var stopEnd = 0L
        var stopping = false
        var ready = false
        var unknown = false
        // Failures remain attached until full known quiescence, never idle alone.
        var failedWorkers = 0
        var stopKnown = false
    }

    fun start(generation: Long, selection: GeneralBootstrapSelection, installation: PersonalJoinSigningKey,
        options: AndroidGeneralRuntimeOptions, timeoutMs: Long): AndroidGeneralRuntimeResult {
        val end = end(timeoutMs) ?: run { diagnostic(options, "stage=DENIED_TIMEOUT"); return AndroidGeneralRuntimeResult.DENIED }
        val typed = factory as? AndroidGeneralSelectionRuntimeHandleFactory
            ?: run { diagnostic(options, "stage=DENIED_FACTORY"); return AndroidGeneralRuntimeResult.DENIED }
        if (generation <= 0 || !selection.matchesKeyAndIsUsableAt(installation, epochMs())) {
            diagnostic(options, "stage=DENIED_SELECTION"); return AndroidGeneralRuntimeResult.DENIED
        }
        return startCaptured(generation, end, options) { typed.create(selection, installation) }
    }

    fun start(generation: Long, metadata: GeneralBootstrapV1, installation: PersonalJoinSigningKey,
              options: AndroidGeneralRuntimeOptions, timeoutMs: Long): AndroidGeneralRuntimeResult {
        val end = end(timeoutMs) ?: return AndroidGeneralRuntimeResult.DENIED
        if (generation <= 0 || !metadata.isUsableAt(epochMs())) return AndroidGeneralRuntimeResult.DENIED
        return startCaptured(generation, end, options) { factory.create(metadata, installation) }
    }

    private fun startCaptured(generation: Long, end: Long, options: AndroidGeneralRuntimeOptions,
        create: () -> AndroidGeneralRuntimeHandle): AndroidGeneralRuntimeResult {
        val entry = synchronized(lock) {
            if (current != null) return AndroidGeneralRuntimeResult.BUSY
            if (generation <= highGeneration) return AndroidGeneralRuntimeResult.DENIED
            val owned = Entry(generation, end, options.log)
            owned.startup = Job { runStartup(owned, create, options) }
            highGeneration = generation
            current = owned // Publish job + owner BEFORE factory/JNI can run.
            owned
        }
        launch(entry, entry.startup)
        val joined = join(entry.startup, end)
        return synchronized(lock) {
            if (current !== entry) AndroidGeneralRuntimeResult.DENIED
            else if (joined && entry.ready && !entry.stopping && !entry.unknown && remaining(end) > 0)
                AndroidGeneralRuntimeResult.READY
            else { entry.ready = false; entry.unknown = true; AndroidGeneralRuntimeResult.UNKNOWN }
        }
    }

    fun isReady(generation: Long): Boolean = synchronized(lock) {
        current?.let { it.generation == generation && it.ready && !it.stopping && !it.unknown &&
            it.startup.done.count == 0L && !it.startup.thread.isAlive } ?: false
    }

    /** Capture before querying JNI; never invoke it under the owner monitor. */
    fun isRunning(generation: Long): Boolean {
        val captured = synchronized(lock) { current?.takeIf { it.generation == generation && it.ready &&
            !it.stopping && !it.unknown } } ?: return false
        val running = try { captured.handle?.isRunning() == true } catch (_: Throwable) { false }
        return synchronized(lock) { current === captured && captured.ready && !captured.stopping && !captured.unknown && running }
    }

    /** Query the exact retained handle outside the monitor, then revalidate entry. */
    fun transportHealth(generation: Long): GeneralTransportHealth33 {
        val captured = synchronized(lock) { current?.takeIf { it.generation == generation && it.ready &&
            !it.stopping && !it.unknown && it.startup.done.count == 0L && !it.startup.thread.isAlive } }
            ?: return GeneralTransportHealth33.UNKNOWN
        val health = try { captured.handle?.transportHealth() ?: GeneralTransportHealth33.UNKNOWN }
            catch (_: Throwable) { GeneralTransportHealth33.UNKNOWN }
        return synchronized(lock) {
            if (current === captured && captured.ready && !captured.stopping && !captured.unknown) health
            else GeneralTransportHealth33.UNKNOWN
        }
    }

    /** Existing bounded native collector only; no log callback or Stop-path read. */
    fun readLoadDiagnostics(generation: Long): String {
        val captured = synchronized(lock) { current?.takeIf { it.generation == generation && it.ready &&
            !it.stopping && !it.unknown && it.startup.done.count == 0L && !it.startup.thread.isAlive } } ?: return ""
        val snapshot = try { captured.handle?.readLoadDiagnostics().orEmpty() } catch (_: Throwable) { "" }
        return synchronized(lock) {
            if (current === captured && captured.ready && !captured.stopping && !captured.unknown) snapshot else ""
        }
    }

    /** Cancellation notification only. Keep this exact owner/job until stop consumes
     * its completed witness after the caller's local socket/worker joins. */
    fun requestStop(generation: Long, timeoutMs: Long, budget: GeneralCleanupBudget? = null): Boolean {
        val requestedEnd = end(minOf(timeoutMs, budget?.remainingMs() ?: timeoutMs)) ?: return false
        val captured = synchronized(lock) {
            val entry = current?.takeIf { it.generation == generation } ?: return false
            if (remaining(requestedEnd, budget) <= 0) return false
            entry.stopping = true; entry.ready = false
            val previous = entry.stop
            if (previous != null && (entry.stopKnown || previous.done.count != 0L || previous.thread.isAlive ||
                entry.startup.done.count != 0L || entry.startup.thread.isAlive)) return true
            // A held or known job is never replayed. Only physically joined
            // failed workers allow a new bounded attempt for this SAME owner.
            entry.stopEnd = requestedEnd
            entry.stopKnown = false
            val job = Job { runStop(entry, requestedEnd, budget) }
            entry.stop = job // Register BEFORE the native cancellation effect.
            entry to job
        }
        launch(captured.first, captured.second)
        return true // Not a join, known Stop, idle or listener-release receipt.
    }

    fun stop(generation: Long, timeoutMs: Long, budget: GeneralCleanupBudget? = null): AndroidGeneralRuntimeResult {
        val newEnd = end(minOf(timeoutMs, budget?.remainingMs() ?: timeoutMs)) ?: return AndroidGeneralRuntimeResult.DENIED
        val entry = synchronized(lock) {
            val owned = current ?: return AndroidGeneralRuntimeResult.DENIED
            if (owned.generation != generation) return AndroidGeneralRuntimeResult.DENIED
            owned.stopping = true; owned.ready = false
            owned
        }
        // Previous stop cannot be erased by its timeout or a later isRunning=false.
        val previous = synchronized(lock) { entry.stop }
        if (previous != null && !join(previous, newEnd, budget)) return AndroidGeneralRuntimeResult.BUSY
        if (previous != null && !join(entry.startup, newEnd, budget)) return AndroidGeneralRuntimeResult.BUSY
        val job = synchronized(lock) {
            if (current !== entry || entry.stop !== previous) return AndroidGeneralRuntimeResult.BUSY
            if (remaining(newEnd, budget) <= 0) return AndroidGeneralRuntimeResult.BUSY
            if (previous != null && entry.stopKnown) {
                current = null
                return AndroidGeneralRuntimeResult.STOPPED
            }
            entry.stopEnd = newEnd
            entry.stopKnown = false
            Job { runStop(entry, newEnd, budget) }.also { entry.stop = it }
        }
        launch(entry, job)
        val joined = join(job, newEnd, budget)
        return synchronized(lock) {
            if (current !== entry || entry.stop !== job) AndroidGeneralRuntimeResult.DENIED
            else if (joined && remaining(newEnd, budget) > 0 && entry.stopKnown && !entry.startup.thread.isAlive && entry.startup.done.count == 0L) {
                current = null; AndroidGeneralRuntimeResult.STOPPED
            } else { entry.unknown = true; AndroidGeneralRuntimeResult.UNKNOWN }
        }
    }

    private fun runStartup(entry: Entry, create: () -> AndroidGeneralRuntimeHandle,
                           options: AndroidGeneralRuntimeOptions) {
        try {
            diagnostic(options, "stage=CREATE")
            val handle = create() // Contract: inert Kotlin handle, no JNI.
            val allowed = synchronized(lock) {
                entry.handle = handle
                current === entry && !entry.stopping && remaining(entry.startupEnd) > 0
            }
            if (!allowed) return
            handle.captureSocksPort(options.socksPort) // Inert and immutable BEFORE JNI/init can fail.
            diagnostic(options, "stage=INITIALIZE")
            handle.initialize()
            diagnostic(options, "stage=CONFIGURE")
            handle.setProtector(options.protect)
            handle.setLogWriter { message ->
                val ackRead = GeneralTransportDiagnostics.ackRead(message)
                if (ackRead != null) {
                    diagnostic(options, "nativeAckRead=${ackRead.classification.name} ownEndExpired=${ackRead.ownEndExpired}")
                } else {
                    val event = GeneralTransportDiagnostics.native(message)
                    diagnostic(options, if (event == GeneralTransportDiagnostics.NativeEvent.OTHER)
                        "GENERAL native event [redacted]" else "native=${event.name}")
                }
            }
            handle.setSocksPort(options.socksPort)
            handle.setSocksCredentials(options.socksUsername, options.socksPassword)
            handle.setSocksListenHost("127.0.0.1")
            handle.setDNS(options.dns)
            handle.setDirectRules(options.directRules)
            handle.setVP8Options(options.fps, options.batch)
            handle.setUDP(options.udp)
            if (synchronized(lock) { current !== entry || entry.stopping || remaining(entry.startupEnd) <= 0 }) return
            diagnostic(options, "stage=START")
            handle.start()
            entry.launchDone.countDown() // Stop may now cancel a held WaitReady, never JNI init/Start.
            if (synchronized(lock) { current !== entry || entry.stopping }) return
            val timeout = remaining(entry.startupEnd)
            check(timeout > 0) { GENERAL_RUNTIME_DENIED }
            diagnostic(options, "stage=WAIT_READY")
            handle.waitReady(timeout)
            val ready = synchronized(lock) {
                if (current === entry && !entry.stopping && remaining(entry.startupEnd) > 0 && !entry.unknown) {
                    entry.ready = true
                    true
                } else false
            }
            if (ready) diagnostic(options, "stage=READY")
        } catch (error: Throwable) {
            val code = (error as? GeneralNativeFailure)?.code ?: GeneralNativeFailure.Code.OTHER
            diagnostic(options, "startupFailure=${code.name}")
            (error as? GeneralNativeFailure)?.providerBoundary?.let { diagnostic(options, it.diagnostic()) }
            diagnostic(options, "stage=FAILED")
            failed(entry)
        }
        finally { entry.launchDone.countDown() }
    }

    /** Fixed vocabulary only; telemetry failure cannot change custody or admission. */
    private fun diagnostic(options: AndroidGeneralRuntimeOptions, event: String) {
        try { options.log(event) } catch (_: Throwable) { /* Observers are not runtime authority. */ }
    }

    private fun runStop(entry: Entry, end: Long, budget: GeneralCleanupBudget? = null) {
        try {
            stopDiagnostic(entry, "STARTUP_GATE")
            check(await(entry.launchDone, end, budget)) { GENERAL_RUNTIME_DENIED }
            val handle = synchronized(lock) { entry.handle } ?: throw IllegalStateException(GENERAL_RUNTIME_DENIED)
            val timeout = remaining(end, budget)
            check(timeout > 0) { GENERAL_RUNTIME_DENIED }
            stopDiagnostic(entry, "NATIVE_ENTER")
            check(remaining(end, budget) > 0) { GENERAL_RUNTIME_DENIED }
            handle.stop(minOf(timeout, remaining(end, budget)))
            stopDiagnostic(entry, "NATIVE_RETURN")
            check(remaining(end, budget) > 0 && join(entry.startup, end, budget)) { GENERAL_RUNTIME_DENIED }
            stopDiagnostic(entry, "STARTUP_JOINED")
            check(remaining(end, budget) > 0 && !handle.isRunning()) { GENERAL_RUNTIME_DENIED }
            stopDiagnostic(entry, "IDLE")
            check(remaining(end, budget) > 0 && handle.listenerReleased() && remaining(end, budget) > 0) { GENERAL_RUNTIME_DENIED }
            val known = synchronized(lock) {
                if (current === entry && entry.stopEnd == end && remaining(end, budget) > 0) {
                    entry.stopKnown = true; true
                } else false
            }
            if (known) stopDiagnostic(entry, "KNOWN")
            else { failed(entry); stopDiagnostic(entry, "UNKNOWN") }
        } catch (_: Throwable) { failed(entry); stopDiagnostic(entry, "UNKNOWN") }
    }
    private fun stopDiagnostic(entry: Entry, stage: String) {
        try { entry.log("stopStage=OWNER_$stage") } catch (_: Throwable) { }
    }

    private fun failed(entry: Entry) = synchronized(lock) {
        entry.unknown = true; entry.ready = false; entry.failedWorkers++
    }
    private fun launch(entry: Entry, job: Job) {
        try { job.thread.start() }
        catch (_: Throwable) { failed(entry); entry.launchDone.countDown(); job.done.countDown() }
    }
    private fun end(timeoutMs: Long): Long? {
        val now = elapsedMs()
        return if (timeoutMs in 1..60_000 && now >= 0 && now <= Long.MAX_VALUE - timeoutMs) now + timeoutMs else null
    }
    private fun remaining(end: Long, budget: GeneralCleanupBudget? = null) =
        minOf((end - elapsedMs()).coerceAtLeast(0), budget?.remainingMs() ?: Long.MAX_VALUE)
    private fun await(latch: CountDownLatch, end: Long, budget: GeneralCleanupBudget? = null): Boolean = try {
        val timeout = remaining(end, budget)
        timeout > 0 && latch.await(timeout, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    private fun join(job: Job, end: Long, budget: GeneralCleanupBudget? = null): Boolean {
        if (!await(job.done, end, budget)) return false
        return try {
            val timeout = remaining(end, budget)
            if (job.thread.isAlive && timeout > 0) job.thread.join(timeout)
            !job.thread.isAlive
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
}
