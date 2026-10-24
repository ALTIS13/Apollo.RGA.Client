package org.olcbox.app.net

import android.content.Context
import android.net.Network
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Dormant fixed-origin transport. No endpoint registration, availability or admission claim. */
internal class AndroidGeneralBootstrapTransport(
    private val calls: Call.Factory,
    private val nanoTime: () -> Long = System::nanoTime
) : GeneralBootstrapHttpTransport {
    override fun post(path: String, body: ByteArray): GeneralBootstrapHttpResponse? {
        if (path !in PATHS || body.isEmpty() || body.size > 4096) return null
        val request = Request.Builder().url(ORIGIN + path).header("Cache-Control", "no-store")
            .header("Accept", "application/json").post(body.copyOf().toRequestBody(JSON)).build()
        val start = nanoTime()
        var call: Call? = null
        try {
            if (Thread.currentThread().isInterrupted) throw CancellationException("GENERAL transport cancelled [redacted]")
            call = calls.newCall(request)
            // The production client also owns one callTimeout spanning DNS/connect/body; never renew by chunk.
            call.timeout().timeout(5, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (expired(start) || response.code != 200 || response.priorResponse != null ||
                    response.request.url != request.url || !jsonContentType(response.header("Content-Type")) ||
                    !response.headers("Cache-Control").joinToString(",").split(',').any { it.trim().equals("no-store", true) }) return null
                val responseBody = response.body
                if (responseBody.contentLength() > 8192) return null
                val output = ByteArrayOutputStream()
                responseBody.byteStream().use { input ->
                    val chunk = ByteArray(1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw CancellationException("GENERAL transport cancelled [redacted]")
                        if (expired(start)) return null
                        val count = input.read(chunk)
                        if (expired(start)) return null
                        if (count < 0) break
                        if (output.size() + count > 8192) return null
                        output.write(chunk, 0, count)
                    }
                }
                return GeneralBootstrapHttpResponse(200, response.header("Content-Type"), output.toByteArray(), "no-store")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("GENERAL transport cancelled [redacted]")
            observeGeneralBootstrap(when (error) {
                is java.net.UnknownHostException -> GeneralBootstrapDiagnostic.DnsFailure
                is javax.net.ssl.SSLException -> GeneralBootstrapDiagnostic.TlsFailure
                is java.io.IOException -> GeneralBootstrapDiagnostic.IoFailure
                else -> GeneralBootstrapDiagnostic.OtherFailure
            })
            return null
        } finally { call?.cancel() }
    }
    private fun expired(start: Long): Boolean {
        val elapsed = nanoTime() - start
        return elapsed < 0 || elapsed >= 5_000_000_000L
    }
    private fun jsonContentType(value: String?) = value?.substringBefore(';')?.trim()?.equals("application/json", true) == true
    override fun toString() = "AndroidGeneralBootstrapTransport([redacted])"
    companion object {
        private const val ORIGIN = "https://rga.apollot.ru"
        private val PATHS = setOf("/v1/general/link/begin", "/v1/general/bootstrap",
            "/v1/managed/link/begin", "/v1/managed/bootstrap",
            "/v1/managed/reserve/link/begin", "/v1/managed/reserve/bootstrap")
        private val JSON = "application/json".toMediaType()
        // No custom TLS factory/trust manager/hostname verifier: retain platform trust and hostname checks.
        internal fun secureClient(socketFactory: SocketFactory, dns: Dns): OkHttpClient = OkHttpClient.Builder()
            .socketFactory(socketFactory).dns(dns).proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false).fastFallback(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()

        fun forApp(context: Context, network: Network): AndroidGeneralBootstrapTransport? {
            if (context.packageName != "tech.gatealtas.rga") return null
            val captured = network
            return AndroidGeneralBootstrapTransport(secureClient(captured.socketFactory,
                Dns { hostname -> captured.getAllByName(hostname).toList() }))
        }
    }
}
