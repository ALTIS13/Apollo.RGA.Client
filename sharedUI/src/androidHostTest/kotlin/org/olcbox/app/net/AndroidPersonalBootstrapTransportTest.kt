package org.olcbox.app.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AndroidPersonalBootstrapTransportTest {
    private fun remote() = PersonalBootstrapEndpointPolicy.select(
        "tech.gatealtas.rga.personal", true, "https://rga.apollot.ru", ""
    )!!

    @Test fun remotePostsExactUrlWithoutRedirectCacheOrTlsTrustOverride() {
        val connection = ResponseConnection(URL("https://rga.apollot.ru/v1/personal/bootstrap"), 200)
        val originalTrust = connection.sslSocketFactory
        val originalHostnameVerifier = connection.hostnameVerifier
        var observedUrl: URL? = null
        val result = AndroidPersonalBootstrapTransport(remote()) { url -> observedUrl = url; connection }
            .post("/v1/personal/bootstrap", "{}".toByteArray())
        assertNotNull(result)
        assertEquals("https://rga.apollot.ru/v1/personal/bootstrap", observedUrl?.toExternalForm())
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertSame(originalTrust, connection.sslSocketFactory)
        assertSame(originalHostnameVerifier, connection.hostnameVerifier)
        assertEquals("no-store", connection.getRequestProperty("Cache-Control"))
        assertTrue(connection.disconnected)
    }

    @Test fun redirectsNeverYieldPayloadOrReadRedirectBody() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val connection = ResponseConnection(URL("https://rga.apollot.ru/v1/personal/bootstrap"), status)
            val result = AndroidPersonalBootstrapTransport(remote()) { connection }
                .post("/v1/personal/bootstrap", "{}".toByteArray())
            assertNull(result)
            assertFalse(connection.instanceFollowRedirects)
            assertEquals(0, connection.bodyReads)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun unapprovedPathAndInvalidRequestNeverOpenNetworkConnection() {
        var opens = 0
        val transport = AndroidPersonalBootstrapTransport(remote()) {
            opens++
            ResponseConnection(it, 200)
        }
        assertNull(transport.post("https://evil.invalid/bootstrap", "{}".toByteArray()))
        assertNull(transport.post("/v1/personal/bootstrap?room=attacker", "{}".toByteArray()))
        assertNull(transport.post("/v1/personal/bootstrap", byteArrayOf()))
        assertNull(transport.post("/v1/personal/bootstrap", ByteArray(4097)))
        assertEquals(0, opens)
    }

    private class ResponseConnection(url: URL, private val status: Int) : HttpsURLConnection(url) {
        var disconnected = false
        var bodyReads = 0
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getResponseCode() = status
        override fun getContentType() = "application/json"
        override fun getHeaderField(name: String?) = if (name == "Cache-Control") "no-store" else null
        override fun getInputStream(): ByteArrayInputStream {
            bodyReads++
            return ByteArrayInputStream("{}".toByteArray())
        }
    }
}
