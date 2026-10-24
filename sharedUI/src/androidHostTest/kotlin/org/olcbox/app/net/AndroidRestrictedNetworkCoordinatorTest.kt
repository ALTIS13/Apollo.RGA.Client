package org.olcbox.app.net

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.net.ProbeOutcome.Answered
import org.olcbox.app.net.ProbeOutcome.Failed
import org.olcbox.app.net.ProbeOutcome.Unknown
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class AndroidRestrictedNetworkCoordinatorTest {
    private class Fixture(generation: Long = 1) {
        val snapshots = AndroidPhysicalNetworkSnapshots(generation, PhysicalNetworkSnapshotLedger())
        val network: Network = ShadowNetwork.newInstance(generation.toInt() + 100)
        var nowMs = 0L
        var probeCalls = 0
        var selected = SelectedPathEvidence(Failed, Unknown)
        val candidates = mutableListOf<RestrictedNetworkCandidate>()
        var probe: suspend (Network, Long, Long, Long) -> DirectProbeRound = { _, gen, id, revision ->
            probeCalls++
            DirectProbeRound(gen, id, revision, nowMs, Failed, Failed, Answered)
        }

        fun snapshot(transport: Int = NetworkCapabilities.TRANSPORT_CELLULAR): PhysicalNetworkSnapshot {
            snapshots.onAvailable(network)
            val caps = ShadowNetworkCapabilities.newInstance().also {
                Shadows.shadowOf(it).addTransportType(transport)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            snapshots.onCapabilitiesChanged(network, caps)
            return snapshots.onLinkPropertiesChanged(network, LinkProperties().apply {
                setDnsServers(listOf(InetAddress.getByName("192.0.2.1")))
            })!!
        }

        fun coordinator(scope: CoroutineScope) = AndroidRestrictedNetworkCoordinator(
            snapshots = snapshots,
            scope = scope,
            probeRound = { network, gen, id, revision -> probe(network, gen, id, revision) },
            selectedPath = { selected },
            onCandidate = { candidates += it },
            clockMs = { nowMs },
            waitMs = { nowMs += it }
        )
    }

    @Test fun twoRestrictedRoundsAndFailedSelectedTransportEmitOnlyCandidate() = runBlocking {
        val fixture = Fixture()
        val snapshot = fixture.snapshot()
        val coordinator = fixture.coordinator(this)

        coordinator.onNetworkEvent(fixture.network, snapshot)?.join()

        assertEquals(2, fixture.probeCalls)
        assertEquals(listOf(RestrictedNetworkCandidate(1, snapshot.networkId, snapshot.revision)), fixture.candidates)
    }

    @Test fun wifiOrUnknownSelectedTransportCannotRequestAlternate() = runBlocking {
        val wifi = Fixture()
        val wifiSnapshot = wifi.snapshot(NetworkCapabilities.TRANSPORT_WIFI)
        assertNull(wifi.coordinator(this).onNetworkEvent(wifi.network, wifiSnapshot))
        assertEquals(0, wifi.probeCalls)

        val cellular = Fixture()
        cellular.selected = SelectedPathEvidence(Unknown, Unknown)
        cellular.coordinator(this).onNetworkEvent(cellular.network, cellular.snapshot())?.join()
        assertEquals(2, cellular.probeCalls)
        assertTrue(cellular.candidates.isEmpty())
    }

    @Test fun ordinaryAvailableAndLocalBridgeFailureAreNotRestrictions() = runBlocking {
        for (evidence in listOf(SelectedPathEvidence(Answered, Answered), SelectedPathEvidence(Answered, Failed))) {
            val fixture = Fixture()
            fixture.selected = evidence
            fixture.coordinator(this).onNetworkEvent(fixture.network, fixture.snapshot())?.join()
            assertTrue(fixture.candidates.isEmpty())
        }
    }

    @Test fun changedLinkRevisionInvalidatesAnInFlightProbe() = runBlocking {
        val fixture = Fixture()
        val snapshot = fixture.snapshot()
        val firstProbe = CompletableDeferred<Unit>()
        fixture.probe = { _, gen, id, revision ->
            fixture.probeCalls++
            firstProbe.complete(Unit)
            fixture.snapshots.onLinkPropertiesChanged(fixture.network, LinkProperties().apply {
                setDnsServers(listOf(InetAddress.getByName("192.0.2.2")))
            })
            DirectProbeRound(gen, id, revision, fixture.nowMs, Failed, Failed, Answered)
        }
        val coordinator = fixture.coordinator(this)

        coordinator.onNetworkEvent(fixture.network, snapshot)?.join()

        assertTrue(firstProbe.isCompleted)
        assertEquals(1, fixture.probeCalls)
        assertTrue(fixture.candidates.isEmpty())
    }

    @Test fun lostNetworkAndExplicitCancellationSuppressCandidate() = runBlocking {
        val lost = Fixture()
        val lostSnapshot = lost.snapshot()
        lost.probe = { _, gen, id, revision ->
            lost.probeCalls++
            lost.snapshots.onLost(lost.network)
            DirectProbeRound(gen, id, revision, lost.nowMs, Failed, Failed, Answered)
        }
        lost.coordinator(this).onNetworkEvent(lost.network, lostSnapshot)?.join()
        assertEquals(1, lost.probeCalls)
        assertTrue(lost.candidates.isEmpty())

        val cancelled = Fixture()
        val cancelledSnapshot = cancelled.snapshot()
        lateinit var coordinator: AndroidRestrictedNetworkCoordinator
        cancelled.probe = { _, gen, id, revision ->
            cancelled.probeCalls++
            coordinator.cancel()
            DirectProbeRound(gen, id, revision, cancelled.nowMs, Failed, Failed, Answered)
        }
        coordinator = cancelled.coordinator(this)
        coordinator.onNetworkEvent(cancelled.network, cancelledSnapshot)?.join()
        assertEquals(1, cancelled.probeCalls)
        assertTrue(cancelled.candidates.isEmpty())
    }

    @Test fun repeatedCallbackDuringAssessmentAndCooldownCannotDuplicateCandidate() = runBlocking {
        val fixture = Fixture()
        val snapshot = fixture.snapshot()
        val coordinator = fixture.coordinator(this)

        val active = coordinator.onNetworkEvent(fixture.network, snapshot)!!
        assertNull(coordinator.onNetworkEvent(fixture.network, snapshot))
        active.join()
        coordinator.onNetworkEvent(fixture.network, snapshot)?.join()

        assertEquals(2, fixture.probeCalls)
        assertEquals(1, fixture.candidates.size)
    }
}
