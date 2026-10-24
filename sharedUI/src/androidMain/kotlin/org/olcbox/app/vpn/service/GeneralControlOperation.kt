package org.olcbox.app.vpn.service

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class GeneralControlResponse(val status: Int, val headers: Map<String, List<String>>, val body: InputStream)
internal interface GeneralControlRequest : Closeable { fun open(originalEnd: Long): GeneralControlResponse }

/** Immutable workers are registered before effects; neither cancellation nor a callback settles them. */
internal class GeneralControlOperation(
    private val create: () -> GeneralControlRequest,
    private val elapsedMs: () -> Long,
    private val end: Long,
    private val max: Long,
    private val current: () -> Boolean,
    private val release: (GeneralControlOperation) -> Unit,
    internal val subscription: Boolean,
) : Closeable {
    private val lock = Any()
    private val input = PipedInputStream(16_384)
    private val output = PipedOutputStream(input)
    @Volatile private var cancelled = false
    @Volatile private var response: GeneralControlResponse? = null
    @Volatile private var failure = false
    private var request: GeneralControlRequest? = null
    private var knownClosed: GeneralControlRequest? = null
    private var knownAfterJoin = false
    private val readers = mutableSetOf<CountDownLatch>()
    private var closeThread: Thread? = null
    private var closeDone: CountDownLatch? = null
    private var started = false
    private var released = false
    private val ready = CountDownLatch(1)
    private val worker = Thread({
        try {
            checkCurrent()
            val actual = create()
            synchronized(lock) { request = actual; knownClosed = null }
            checkCurrent()
            val opened = actual.open(end)
            checkCurrent()
            response = GeneralControlResponse(opened.status, opened.headers.mapValues { it.value.toList() }, input)
            ready.countDown()
            val buffer = ByteArray(8192); var total = 0L
            while (true) {
                checkCurrent()
                val n = opened.body.read(buffer)
                checkCurrent()
                if (n < 0) break
                total += n
                if (total > max) throw IOException(DENIED)
                output.write(buffer, 0, n)
            }
        } catch (_: Exception) { failure = true }
        finally {
            ready.countDown()
            try { output.close() } catch (_: Exception) { failure = true }
            requestClose()
        }
    }, "rga-general-control-body").apply { isDaemon = true }
    private fun checkCurrent() { if (cancelled || elapsedMs() >= end || !current()) throw IOException(DENIED) }
    suspend fun open(): GeneralControlResponse {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if (!started && !cancelled) { started = true; worker.start() } }
        try {
            while (ready.count != 0L) { currentCoroutineContext().ensureActive(); checkCurrent(); delay(10) }
            checkCurrent()
            val value = response ?: throw IOException(DENIED)
            return GeneralControlResponse(value.status, value.headers, object : InputStream() {
                override fun read(): Int = readOwned { input.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    return readOwned { input.read(b, off, len) }
                }
                override fun close() = this@GeneralControlOperation.close()
            })
        } catch (cancelled: CancellationException) {
            cancel(); withContext(NonCancellable + Dispatchers.IO) { closeKnown(minOf(end, elapsedMs() + 20_000)) }; throw cancelled
        } catch (_: Exception) {
            cancel(); withContext(NonCancellable + Dispatchers.IO) { closeKnown(minOf(end, elapsedMs() + 20_000)) }; throw IOException(DENIED)
        }
    }
    private fun readOwned(read: () -> Int): Int {
        checkCurrent()
        val done = CountDownLatch(1)
        synchronized(lock) { if (cancelled) throw IOException(DENIED); readers.add(done) }
        try { return read().also { checkCurrent(); if (it < 0 && failure) throw IOException(DENIED) } }
        finally { done.countDown(); synchronized(lock) { readers.remove(done) } }
    }
    fun cancel() {
        cancelled = true
        try { input.close() } catch (_: Exception) { failure = true }
        requestClose()
    }
    private fun requestClose() {
        val attempt = synchronized(lock) {
            if (closeThread?.isAlive == true || closeDone?.count?.let { it != 0L } == true) return
            val actual = request ?: return
            if (knownClosed === actual && knownAfterJoin) return
            val done = CountDownLatch(1)
            val thread = Thread({
                try { actual.close(); synchronized(lock) { if (request === actual) {
                    knownClosed = actual; knownAfterJoin = !started || !worker.isAlive
                } } }
                catch (_: Exception) { /* exact failed request stays retained for the owned retry */ }
                finally { done.countDown() }
            }, "rga-general-control-close").apply { isDaemon = true }
            closeDone = done; closeThread = thread; thread
        }
        attempt.start()
    }
    fun closeKnown(originalEnd: Long): Boolean {
        if (elapsedMs() >= originalEnd) return false
        cancel()
        val wasStarted = synchronized(lock) { started }
        if (wasStarted) {
            val remaining = originalEnd - elapsedMs()
            if (remaining <= 0) return false
            worker.join(remaining)
            if (worker.isAlive || elapsedMs() >= originalEnd) return false
        }
        val activeReaders = synchronized(lock) { readers.toList() }
        for (reader in activeReaders) {
            val remaining = originalEnd - elapsedMs()
            if (remaining <= 0 || !reader.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) return false
        }
        requestClose()
        val close = synchronized(lock) { closeThread }
        if (close != null) {
            val remaining = originalEnd - elapsedMs()
            if (remaining <= 0) return false
            close.join(remaining)
            if (close.isAlive || elapsedMs() >= originalEnd) return false
        }
        var notifyRelease = false
        val settled = synchronized(lock) {
            if (elapsedMs() >= originalEnd || readers.isNotEmpty() || request != null && (knownClosed !== request || !knownAfterJoin) || started && worker.isAlive ||
                closeThread?.isAlive == true || closeDone?.count?.let { it != 0L } == true) false
            else { if (!released) { released = true; notifyRelease = true }; true }
        }
        if (notifyRelease) release(this)
        return settled
    }
    override fun close() { if (!closeKnown(minOf(end, elapsedMs() + 20_000))) throw IOException(DENIED) }
    override fun toString() = "GeneralControlOperation([redacted])"
    companion object { private const val DENIED = "GENERAL control unavailable" }
}
