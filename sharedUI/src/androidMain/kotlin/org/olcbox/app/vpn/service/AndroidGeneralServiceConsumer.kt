package org.olcbox.app.vpn.service

import java.io.Closeable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.olcbox.app.vpn.AndroidGeneralRuntimeResult
import org.olcbox.app.vpn.GeneralTransportDiagnostics
import org.olcbox.app.net.GeneralBootstrapSelection
import org.olcbox.app.net.PersonalJoinSigningKey
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.vpn.AndroidGeneralRuntimeOptions
import org.olcbox.app.vpn.AndroidGeneralRuntimeOwner
import org.olcbox.app.vpn.GeneralCleanupBudget
import org.olcbox.app.vpn.GeneralTransportHealth33
import org.olcbox.app.vpn.GeneralTransportLossEpisode33
import org.olcbox.app.vpn.GeneralTransportObservation33

internal class GeneralServiceBinding(
    val serviceGeneration: Long, val tun: Any, val network: Any, val snapshot: PhysicalNetworkSnapshot,
    val storageId: String, val link: String, val selection: GeneralBootstrapSelection, val key: PersonalJoinSigningKey
) {
    private val installationId = key.installationId
    private val publicDer = key.publicKeyDer.copyOf()
    fun matchesKey(current: PersonalJoinSigningKey): Boolean = try {
        current.installationId == installationId && current.publicKeyDer.contentEquals(publicDer)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: Exception) { false }
    override fun toString() = "GeneralServiceBinding([redacted])"
}

internal class GeneralServiceOperations(
    val current: suspend () -> Boolean,
    val stopBridge: suspend () -> Boolean,
    val stopOrdinary: suspend () -> Boolean,
    val startBridge: suspend () -> Boolean,
    val restoreOrdinary: suspend () -> Boolean,
    val bridgeEpoch: () -> Long,
    val publish: (GeneralTunnelPhase) -> Unit
)

internal interface GeneralProbeRequest : Closeable { fun body(originalEndElapsedMs: Long): ByteArray? }
internal enum class GeneralServiceResult { TransportVerified, OrdinaryRestored, CandidateRetired, Unavailable, Busy, Superseded }

