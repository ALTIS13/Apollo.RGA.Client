package org.olcbox.app.net

import android.net.Network
import android.os.SystemClock
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Direct HTTPS evidence on exactly one physical Android network; never a VPN-path verifier. */
internal class BoundPhysicalNetworkProbe(
    private val opener: (Network, URL) -> URLConnection = { network, url ->
        network.openConnection(url, Proxy.NO_PROXY)
    },
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val deadlineMs: Long = 4_000L
) {
    private data class Control(val url: URL, val expected: IntRange)

    private data class ProbeCall(
        val started: AtomicBoolean,
        val connection: AtomicReference<HttpURLConnection?>,
        val future: Future<ProbeOutcome>
    )

    suspend fun collect(
        network: Network,
        generation: Long,
        networkId: Long,
        revision: Long
    ): DirectProbeRound = withContext(Dispatchers.IO) {
        val controls = listOf(
            Control(URL("https://www.gstatic.com/generate_204"), 204..204),
            Control(URL("https://www.cloudflare.com/cdn-cgi/trace"), 200..200),
            // An HTTPS redirect proves the domestic origin answered; do not follow it.
            Control(URL("https://ya.ru/"), 200..399),
            Control(URL("https://telemost.yandex.ru/"), 200..399)
        )
        val calls = controls.map { control ->
            val started = AtomicBoolean(false)
            val connection = AtomicReference<HttpURLConnection?>(null)
            val future = executor.submit(Callable {
                started.set(true)
                probe(network, control, connection)
            })
            ProbeCall(started, connection, future)
        }
        val startedAtNs = System.nanoTime()
        try {
            val outcomes = calls.map { call ->
                currentCoroutineContext().ensureActive()
                val remainingNs = TimeUnit.MILLISECONDS.toNanos(deadlineMs) - (System.nanoTime() - startedAtNs)
                try {
                    if (call.future.isDone) call.future.get()
                    else if (remainingNs > 0) call.future.get(remainingNs, TimeUnit.NANOSECONDS)
                    else if (call.started.get()) ProbeOutcome.Failed else ProbeOutcome.Unknown
                } catch (_: TimeoutException) {
                    if (call.started.get()) ProbeOutcome.Failed else ProbeOutcome.Unknown
                } catch (_: ExecutionException) {
                    ProbeOutcome.Unknown
                } catch (_: java.util.concurrent.CancellationException) {
                    ProbeOutcome.Unknown
                }
            }
            currentCoroutineContext().ensureActive()
            DirectProbeRound(
                generation = generation,
                networkId = networkId,
                revision = revision,
                completedAtMs = clockMs(),
                foreignA = outcomes[0],
                foreignB = outcomes[1],
                domestic = when {
                    outcomes.drop(2).any { it == ProbeOutcome.Answered } -> ProbeOutcome.Answered
                    outcomes.drop(2).all { it == ProbeOutcome.Failed } -> ProbeOutcome.Failed
                    else -> ProbeOutcome.Unknown
                }
            )
        } finally {
            calls.forEach { call ->
                if (!call.future.isDone) {
                    call.future.cancel(true)
                    call.connection.get()?.disconnect()
                }
            }
        }
    }

    private fun probe(
        network: Network,
        control: Control,
        current: AtomicReference<HttpURLConnection?>
    ): ProbeOutcome {
        return try {
            val connection = opener(network, control.url) as? HttpURLConnection
                ?: return ProbeOutcome.Unknown
            current.set(connection)
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Cache-Control", "no-store")
            if (connection.responseCode in control.expected) ProbeOutcome.Answered else ProbeOutcome.Unknown
        } catch (_: SSLException) {
            ProbeOutcome.Unknown
        } catch (_: SocketTimeoutException) {
            ProbeOutcome.Failed
        } catch (_: ConnectException) {
            ProbeOutcome.Failed
        } catch (_: NoRouteToHostException) {
            ProbeOutcome.Failed
        } catch (_: UnknownHostException) {
            ProbeOutcome.Failed
        } catch (_: IOException) {
            ProbeOutcome.Unknown
        } catch (_: Exception) {
            ProbeOutcome.Unknown
        } finally {
            current.getAndSet(null)?.disconnect()
        }
    }

    private companion object {
        val executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "rga-physical-probe").apply { isDaemon = true }
        }
    }
}
