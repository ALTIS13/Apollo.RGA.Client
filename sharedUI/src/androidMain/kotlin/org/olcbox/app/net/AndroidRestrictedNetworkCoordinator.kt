package org.olcbox.app.net

import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.olcbox.app.vpn.UpstreamTransport

/** Evidence supplied by the selected ordinary transport, not by the direct physical probe. */
internal data class SelectedPathEvidence(
    val selectedTransport: ProbeOutcome,
    val tunBridge: ProbeOutcome
)

/** A request for the service to consider a test grant; never permission to switch or claim Connected. */
internal data class RestrictedNetworkCandidate(
    val generation: Long,
    val networkId: Long,
    val revision: Long
)

/**
 * Consumes ordered Android callback snapshots and physical-network probes. The service must
 * separately revalidate this candidate, obtain admission, and prove the complete TUN path.
 */
internal class AndroidRestrictedNetworkCoordinator(
    private val snapshots: AndroidPhysicalNetworkSnapshots,
    private val scope: CoroutineScope,
    private val probeRound: suspend (Network, Long, Long, Long) -> DirectProbeRound =
        BoundPhysicalNetworkProbe()::collect,
    private val selectedPath: suspend (Network) -> SelectedPathEvidence,
    private val onCandidate: (RestrictedNetworkCandidate) -> Unit,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
    private val classifier: RestrictedNetworkClassifier = RestrictedNetworkClassifier()
) {
    private var activeJob: Job? = null
    private var lastSnapshot: PhysicalNetworkSnapshot? = null

    /** Called for a callback snapshot, on the service's currently selected physical network. */
    @Synchronized
    fun onNetworkEvent(network: Network, snapshot: PhysicalNetworkSnapshot): Job? {
        if (snapshots.current(network) != snapshot || snapshots.networkFor(snapshot) != network) return null
        if (lastSnapshot == snapshot && activeJob?.isActive == true) return null

        activeJob?.cancel()
        lastSnapshot = snapshot
        classifier.onNetwork(
            snapshot.generation, snapshot.networkId, snapshot.revision,
            cellular = snapshot.transport == UpstreamTransport.Cellular && snapshot.isProbeReady,
            captive = snapshot.captive,
            nowMs = clockMs()
        )
        if (!snapshot.isProbeReady) return null

        return scope.launch { assess(network, snapshot) }.also { activeJob = it }
    }

    /** Cancels a pending assessment when VPN monitoring ends or the user disconnects. */
    @Synchronized
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        lastSnapshot?.let { classifier.cancel(it.generation) }
        lastSnapshot = null
    }

    private suspend fun assess(network: Network, snapshot: PhysicalNetworkSnapshot) {
        try {
            waitMs(SETTLE_MS)
            currentCoroutineContext().ensureActive()
            if (!beginIfCurrent(network, snapshot)) return

            val first = probeRound(network, snapshot.generation, snapshot.networkId, snapshot.revision)
            currentCoroutineContext().ensureActive()
            if (!recordIfCurrent(network, snapshot, first)) return

            waitMs(ROUND_SPACING_MS)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(network, snapshot)) return
            val second = probeRound(network, snapshot.generation, snapshot.networkId, snapshot.revision)
            currentCoroutineContext().ensureActive()
            if (!recordIfCurrent(network, snapshot, second)) return

            val path = selectedPath(network)
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                if (!isCurrentLocked(network, snapshot)) return@synchronized
                val assessment = classifier.finish(
                        snapshot.generation, snapshot.networkId, snapshot.revision,
                        clockMs(), path.selectedTransport, path.tunBridge
                    )
                // Only fixed enum labels: no network address, URL, token or payload.
                Log.d("RgaRestrictedProbe", "physical=${first.foreignA}/${first.foreignB}/${first.domestic}," +
                    "${second.foreignA}/${second.foreignB}/${second.domestic};" +
                    " selected=${path.selectedTransport},bridge=${path.tunBridge},result=$assessment")
                if (assessment == RestrictedAssessment.RestrictedSuspect) {
                    onCandidate(RestrictedNetworkCandidate(snapshot.generation, snapshot.networkId, snapshot.revision))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed or unknown probe is not restriction evidence.
        }
    }

    @Synchronized
    private fun beginIfCurrent(network: Network, snapshot: PhysicalNetworkSnapshot): Boolean =
        isCurrentLocked(network, snapshot) && classifier.begin(
            snapshot.generation, snapshot.networkId, snapshot.revision, clockMs()
        )

    @Synchronized
    private fun recordIfCurrent(network: Network, snapshot: PhysicalNetworkSnapshot, round: DirectProbeRound): Boolean =
        isCurrentLocked(network, snapshot) && classifier.record(round)

    @Synchronized
    private fun isCurrent(network: Network, snapshot: PhysicalNetworkSnapshot): Boolean =
        isCurrentLocked(network, snapshot)

    private fun isCurrentLocked(network: Network, snapshot: PhysicalNetworkSnapshot): Boolean =
        lastSnapshot == snapshot && snapshots.current(network) == snapshot &&
            snapshots.networkFor(snapshot) == network

    private companion object {
        const val SETTLE_MS = 5_000L
        const val ROUND_SPACING_MS = 3_000L
    }
}
