package org.olcbox.app.net

import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.Proxy as NetProxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class AndroidGeneralBootstrapTransportTest {
    // Catches the real server400: MIME parameters are forbidden by GENERAL ingress.
    @Test fun bothBootstrapPostsEmitParameterFreeJsonWithoutChangingUtf8Bytes() {
        val bytes = "{\"note\":\"подписка\"}".toByteArray(Charsets.UTF_8)
        for (path in listOf("/v1/general/link/begin", "/v1/general/bootstrap")) {
            val fixture = Fixture()
            assertNotNull(AndroidGeneralBootstrapTransport(fixture.factory).post(path, bytes))
            val body = requireNotNull(fixture.request?.body)
            assertEquals("application/json", body.contentType().toString())
            val actual = Buffer().also(body::writeTo).readByteArray()
            assertArrayEquals(bytes, actual)
            assertEquals(bytes.size.toLong(), body.contentLength())
        }
    }

    @Test fun secureClientIsDormantDirectBoundFiniteAndKeepsPlatformTrust() {
        val sockets = SocketFactory.getDefault()
        var dnsCalls = 0
        val dns = Dns { dnsCalls++; listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        val client = AndroidGeneralBootstrapTransport.secureClient(sockets, dns)
        assertSame(sockets, client.socketFactory); assertSame(dns, client.dns)
        assertSame(NetProxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure); assertFalse(client.fastFallback)
        assertEquals(5000, client.callTimeoutMillis)
        val platformDefault = OkHttpClient()
        assertSame(platformDefault.hostnameVerifier, client.hostnameVerifier)
        assertEquals(platformDefault.x509TrustManager!!.acceptedIssuers.toList(), client.x509TrustManager!!.acceptedIssuers.toList())
        assertFalse(client.hostnameVerifier.verify("wrong.invalid", object : javax.net.ssl.SSLSession by FakeSession() {}))
        assertEquals(0, dnsCalls)
    }

    @Test fun onlyExactFixedOriginPostPathsAndCapsCreateCalls() {
        val f = Fixture()
        val transport = AndroidGeneralBootstrapTransport(f.factory)
        assertEquals(0, f.created)
        for (path in listOf("https://evil.invalid/v1/general/bootstrap", "/v1/general/bootstrap?x=1", "/v1/general/bootstrap#x",
            "/v1/personal/bootstrap", "//evil.invalid/v1/general/bootstrap", "/v1/general/bootstrap/")) assertNull(transport.post(path, "{}".toByteArray()))
        assertNull(transport.post("/v1/general/bootstrap", ByteArray(4097)))
        assertNull(transport.post("/v1/general/bootstrap", byteArrayOf()))
        assertEquals(0, f.created)
        for (path in listOf("/v1/general/link/begin", "/v1/general/bootstrap")) {
            assertNotNull(transport.post(path, "{}".toByteArray()))
            assertEquals("https", f.request!!.url.scheme); assertEquals("rga.apollot.ru", f.request!!.url.host)
            assertEquals(443, f.request!!.url.port); assertEquals(path, f.request!!.url.encodedPath)
            assertEquals("POST", f.request!!.method); assertEquals("no-store", f.request!!.header("Cache-Control"))
            assertEquals("application/json", f.request!!.header("Accept"))
            assertEquals(5_000_000_000L, f.timeout.timeoutNanos())
        }
        assertEquals(2, f.cancelled); assertEquals(2, f.closed)
        assertEquals("AndroidGeneralBootstrapTransport([redacted])", transport.toString())
    }

    @Test fun bodyHeaderRedirectAndOriginalEndFailuresCloseOnlyTheirOwnedCall() {
        for (mode in listOf("oversize", "declaredOversize", "type", "store", "redirect", "wrongOrigin", "late", "chunkLate", "exception", "cancel")) {
            val f = Fixture(mode)
            var tick = 0
            val transport = AndroidGeneralBootstrapTransport(f.factory) {
                if (mode == "late" && tick++ > 0) 5_000_000_000L else f.elapsed
            }
            if (mode == "cancel") {
                try { transport.post("/v1/general/bootstrap", "{}".toByteArray()); fail("Cancellation swallowed") }
                catch (_: CancellationException) { }
            } else assertNull(mode, transport.post("/v1/general/bootstrap", "{}".toByteArray()))
            assertEquals(mode, 1, f.created); assertEquals(mode, 1, f.cancelled)
            assertEquals(mode, if (mode in listOf("exception", "cancel")) 0 else 1, f.closed)
        }
    }

    private class Fixture(private val mode: String = "ok") {
        var created = 0; var cancelled = 0; var closed = 0
        var elapsed = 0L
        var request: Request? = null
        val timeout = Timeout()
        val factory = Call.Factory { input ->
            created++; request = input
            Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { proxy, method, _ ->
                when (method.name) {
                    "request" -> input
                    "timeout" -> timeout
                    "cancel" -> { cancelled++; null }
                    "isExecuted" -> true
                    "isCanceled" -> cancelled > 0
                    "clone" -> proxy
                    "tag", "addEventListener" -> null
                    "execute" -> {
                        if (mode == "exception") throw java.io.IOException("private detail")
                        if (mode == "cancel") throw CancellationException("private detail")
                        val bytes = if (mode == "oversize") ByteArray(8193) else "{}".toByteArray()
                        val body = object : ResponseBody() {
                            private val buffer = Buffer().write(bytes)
                            override fun contentType(): MediaType? = null
                            override fun contentLength(): Long = if (mode == "declaredOversize") 8193 else -1
                            private val counted = object : ForwardingSource(buffer) {
                                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                                    if (mode == "chunkLate") elapsed += 3_000_000_000L
                                }
                            }.buffer()
                            override fun source(): BufferedSource = counted
                            override fun close() { closed++; super.close() }
                        }
                        Response.Builder().request(if (mode == "wrongOrigin") input.newBuilder().url("https://wrong.invalid/").build() else input)
                            .protocol(Protocol.HTTP_1_1).code(if (mode == "redirect") 302 else 200).message("controlled")
                            .header("Content-Type", if (mode == "type") "text/plain" else "application/json")
                            .header("Cache-Control", if (mode == "store") "private" else "no-store").body(body).build()
                    }
                    else -> throw UnsupportedOperationException("Controlled call method")
                }
            } as Call
        }
    }

    // No certificate/session from a live peer. Rejection of a peer without a certificate is controlled evidence.
    private class FakeSession : javax.net.ssl.SSLSession {
        override fun getPeerCertificates(): Array<java.security.cert.Certificate> = throw javax.net.ssl.SSLPeerUnverifiedException("controlled")
        override fun getId() = byteArrayOf()
        override fun getSessionContext(): javax.net.ssl.SSLSessionContext? = null
        override fun getCreationTime() = 0L
        override fun getLastAccessedTime() = 0L
        override fun invalidate() {}
        override fun isValid() = false
        override fun putValue(name: String?, value: Any?) {}
        override fun getValue(name: String?): Any? = null
        override fun removeValue(name: String?) {}
        override fun getValueNames() = emptyArray<String>()
        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = throw javax.net.ssl.SSLPeerUnverifiedException("controlled")
        override fun getPeerPrincipal(): java.security.Principal = throw javax.net.ssl.SSLPeerUnverifiedException("controlled")
        override fun getLocalPrincipal(): java.security.Principal? = null
        override fun getCipherSuite() = "controlled"
        override fun getProtocol() = "controlled"
        override fun getPeerHost() = "controlled"
        override fun getPeerPort() = 443
        override fun getPacketBufferSize() = 0
        override fun getApplicationBufferSize() = 0
    }
}
