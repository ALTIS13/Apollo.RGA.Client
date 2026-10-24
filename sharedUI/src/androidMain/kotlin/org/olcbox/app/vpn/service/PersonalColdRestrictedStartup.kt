package org.olcbox.app.vpn.service

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.net.DirectProbeRound
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.net.RestrictedAssessment
import org.olcbox.app.net.RestrictedNetworkCandidate
import org.olcbox.app.net.RestrictedNetworkClassifier
import org.olcbox.app.net.SelectedPathEvidence
import org.olcbox.app.vpn.UpstreamTransport

/** Startup-local evidence reduction; neither admission nor Connected/full-TUN proof. */
internal class PersonalColdRestrictedStartup(
    private val isCurrent: suspend () -> Boolean,
    private val physicalRound: suspend () -> DirectProbeRound,
    private val selectedPath: suspend () -> SelectedPathEvidence,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val waitMs: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun assess(snapshot: PhysicalNetworkSnapshot): RestrictedNetworkCandidate? = withTimeoutOrNull(30_000L) {
        if (!snapshot.isProbeReady || snapshot.captive || snapshot.transport != UpstreamTransport.Cellular ||
            !isCurrent()) return@withTimeoutOrNull null
        val classifier = RestrictedNetworkClassifier()
        classifier.onNetwork(snapshot.generation, snapshot.networkId, snapshot.revision, true, false, clockMs())
        waitMs(5_000L)
        if (!isCurrent() || !classifier.begin(snapshot.generation, snapshot.networkId, snapshot.revision, clockMs()))
            return@withTimeoutOrNull null
        val first = physicalRound()
        if (!isCurrent() || !classifier.record(first)) return@withTimeoutOrNull null
        waitMs(3_000L)
        if (!isCurrent()) return@withTimeoutOrNull null
        val second = physicalRound()
        if (!isCurrent() || !classifier.record(second)) return@withTimeoutOrNull null
        val path = selectedPath()
        if (!isCurrent()) return@withTimeoutOrNull null
        if (classifier.finish(snapshot.generation, snapshot.networkId, snapshot.revision, clockMs(),
                path.selectedTransport, path.tunBridge) == RestrictedAssessment.RestrictedSuspect)
            RestrictedNetworkCandidate(snapshot.generation, snapshot.networkId, snapshot.revision)
        else null
    }
}
