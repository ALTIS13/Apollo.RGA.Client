package org.olcbox.app.net

import android.net.Network
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.ProbeOutcome.Answered
import org.olcbox.app.net.ProbeOutcome.Failed
import org.olcbox.app.net.ProbeOutcome.Unknown
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RecordingNetworkShadow::class])
class BoundPhysicalNetworkProbeTest {
    private val network = ShadowNetwork.newInstance(17)

    @Before fun resetShadow() = ProbeOpenRecord.reset()

    @Test fun usesSameNetworkAndNoProxyForFourHttpsControls() = runBlocking {
        val connections = successfulConnections()
        ProbeOpenRecord.connections = connections

        val result = BoundPhysicalNetworkProbe(clockMs = { 8_000 }).collect(network, 1, 17, 2)

        assertEquals(setOf("www.gstatic.com", "www.cloudflare.com", "ya.ru", "telemost.yandex.ru"), ProbeOpenRecord.urls.map { it.host }.toSet())
        assertEquals(4, ProbeOpenRecord.urls.size)
        assertTrue(ProbeOpenRecord.proxies.all { it == Proxy.NO_PROXY })
        assertEquals(DirectProbeRound(1, 17, 2, 8_000, Answered, Answered, Answered), result)
        assertEquals(0, connections.values.sumOf { it.bodyReads.get() })
        assertEquals(4, connections.values.count { it.disconnected })
    }

    @Test fun rejectsRedirectChallengeAndTlsErrorAsUnknown() = runBlocking {
        val connections = successfulConnections().toMutableMap().apply {
            put("www.gstatic.com", FakeConnection("www.gstatic.com", 302))
            put("www.cloudflare.com", FakeConnection("www.cloudflare.com", 403))
            put("ya.ru", FakeConnection("ya.ru", failure = SSLException("untrusted certificate")))
            put("telemost.yandex.ru", FakeConnection("telemost.yandex.ru", failure = SSLException("untrusted certificate")))
        }
        val probe = BoundPhysicalNetworkProbe(opener = { supplied, url ->
            assertTrue(supplied === network)
            connections.getValue(url.host)
        }, clockMs = { 8_000 })

        val result = probe.collect(network, 1, 17, 2)

        assertEquals(Unknown, result.foreignA)
        assertEquals(Unknown, result.foreignB)
        assertEquals(Unknown, result.domestic)
        assertTrue(connections.values.all { it.disconnected && !it.instanceFollowRedirects })
        assertEquals(0, connections.values.sumOf { it.bodyReads.get() })
    }

    @Test fun verifiedDomesticRedirectCountsAsReachableWithoutFollowingIt() = runBlocking {
        val connections = successfulConnections().toMutableMap().apply {
            put("ya.ru", FakeConnection("ya.ru", 302))
            put("telemost.yandex.ru", FakeConnection("telemost.yandex.ru", failure = SSLException("untrusted certificate")))
        }
        val probe = BoundPhysicalNetworkProbe(opener = { _, url -> connections.getValue(url.host) }, clockMs = { 8_000 })

        val result = probe.collect(network, 1, 17, 2)

        assertEquals(Answered, result.domestic)
        assertTrue(connections.values.all { !it.instanceFollowRedirects })
    }

    @Test fun telemostRemainsPositiveControlWhenYaIsUnavailable() = runBlocking {
        val connections = successfulConnections().toMutableMap().apply {
            put("ya.ru", FakeConnection("ya.ru", failure = SocketTimeoutException("timeout")))
        }
        val probe = BoundPhysicalNetworkProbe(opener = { _, url -> connections.getValue(url.host) }, clockMs = { 8_000 })

        assertEquals(Answered, probe.collect(network, 1, 17, 2).domestic)
    }

