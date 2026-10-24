package org.olcbox.app.vpn.service

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.olcbox.app.vpn.AndroidGeneralRuntimeOptions

/** Inert constructor. Both real raw/TLS sockets are captured before their network/body effects. */
internal class GeneralControlHttpRequest(private val options: AndroidGeneralRuntimeOptions,
    private val url: String, private val host: String, private val headers: Map<String, String>) : GeneralControlRequest {
    private val lock = Any()
    private val sockets = GeneralProbeSocketCustody()
    private var closed = false
    private var call: Call? = null
    private var response: Response? = null
    private var client: OkHttpClient? = null
    override fun open(originalEnd: Long): GeneralControlResponse {
        synchronized(lock) { if (closed) throw IOException(DENIED) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
        val trust = tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
        val raw = object : SocketFactory() {
            override fun createSocket(): Socket = sockets.allocate { object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    if (options.socksUsername.toByteArray().size !in 1..255 || options.socksPassword.toByteArray().size !in 1..255) throw IOException(DENIED)
                    val remote = endpoint as? InetSocketAddress ?: throw IOException(DENIED)
                    if (remote.port != 443 || remote.hostString != host) throw IOException(DENIED)
                    super.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127,0,0,1)), options.socksPort.toInt()), timeout)
                    soTimeout = 20_000
                    // Existing handshake is fixed rga; SAME subscription may have its own canonical host.
                    generalSocksConnect(getInputStream(), getOutputStream(), host, options.socksUsername, options.socksPassword)
                }
            } }
            override fun createSocket(h: String, p: Int): Socket = createSocket().apply { connect(InetSocketAddress(h,p),15_000) }
            override fun createSocket(h: InetAddress, p: Int): Socket = throw IOException(DENIED)
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = throw IOException(DENIED)
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = throw IOException(DENIED)
        }
        val ownedTls = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites() = tls.defaultCipherSuites
            override fun getSupportedCipherSuites() = tls.supportedCipherSuites
            override fun createSocket(s: Socket, h: String, p: Int, auto: Boolean): Socket =
                if (h == host && p == 443) sockets.allocate { tls.createSocket(s,h,p,auto) } else throw IOException(DENIED)
            override fun createSocket(h: String, p: Int): Socket = throw IOException(DENIED)
            override fun createSocket(h: InetAddress, p: Int): Socket = throw IOException(DENIED)
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = throw IOException(DENIED)
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = throw IOException(DENIED)
        }
        val remaining = (originalEnd - android.os.SystemClock.elapsedRealtime()).takeIf { it > 0 } ?: throw IOException(DENIED)
        val built = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .socketFactory(raw).sslSocketFactory(ownedTls, trust)
            .dns(Dns { name -> if (name != host) throw IOException(DENIED) else listOf(InetAddress.getByAddress(host,byteArrayOf(0,0,0,1))) })
            .connectTimeout(minOf(15_000,remaining),TimeUnit.MILLISECONDS).readTimeout(minOf(20_000,remaining),TimeUnit.MILLISECONDS)
            .callTimeout(remaining,TimeUnit.MILLISECONDS).build()
        val request = Request.Builder().url(url).get().header("Accept-Encoding","identity").apply {
            headers.forEach { (k,v) -> header(k,v) }
        }.build()
        val actual = built.newCall(request)
        synchronized(lock) { if (closed) { actual.cancel(); throw IOException(DENIED) }; client=built; call=actual }
        val received = actual.execute()
        synchronized(lock) { response=received; if (closed) throw IOException(DENIED) }
        if (received.headers.byteCount() > 16_384 || received.header("Content-Encoding")?.let { it != "identity" } == true) throw IOException(DENIED)
        return GeneralControlResponse(received.code, received.headers.toMultimap(), received.body.byteStream())
    }
    override fun close() {
        val captured = synchronized(lock) { closed=true; Triple(call,response,client) }
        var failed = false
        fun cleanup(action: () -> Unit) { try { action() } catch (_: Exception) { failed = true } }
        cleanup { captured.first?.cancel() }
        cleanup { captured.second?.close() }
        cleanup { sockets.close() }
        cleanup { captured.third?.connectionPool?.evictAll() }
        cleanup { captured.third?.dispatcher?.executorService?.shutdown() }
        // Attempt every captured cleanup even on failure, but never turn that failure into ACK.
        if (failed) throw IOException(DENIED)
    }
    override fun toString() = "GeneralControlHttpRequest([redacted])"
    companion object { private const val DENIED="GENERAL control unavailable" }
}

private fun generalSocksConnect(input: java.io.InputStream, output: java.io.OutputStream,
    host: String, username: String, password: String) {
    val user=username.toByteArray(Charsets.UTF_8);val pass=password.toByteArray(Charsets.UTF_8)
    fun byte(): Int = input.read().takeIf { it>=0 } ?: throw IOException("GENERAL control refused")
    fun expect(n: Int) { if(byte()!=n) throw IOException("GENERAL control refused") }
    output.write(byteArrayOf(5,1,2));output.flush();expect(5);expect(2)
    output.write(byteArrayOf(1,user.size.toByte()));output.write(user);output.write(pass.size);output.write(pass);output.flush();expect(1);expect(0)
    val domain=host.toByteArray(Charsets.US_ASCII)
    output.write(byteArrayOf(5,1,0,3,domain.size.toByte()));output.write(domain);output.write(byteArrayOf(1,-69));output.flush()
    expect(5);expect(0);expect(0)
    val n=when(byte()){1->4;4->16;3->byte().takeIf{it>0}?:throw IOException("GENERAL control refused");else->throw IOException("GENERAL control refused")}
    repeat(n+2){byte()}
}
