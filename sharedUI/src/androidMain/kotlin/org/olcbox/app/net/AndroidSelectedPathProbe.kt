package org.olcbox.app.net

import kotlinx.coroutines.CancellationException
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.SubscriptionFetchProxy

/**
 * Probes the selected ordinary core's SOCKS path, never the direct physical path.
 * The service must establish that this core was started for the current Network.
 * Structural bridge readiness is not independent evidence that a foreign UID crossed TUN.
 */
internal class AndroidSelectedPathProbe(
    private val bulkCheck: suspend (SubscriptionFetchProxy) -> Boolean = ::checkBulk
) {
    suspend fun collect(
        selectedOnCurrentNetwork: Boolean,
        coreRunning: Boolean,
        bridgeReady: Boolean,
        proxy: SubscriptionFetchProxy?
    ): SelectedPathEvidence {
        if (!selectedOnCurrentNetwork || !coreRunning || proxy == null) {
            return SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Unknown)
        }
        if (!bridgeReady) return SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Failed)
        val transport = try {
            if (bulkCheck(proxy)) ProbeOutcome.Answered else ProbeOutcome.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProbeOutcome.Unknown
        }
        return SelectedPathEvidence(transport, ProbeOutcome.Unknown)
    }

    companion object {
        suspend fun checkBulk(proxy: SubscriptionFetchProxy): Boolean {
            val client = createProxyHttpClient(
                subscriptionProxy = proxy,
                connectTimeoutMs = TransportCheck.TIMEOUT_MS,
                requestTimeoutMs = TransportCheck.TIMEOUT_MS,
                socketTimeoutMs = TransportCheck.TIMEOUT_MS,
                followRedirects = false
            )
            return try {
                withProxyAuthentication(proxy) { TransportCheck.passes(client) }
            } finally {
                client.close()
            }
        }

        /** A rule-based front may send probe hosts DIRECT; only its raw backend tests the transport. */
        fun transportPort(frontPort: Int?, backendPort: Int?, hasRoutingFront: Boolean): Int? =
            (if (hasRoutingFront) backendPort else frontPort)?.takeIf { it in 1..65535 }
    }
}
