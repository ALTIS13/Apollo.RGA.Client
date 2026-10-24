package org.olcbox.app.update.personal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalUpdateTransportTest {
    @Test fun socksAuthenticationIsPerSocketAndConnectUsesRemoteRgaNameOnly() {
        val input = ByteArrayInputStream(byteArrayOf(5, 2, 1, 0, 5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
        val output = ByteArrayOutputStream()
        PersonalUpdateSocksHandshake.negotiate(input, output, PersonalUpdateSocksEndpoint("127.0.0.1", 1080, "u", "p"))
        val name = "rga.apollot.ru".toByteArray(Charsets.US_ASCII)
        val expected = byteArrayOf(5, 1, 2, 1, 1, 117, 1, 112, 5, 1, 0, 3, 14) + name + byteArrayOf(1, -69)
        assertArrayEquals(expected, output.toByteArray())
    }
    @Test fun unauthenticatedSocketDoesNotLeakOptionalCredentials() {
        val input = ByteArrayInputStream(byteArrayOf(5, 0, 5, 0, 0, 3, 1, 120, 0, 80))
        val output = ByteArrayOutputStream()
        PersonalUpdateSocksHandshake.negotiate(input, output, PersonalUpdateSocksEndpoint("127.0.0.1", 1080, "", "secret"))
        assertArrayEquals(byteArrayOf(5, 1, 0, 5, 1, 0, 3, 14) + "rga.apollot.ru".toByteArray() + byteArrayOf(1, -69), output.toByteArray())
    }
    @Test fun rejectedAuthenticationTruncatedOrMalformedFramingNeverConnects() {
        for (bytes in listOf(byteArrayOf(5, -1), byteArrayOf(5, 2, 1, 1), byteArrayOf(5),
            byteArrayOf(5, 2, 1, 0, 5, 1, 0, 1), byteArrayOf(5, 2, 1, 0, 5, 0, 1, 1))) {
            try { PersonalUpdateSocksHandshake.negotiate(ByteArrayInputStream(bytes), ByteArrayOutputStream(),
                PersonalUpdateSocksEndpoint("127.0.0.1", 1080, "u", "p")); fail("malformed SOCKS framing accepted") }
            catch (_: java.io.IOException) { }
        }
    }
    @Test fun ipv6ReplyIsFullyConsumedAndOverlongCredentialsRejected() {
        val bytes = byteArrayOf(5, 0, 5, 0, 0, 4) + ByteArray(16) + byteArrayOf(0, 80, 99)
        val input = ByteArrayInputStream(bytes)
        PersonalUpdateSocksHandshake.negotiate(input, ByteArrayOutputStream(), PersonalUpdateSocksEndpoint("127.0.0.1", 1080, null, null))
        assertEquals(99, input.read())
        try { PersonalUpdateSocksHandshake.negotiate(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
            PersonalUpdateSocksEndpoint("127.0.0.1", 1080, "u".repeat(256), "p")); fail("oversize credentials accepted") }
        catch (_: java.io.IOException) { }
    }
    @Test fun credentialsAreAbsentFromTransportRepresentation() {
        val endpoint = PersonalUpdateSocksEndpoint("127.0.0.1", 1080, "transport-user", "transport-secret")
        assertFalse(endpoint.toString().contains("transport-user")); assertFalse(endpoint.toString().contains("transport-secret"))
    }
    @Test fun clientKeepsPlatformTlsAndHasNoRedirectCookieOrRetryPolicy() {
        val route = PersonalUpdateFetchRoute.Selected(PersonalUpdateFetchTicket(PersonalUpdateSocksEndpoint("127.0.0.1", 1080, "u", "p"),
            { true }, { _, action -> action(); true }))
        val client = AndroidPersonalUpdateHttp().clientFor(route)
        try {
            assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
            assertSame(CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(OkHttpClient().hostnameVerifier, client.hostnameVerifier)
            assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun responseCancellationClosesBodyAndCancelsTheRealCallWithoutNetwork() = runBlocking {
        val client = OkHttpClient(); val request = Request.Builder().url(PERSONAL_UPDATE_MANIFEST_URL).build()
        val call = client.newCall(request); var closed = false
        val source = object : ForwardingSource(Buffer().writeUtf8("bytes")) { override fun close() { closed = true; super.close() } }.buffer()
        val body = object : ResponseBody() { override fun contentType(): MediaType? = null
            override fun contentLength() = 5L; override fun source(): BufferedSource = source }
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            AndroidPersonalUpdateHttp().wrapResponse(response, client, call, 8192)
            entered.complete(Unit); awaitCancellation()
        }
        withTimeout(5000) { entered.await() }; job.cancelAndJoin()
        assertTrue(call.isCanceled()); assertTrue(closed)
        client.dispatcher.executorService.shutdown()
    }
    @Test fun oversizedHttpHeadersOrDeclaredBodyAreRefusedBeforeBodyReading() = runBlocking {
        for (headers in listOf(false, true)) {
            val client = OkHttpClient(); val request = Request.Builder().url(PERSONAL_UPDATE_MANIFEST_URL).build()
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(if (headers) 1 else 8193).toResponseBody())
            if (headers) builder.header("X-Padding", "x".repeat(17000))
            try { AndroidPersonalUpdateHttp().wrapResponse(builder.build(), client, client.newCall(request), 8192)
                fail("oversized HTTP response accepted") } catch (_: java.io.IOException) { }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }
}