/** Actual service callbacks own the SAME TUN. Readiness is never a full-TUN release. */
internal class AndroidGeneralServiceConsumer(
    private val owner: AndroidGeneralRuntimeOwner,
    private val requests: (AndroidGeneralRuntimeOptions) -> GeneralProbeRequest,
    private val elapsedMs: () -> Long,
    private val epochMs: () -> Long
) {
    private val lock = Any()
    private var nextOwnerGeneration = 0L
    private var current: Entry? = null
    private var knownSettledTicket: CleanupTicket? = null
    private var knownSettledBinding: GeneralServiceBinding? = null
    internal class CleanupTicket internal constructor(val tun: Any, internal val ownerGeneration: Long,
        internal val serviceGeneration: Long = 0)
    private class Entry(val binding: GeneralServiceBinding, val options: AndroidGeneralRuntimeOptions,
        val operations: GeneralServiceOperations, val ticket: CleanupTicket) {
        @Volatile var retired = false
        @Volatile var startAttempted = false
        @Volatile var startupResult: AndroidGeneralRuntimeResult? = null
        var probe: BodyProbe? = null
        var verified = false
        val transportLoss33 = GeneralTransportLossEpisode33()
        var control: GeneralControlCapture? = null
        val controlOperations = mutableSetOf<GeneralControlOperation>()
    }

    suspend fun handoff(binding: GeneralServiceBinding, options: AndroidGeneralRuntimeOptions,
        operations: GeneralServiceOperations, restoreOnFailure: Boolean = true): GeneralServiceResult {
        currentCoroutineContext().ensureActive()
        diagnostic(options, "handoff=PREFLIGHT")
        if (binding.serviceGeneration <= 0 || binding.snapshot.generation != binding.serviceGeneration ||
            !binding.selection.matchesKeyAndIsUsableAt(binding.key, epochMs()) ||
            options.directRules.lineSequence().none { it == PROBE_RULE } ||
            options.socksUsername.toByteArray().size !in 1..255 || options.socksPassword.toByteArray().size !in 1..255 ||
            !operations.current()) return GeneralServiceResult.Unavailable
        val entry = synchronized(lock) {
            if (current != null) return GeneralServiceResult.Busy
            check(nextOwnerGeneration < Long.MAX_VALUE)
            Entry(binding, options, operations, CleanupTicket(binding.tun, ++nextOwnerGeneration,
                binding.serviceGeneration)).also { current = it }
        }
        // AI-generated: one GENERAL handoff end covers startup plus the separately capped body probe.
        val end = elapsedMs() + 60_000L
        suspend fun stillCurrent(): Boolean {
            currentCoroutineContext().ensureActive()
            return elapsedMs() < end && synchronized(lock) { current === entry && !entry.retired } &&
                binding.matchesKey(binding.key) && binding.selection.matchesKeyAndIsUsableAt(binding.key, epochMs()) &&
                operations.current()
        }
        suspend fun failed(allowNext: Boolean = true): GeneralServiceResult = withContext(NonCancellable) {
            diagnostic(options, "handoff=RETIRE")
            retireEntry(entry)
            val known = stopCaptured(entry.ticket, 20_000)
            diagnostic(options, if (known) "cleanup=KNOWN" else "cleanup=UNKNOWN")
            if (!known) return@withContext GeneralServiceResult.Unavailable
            if (!operations.current()) return@withContext GeneralServiceResult.Superseded
            // This witness is minted only by the same exact physical cleanup,
            // never by a timeout/isRunning=false. The service rechecks policy and
            // the next carrier independently requires a fresh finite admission.
            if (allowNext && !restoreOnFailure) return@withContext GeneralServiceResult.CandidateRetired
            // Successful restore performs its own final service fence, then releases this GENERAL binding.
            val restored = operations.restoreOrdinary()
            diagnostic(options, if (restored) "restore=KNOWN" else "restore=FAILED")
            if (restored) GeneralServiceResult.OrdinaryRestored else GeneralServiceResult.Unavailable
        }
        try {
            if (!stillCurrent()) return failed()
            operations.publish(GeneralTunnelPhase.Checking)
            diagnostic(options, "handoff=STOP_BRIDGE")
            if (!operations.stopBridge() || !stillCurrent()) return failed()
            diagnostic(options, "handoff=STOP_ORDINARY")
            if (!operations.stopOrdinary() || !stillCurrent()) return failed()
            synchronized(lock) { if (!entry.retired) entry.startAttempted = true }
            if (!entry.startAttempted) return failed()
            diagnostic(options, "handoff=OWNER_START")
            val ready = withContext(Dispatchers.IO) {
                val result = if (entry.retired) AndroidGeneralRuntimeResult.DENIED else owner.start(
                    entry.ticket.ownerGeneration, binding.selection, binding.key, options, remaining(end).coerceAtMost(60_000))
                entry.startupResult = result
                result
            }
            if (ready == AndroidGeneralRuntimeResult.DENIED) return failed(allowNext = false)
            if (ready != AndroidGeneralRuntimeResult.READY || !stillCurrent()) return failed()
            diagnostic(options, "handoff=START_BRIDGE")
            if (!operations.startBridge() || !stillCurrent() || operations.bridgeEpoch() <= 0) return failed()
            val bridgeEpoch = operations.bridgeEpoch()
            // Carrier CONNECT and foreign TLS share this local check budget.
            // It never outlives the original handoff end or usable selection.
            val probeEnd = minOf(end, elapsedMs() + 15_000L)
            diagnostic(options, "handoff=BODY_PROBE")
            val probe = BodyProbe(GeneralLoadProbeRequest(requests(options),
                { owner.readLoadDiagnostics(entry.ticket.ownerGeneration) }, options.log), elapsedMs, probeEnd)
            val accepted = synchronized(lock) {
                if (current !== entry || entry.retired) false else { entry.probe = probe; true }
            }
            if (!accepted) return failed() // Request construction is inert; no body/socket/worker was entered.
            val bodyVerified = withTimeoutOrNull(remaining(probeEnd)) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    continuation.invokeOnCancellation { retireEntry(entry) }
                    probe.start { valid -> continuation.resume(valid) }
                }
            } == true
            // Completion/close is not physical settlement: join the exact local worker separately.
            val locallySettled = withContext(Dispatchers.IO) { probe.join(probeEnd) }
            diagnostic(options, if (bodyVerified) "body=VERIFIED" else "body=FAILED")
            diagnostic(options, if (locallySettled) "probeJoin=KNOWN" else "probeJoin=UNKNOWN")
            if (!bodyVerified || !locallySettled || !stillCurrent() || operations.bridgeEpoch() != bridgeEpoch ||
                !owner.isRunning(entry.ticket.ownerGeneration) || !stillCurrent()) return failed()
            val published = synchronized(lock) {
                if (current !== entry || entry.retired || operations.bridgeEpoch() != bridgeEpoch) false
                else { entry.verified = true; true }
            }
            if (!published) return failed()
            // Service callback serializes its own generation/PFD/snapshot fence immediately before UI publication.
            operations.publish(GeneralTunnelPhase.TransportVerified)
            diagnostic(options, "handoff=TRANSPORT_VERIFIED")
            return GeneralServiceResult.TransportVerified
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { retireEntry(entry); stopCaptured(entry.ticket, 20_000) }
            throw cancelled
        } catch (_: Exception) { return failed() }
    }

    private fun diagnostic(options: AndroidGeneralRuntimeOptions, event: String) {
        try { options.log(event) } catch (_: Throwable) { /* Telemetry cannot affect handoff. */ }
    }

    /** Retire before service-generation invalidation or cancellation. Ticket keeps the original owner. */
    fun retire(): CleanupTicket? {
        val entry = synchronized(lock) { current } ?: return null
        retireEntry(entry)
        return entry.ticket
    }
    /** Recovery can retain ONLY this exact binding's actual owner or known stop receipt. */
    fun captureRecoveryTicket(binding: GeneralServiceBinding): CleanupTicket? {
        val captured = synchronized(lock) {
            val active = current
            if (active != null) {
                if (active.binding !== binding) return null
                active
            } else {
                return if (knownSettledBinding === binding) knownSettledTicket else null
            }
        }
        retireEntry(captured)
        return captured.ticket
    }
    private fun retireEntry(entry: Entry) {
        val probe = synchronized(lock) { entry.retired = true; entry.verified = false; entry.probe }
        entry.control?.retire()
        try { entry.operations.publish(GeneralTunnelPhase.Stopping) }
        catch (_: Exception) { /* Cleanup custody does not depend on a UI callback. */ }
        finally { probe?.cancel() }
    }
    /** Exact-ticket cancellation only; never consumes Stop or releases custody. */
    internal fun requestStopCaptured(ticket: CleanupTicket, timeoutMs: Long): Boolean {
        val budget = GeneralCleanupBudget.capture(timeoutMs, elapsedMs) ?: return false
        return requestStopCaptured(ticket, budget)
    }
    internal fun requestStopCaptured(ticket: CleanupTicket, budget: GeneralCleanupBudget): Boolean {
        if (budget.remainingMs() <= 0) return false
        val entry = synchronized(lock) { current?.takeIf { it.ticket === ticket } }
            ?: return hasKnownSettlement(ticket)
        retireEntry(entry)
        val left = budget.remainingMs()
        return left > 0 && (!entry.startAttempted || owner.requestStop(ticket.ownerGeneration, left, budget))
    }
    suspend fun stopCaptured(ticket: CleanupTicket, timeoutMs: Long): Boolean {
        val budget = GeneralCleanupBudget.capture(timeoutMs, elapsedMs) ?: return false
        return stopCaptured(ticket, budget)
    }
    internal suspend fun stopCaptured(ticket: CleanupTicket, budget: GeneralCleanupBudget): Boolean = withContext(NonCancellable) {
        val initialLeft = budget.remainingMs()
        if (initialLeft <= 0) return@withContext false
        val end = elapsedMs() + initialLeft
        val entry = synchronized(lock) { current?.takeIf { it.ticket === ticket } }
            ?: return@withContext hasKnownSettlement(ticket)
        retireEntry(entry)
        if (budget.remainingMs() <= 0) return@withContext false
        // A local close may depend on native cancellation. Notify the SAME
        // bounded retained owner before any blocking join, but do not consume
        // its Stop witness or release either custody until ALL local joins.
        if (entry.startAttempted) owner.requestStop(ticket.ownerGeneration, minOf(remaining(end), budget.remainingMs()), budget)
        stopDiagnostic(entry, "CONTROLS_JOIN")
        val controls = synchronized(lock) { entry.controlOperations.toList() }
        for (operation in controls) if (budget.remainingMs() <= 0 || !withContext(Dispatchers.IO) { operation.closeKnown(end) }) return@withContext false
        stopDiagnostic(entry, "PROBE_JOIN")
        val probe = synchronized(lock) { entry.probe }
        if (budget.remainingMs() <= 0 || probe != null && !withContext(Dispatchers.IO) { probe.join(end) }) return@withContext false
        stopDiagnostic(entry, "OWNER_CONSUME")
        val known = if (!entry.startAttempted) true else withContext(Dispatchers.IO) {
            when (owner.stop(ticket.ownerGeneration, minOf(remaining(end), budget.remainingMs()), budget)) {
                AndroidGeneralRuntimeResult.STOPPED -> true
                AndroidGeneralRuntimeResult.DENIED -> entry.startupResult == AndroidGeneralRuntimeResult.DENIED
                else -> false
            }
        }
        if (remaining(end) <= 0 || budget.remainingMs() <= 0) return@withContext false
        if (!known) return@withContext hasKnownSettlement(ticket)
        synchronized(lock) {
            if (budget.remainingMs() <= 0) false
            else if (current !== entry) current == null && knownSettledTicket === ticket
            else { knownSettledTicket = ticket; knownSettledBinding = entry.binding; current = null; true }
        }
    }
    private fun stopDiagnostic(entry: Entry, stage: String) {
        try { entry.options.log("stopStage=CONSUMER_$stage") } catch (_: Throwable) { }
    }
    internal fun hasKnownSettlement(ticket: CleanupTicket): Boolean = synchronized(lock) {
        current == null && knownSettledTicket === ticket // Only the prior actual known Stop/joins can mint this witness.
    }
    fun hasOwnedRuntime(): Boolean = synchronized(lock) { current != null }
    internal fun captureControl(guard: () -> Boolean, commit: ((() -> Unit) -> Boolean)): GeneralControlCapture? {
        val entry = synchronized(lock) { current?.takeIf { it.verified && !it.retired } } ?: return null
        val host = GeneralControlCapture.canonicalHost(entry.binding.link) ?: return null
        val rules = entry.options.directRules.lineSequence().toSet()
        if ("tunnel-domain:rga.apollot.ru" !in rules || "tunnel-domain:$host" !in rules || !guard() ||
            !owner.isRunning(entry.ticket.ownerGeneration)) return null
        return synchronized(lock) {
            if (current !== entry || entry.retired || !entry.verified) return@synchronized null
            entry.control ?: GeneralControlCapture(entry.options, entry.binding.link,
                {
                    synchronized(lock) { current === entry && !entry.retired && entry.verified } &&
                        entry.binding.matchesKey(entry.binding.key) && guard() &&
                        owner.isRunning(entry.ticket.ownerGeneration) &&
                        synchronized(lock) { current === entry && !entry.retired && entry.verified }
                }, { action -> commit { synchronized(lock) {
                    if (current !== entry || entry.retired || !entry.verified) throw IllegalStateException("GENERAL control retired")
                    action()
                } } }, { operation -> synchronized(lock) {
                    if (current !== entry || entry.retired || !entry.verified ||
                        entry.controlOperations.any { it.subscription == operation.subscription }) false
                    else { entry.controlOperations.add(operation); true }
                } }, { operation -> synchronized(lock) { entry.controlOperations.remove(operation) } }, elapsedMs
            ).also { entry.control = it }
        }
    }
    fun isRunning(): Boolean {
        val entry = synchronized(lock) { current?.takeIf { it.verified && !it.retired } } ?: return false
        return owner.isRunning(entry.ticket.ownerGeneration) && synchronized(lock) { current === entry && entry.verified && !entry.retired }
    }

    /** An exact-binding observation; retired/unknown reads get no reconnect grace. */
    fun observeTransportHealth(binding: GeneralServiceBinding): GeneralTransportObservation33 {
        val entry = synchronized(lock) { current?.takeIf { it.binding === binding && it.verified && !it.retired } }
            ?: return GeneralTransportObservation33(GeneralTransportHealth33.UNKNOWN, true)
        val health = owner.transportHealth(entry.ticket.ownerGeneration)
        return synchronized(lock) {
            if (current !== entry || entry.binding !== binding || !entry.verified || entry.retired)
                GeneralTransportObservation33(GeneralTransportHealth33.UNKNOWN, true)
            else GeneralTransportObservation33(health, entry.transportLoss33.observe(health, elapsedMs()))
        }
    }
    private fun remaining(end: Long) = (end - elapsedMs()).coerceAtLeast(0)
    override fun toString() = "AndroidGeneralServiceConsumer([redacted])"
    companion object {
        const val PROBE_RULE = "tunnel-domain:cloudflare.com"
        fun actual(owner: AndroidGeneralRuntimeOwner): AndroidGeneralServiceConsumer = AndroidGeneralServiceConsumer(
            owner, { GeneralSocksHttpRequest(it, android.os.SystemClock::elapsedRealtime) },
            android.os.SystemClock::elapsedRealtime, System::currentTimeMillis)
    }
}