    @Test fun timesOutAndClosesConnectionsWithoutReadingBodies() = runBlocking {
        val connections = successfulConnections().toMutableMap().apply {
            put("www.gstatic.com", FakeConnection("www.gstatic.com", failure = SocketTimeoutException("timeout")))
        }
        val probe = BoundPhysicalNetworkProbe(opener = { supplied, url ->
            assertTrue(supplied === network)
            connections.getValue(url.host)
        }, clockMs = { 8_000 })

        val result = probe.collect(network, 1, 17, 2)

        assertEquals(Failed, result.foreignA)
        assertEquals(Answered, result.foreignB)
        assertEquals(Answered, result.domestic)
        assertTrue(connections.values.all { it.disconnected })
        assertTrue(connections.values.all { it.connectTimeout in 1..4_000 && it.readTimeout in 1..4_000 })
        assertTrue(connections.values.all { !it.useCaches })
        assertEquals(0, connections.values.sumOf { it.bodyReads.get() })
    }

    @Test fun returnsGenerationAndRevisionUnchanged() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Network>())
        val connections = successfulConnections()
        val probe = BoundPhysicalNetworkProbe(opener = { supplied, url ->
            seen += supplied
            connections.getValue(url.host)
        }, clockMs = { 12_345 })

        val result = probe.collect(network, generation = 9, networkId = 31, revision = 7)

        assertEquals(DirectProbeRound(9, 31, 7, 12_345, Answered, Answered, Answered), result)
        assertEquals(4, seen.size)
        assertTrue(seen.all { it === network })
        assertFalse(connections.values.any { it.bodyReads.get() != 0 })
    }

    @Test fun globalDeadlineReturnsWithoutWaitingForBlockedForeignControls() = runBlocking {
        val connections = successfulConnections().toMutableMap().apply {
            put("www.gstatic.com", FakeConnection("www.gstatic.com", 204, delayMs = 5_000))
            put("www.cloudflare.com", FakeConnection("www.cloudflare.com", 200, delayMs = 5_000))
        }
        val probe = BoundPhysicalNetworkProbe(
            opener = { _, url -> connections.getValue(url.host) },
            clockMs = { 8_000 },
            deadlineMs = 500
        )

        val started = System.nanoTime()
        val result = probe.collect(network, 1, 17, 2)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMs < 2_000, "A blocked foreign control must not hold the caller")
        assertEquals(Answered, result.domestic)
        assertTrue(result.foreignA != Answered && result.foreignB != Answered)
        assertEquals(0, connections.values.sumOf { it.bodyReads.get() })
    }

    private fun successfulConnections() = mapOf(
        "www.gstatic.com" to FakeConnection("www.gstatic.com", 204),
        "www.cloudflare.com" to FakeConnection("www.cloudflare.com", 200),
        "ya.ru" to FakeConnection("ya.ru", 200),
        "telemost.yandex.ru" to FakeConnection("telemost.yandex.ru", 200)
    )
}

private class FakeConnection(
    host: String,
    private val status: Int = 200,
    private val failure: Exception? = null,
    private val delayMs: Long = 0
) : HttpURLConnection(URL("https://$host/")) {
    val bodyReads = AtomicInteger()
    @Volatile var disconnected = false

    override fun connect() = Unit
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode(): Int {
        if (delayMs > 0) Thread.sleep(delayMs)
        failure?.let { throw it }
        return status
    }
    override fun getInputStream(): InputStream {
        bodyReads.incrementAndGet()
        return ByteArrayInputStream(byteArrayOf())
    }
}

private object ProbeOpenRecord {
    val urls = Collections.synchronizedList(mutableListOf<URL>())
    val proxies = Collections.synchronizedList(mutableListOf<Proxy>())
    var connections: Map<String, FakeConnection> = emptyMap()

    fun reset() {
        urls.clear()
        proxies.clear()
        connections = emptyMap()
    }
}

@Implements(Network::class)
class RecordingNetworkShadow {
    @Implementation
    fun openConnection(url: URL, proxy: Proxy): URLConnection {
        ProbeOpenRecord.urls += url
        ProbeOpenRecord.proxies += proxy
        return ProbeOpenRecord.connections.getValue(url.host)
    }
}