/** One physical local probe worker. Neither its close nor its join claims native flow settlement. */
private class BodyProbe(private val request: GeneralProbeRequest, private val elapsedMs: () -> Long, private val end: Long) {
    private val done = CountDownLatch(1)
    private val startState = AtomicInteger(0) // NEW -> STARTED or CANCELLED_BEFORE_START, never both.
    @Volatile private var cancelled = false
    @Volatile private var valid = false
    @Volatile private var completed: ((Boolean) -> Unit)? = null
    private val closeOwner = ProbeCloseWorker(request, elapsedMs)
    private val worker = Thread({
        try { if (!cancelled) valid = validTrace(request.body(end)) && !cancelled && elapsedMs() < end }
        catch (_: Exception) { valid = false }
        finally {
            closeOwner.requestClose()
            done.countDown()
            completed?.invoke(valid && !cancelled && elapsedMs() < end)
        }
    }, "rga-general-body-probe").apply { isDaemon = true }
    fun start(completion: (Boolean) -> Unit) {
        completed = completion
        if (!startState.compareAndSet(0, 1)) {
            check(startState.get() == 2) { "GENERAL probe already started [redacted]" }
            completion(false)
            return
        }
        try { worker.start() } catch (_: Exception) {
            closeOwner.requestClose(); done.countDown(); completion(false)
        }
    }
    fun cancel() {
        cancelled = true
        if (startState.compareAndSet(0, 2)) done.countDown() // A never-entered worker is physically known.
        closeOwner.requestClose()
    }
    fun join(originalEnd: Long): Boolean {
        val remaining = (originalEnd - elapsedMs()).coerceAtLeast(0)
        if (remaining <= 0) return false
        return try {
            if (!done.await(remaining, TimeUnit.MILLISECONDS)) return false
            val left = (originalEnd - elapsedMs()).coerceAtLeast(0)
            if (worker.isAlive && left > 0) worker.join(left)
            !worker.isAlive && closeOwner.joinKnown(originalEnd)
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
    private fun validTrace(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty() || bytes.size > 4096 || bytes.any { it.toInt() !in 9..126 && it.toInt() != 10 && it.toInt() != 13 }) return false
        val fields = linkedMapOf<String, String>()
        for (line in bytes.toString(Charsets.US_ASCII).lineSequence().filter { it.isNotEmpty() }) {
            val split = line.indexOf('='); if (split !in 1..32) return false
            val name = line.substring(0, split); if (fields.put(name, line.substring(split + 1)) != null) return false
        }
        val ip = fields["ip"] ?: return false
        val literal = if (ip.contains(':')) ip.length in 3..64 && Regex("[0-9a-fA-F:]+").matches(ip) &&
            try { InetAddress.getByName(ip).address.size == 16 } catch (_: Exception) { false }
        else ip.split('.').let { p -> p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 } }
        return literal && fields["h"] == "cloudflare.com" && fields["visit_scheme"] == "https" && Regex("[A-Z]{2}").matches(fields["loc"].orEmpty())
    }
}

/** ONE retained close worker per exact request. Failed/held attempts retain it; retries never replace it. */
private class ProbeCloseWorker(private val request: GeneralProbeRequest, private val elapsedMs: () -> Long) {
    private class Attempt { val done = CountDownLatch(1) }
    private val lock = Any()
    private val wake = Semaphore(0)
    private val stopped = CountDownLatch(1)
    private var attempt: Attempt? = null
    private var started = false
    private var known = false
    private var broken = false
    private val worker = Thread({
        try {
            while (true) {
                wake.acquireUninterruptibly()
                val captured = synchronized(lock) { attempt } ?: continue
                val closed = try { request.close(); true } catch (_: Exception) { false }
                synchronized(lock) { if (attempt === captured) known = closed }
                captured.done.countDown()
                if (closed) return@Thread
            }
        } finally {
            synchronized(lock) { broken = !known; attempt?.done?.countDown() }
            stopped.countDown()
        }
    }, "rga-general-probe-close").apply { isDaemon = true }
    fun requestClose() {
        synchronized(lock) {
            if (known || broken || attempt?.done?.count == 1L) return
            attempt = Attempt() // New attempt, SAME request/resource and SAME physical worker.
            if (!started) {
                started = true
                try { worker.start() } catch (_: Exception) { broken = true; attempt?.done?.countDown(); stopped.countDown(); return }
            }
            wake.release()
        }
    }
    fun joinKnown(end: Long): Boolean {
        val captured = synchronized(lock) { attempt } ?: return false
        fun remaining() = (end - elapsedMs()).coerceAtLeast(0)
        return try {
            val left = remaining()
            if (left <= 0 || !captured.done.await(left, TimeUnit.MILLISECONDS) || !synchronized(lock) { known }) return false
            val stopLeft = remaining()
            if (stopLeft <= 0 || !stopped.await(stopLeft, TimeUnit.MILLISECONDS)) return false
            val joinLeft = remaining()
            if (worker.isAlive && joinLeft > 0) worker.join(joinLeft)
            !worker.isAlive && remaining() > 0 && synchronized(lock) { known }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }
}

/** Exact raw+TLS socket custody; close-requested is not close-known. */
internal class GeneralProbeSocketCustody : Closeable {
    private class Owned(val socket: Socket, var knownClosed: Boolean = false)
    private val lock = Any()
    private var closing = false
    private val owned = mutableListOf<Owned>()
    fun <T : Socket> allocate(create: () -> T): T = synchronized(lock) {
        check(!closing) { "GENERAL probe denied [redacted]" }
        create().also { owned += Owned(it) } // Publish before connect/handshake; no allocation after close request.
    }
    override fun close() {
        val captured = synchronized(lock) { closing = true; owned.filter { !it.knownClosed } }
        var failed = false
        for (entry in captured) {
            try { entry.socket.close(); synchronized(lock) { entry.knownClosed = true } }
            catch (_: Exception) { failed = true }
        }
        if (failed || synchronized(lock) { owned.any { !it.knownClosed } })
            throw IOException("GENERAL probe close unknown [redacted]")
    }
    override fun toString() = "GeneralProbeSocketCustody([redacted])"
}

/** Fixed foreign HTTPS target through ONLY the captured authenticated GENERAL loopback proxy. */
private class GeneralSocksHttpRequest(private val options: AndroidGeneralRuntimeOptions,
    private val elapsedMs: () -> Long) : GeneralProbeRequest {
    private val sockets = GeneralProbeSocketCustody()
    private val diagnostics = GeneralProbeDiagnostics(options.log)
    override fun close() = sockets.close()
    private fun remaining(end: Long): Int = (end - elapsedMs()).takeIf { it in 1..30_000 }?.toInt() ?: throw IOException(DENIED)
    override fun body(originalEndElapsedMs: Long): ByteArray? {
        return try { bodyChecked(originalEndElapsedMs) }
        catch (error: Exception) { diagnostics.failed(error); throw error }
    }
    private fun bodyChecked(originalEndElapsedMs: Long): ByteArray {
        diagnostics.mark(GeneralProbeStage.LOOPBACK)
        val raw = sockets.allocate { Socket() }
        raw.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), options.socksPort.toInt()), remaining(originalEndElapsedMs))
        raw.soTimeout = remaining(originalEndElapsedMs)
        val input = DataInputStream(raw.getInputStream()); val output = DataOutputStream(raw.getOutputStream())
        fun exact(n: Int): ByteArray { raw.soTimeout = remaining(originalEndElapsedMs); return ByteArray(n).also { input.readFully(it) } }
        diagnostics.mark(GeneralProbeStage.AUTH)
        output.write(byteArrayOf(5, 1, 2)); output.flush()
        require(exact(2).contentEquals(byteArrayOf(5, 2))) { DENIED }
        val user = options.socksUsername.toByteArray(Charsets.UTF_8); val pass = options.socksPassword.toByteArray(Charsets.UTF_8)
        require(user.size in 1..255 && pass.size in 1..255) { DENIED }
        output.writeByte(1); output.writeByte(user.size); output.write(user); output.writeByte(pass.size); output.write(pass); output.flush()
        require(exact(2).contentEquals(byteArrayOf(1, 0))) { DENIED }
        diagnostics.mark(GeneralProbeStage.DESTINATION)
        val host = "cloudflare.com".toByteArray(Charsets.US_ASCII)
        output.write(byteArrayOf(5, 1, 0, 3)); output.writeByte(host.size); output.write(host); output.writeShort(443); output.flush()
        val reply = exact(4)
        try { options.log("socksReply=${GeneralTransportDiagnostics.socksReply(reply).name}") } catch (_: Throwable) { }
        require(reply[0] == 5.toByte() && reply[1] == 0.toByte() && reply[2] == 0.toByte()) { DENIED }
        when (reply[3].toInt() and 255) { 1 -> exact(4); 4 -> exact(16); 3 -> exact(exact(1)[0].toInt() and 255); else -> throw IOException(DENIED) }
        exact(2)
        diagnostics.mark(GeneralProbeStage.TLS)
        val tls = sockets.allocate { (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "cloudflare.com", 443, true) as SSLSocket }
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.soTimeout = remaining(originalEndElapsedMs); tls.startHandshake()
        tls.getOutputStream().apply { write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: cloudflare.com\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush() }
        val stream = BufferedInputStream(tls.getInputStream()); var headerBytes = 0
        fun byte(): Int { tls.soTimeout = remaining(originalEndElapsedMs); return stream.read() }
        fun line(): String {
            val text = ByteArrayOutputStream()
            while (true) {
                val b = byte(); if (b < 0 || ++headerBytes > 8192 || text.size() > 1024) throw IOException(DENIED)
                if (b == 13) { require(byte() == 10) { DENIED }; return text.toByteArray().toString(Charsets.US_ASCII) }
                require(b in 32..126 || b == 9) { DENIED }; text.write(b)
            }
        }
        diagnostics.mark(GeneralProbeStage.STATUS)
        require(line().startsWith("HTTP/1.1 200 ")) { DENIED }
        diagnostics.mark(GeneralProbeStage.HEADERS)
        val headers = linkedMapOf<String, String>()
        while (true) { val h = line(); if (h.isEmpty()) break; val colon = h.indexOf(':'); require(colon > 0) { DENIED }
            require(headers.put(h.substring(0, colon).lowercase(java.util.Locale.US), h.substring(colon + 1).trim()) == null) { DENIED } }
        require(headers["content-type"]?.substringBefore(';')?.trim()?.equals("text/plain", true) == true) { DENIED }
        diagnostics.mark(GeneralProbeStage.BODY)
        val body = ByteArrayOutputStream()
        fun bytes(n: Int) { require(n >= 0 && body.size() + n <= 4096) { DENIED }; repeat(n) { val b = byte(); require(b >= 0) { DENIED }; body.write(b) } }
        val length = headers["content-length"]
        val transfer = headers["transfer-encoding"]
        require(length == null || transfer == null) { DENIED }
        when {
            length != null -> { require(Regex("[0-9]{1,4}").matches(length)) { DENIED }; bytes(length.toInt()) }
            transfer != null -> {
                require(transfer.equals("chunked", true)) { DENIED }
                while (true) {
                    val size = line(); require(Regex("[0-9a-fA-F]{1,4}").matches(size)) { DENIED }
                    val n = size.toInt(16)
                    if (n == 0) { require(line().isEmpty()) { DENIED }; break }
                    bytes(n); require(byte() == 13 && byte() == 10) { DENIED }
                }
            }
            else -> while (true) { val b = byte(); if (b < 0) break; require(body.size() < 4096) { DENIED }; body.write(b) }
        }
        remaining(originalEndElapsedMs)
        diagnostics.mark(GeneralProbeStage.COMPLETE)
        return body.toByteArray()
    }
    override fun toString() = "GeneralSocksHttpRequest([redacted])"
    private companion object { const val DENIED = "GENERAL probe denied [redacted]" }
}
