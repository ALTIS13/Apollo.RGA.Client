package org.olcbox.app.vpn.service

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.ParcelFileDescriptor
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchFence
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.net.AndroidPhysicalNetworkSnapshots
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.PersonalBootstrapV1
import org.olcbox.app.net.PhysicalNetworkSnapshotLedger
import org.olcbox.app.vpn.AndroidOlcRtcRuntime
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers

/** Exercises the actual Service-produced ticket, not a reimplemented predicate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalSubscriptionRefreshFenceTest {
    @Test fun publishedTicketAllowsOnlyExactSelectedSubscriptionUrl() = withFixture { f ->
        assertTrue(f.ticket.current())
        assertTrue(f.ticket.allowsUrl(URL))
        assertFalse(f.ticket.allowsUrl("$URL?other=1"))
        assertFalse(f.ticket.allowsUrl("$URL#other"))
        assertFalse(f.ticket.allowsUrl("https://other.invalid/sub/selected"))
    }

    @Test fun missingAttestedSnapshotPublishesBlockedTicket() = withFixture { f ->
        f.set("personalRefreshSnapshot", null)
        f.publish()
        val blocked = OlcboxVpnState.channelProxy!!.fence!!
        assertFalse(blocked.current())
        assertFalse(blocked.allowsUrl(URL))
        var persisted = 0
        assertFalse(blocked.commit?.invoke { persisted++ } ?: false)
        assertEquals(0, persisted)
    }

    @Test fun liveWorkingServiceCommitRunsPersistenceExactlyOnce() = withFixture { f ->
        var persisted = 0
        val commit = f.ticket.commit
        assertNotNull("Service must publish an atomic commit hook", commit)
        assertTrue(commit!!.invoke { persisted++ })
        assertEquals(1, persisted)
    }

    @Test fun stopBeforeBorrowedTicketCommitNeverRunsPersistence() = withFixture { f ->
        val commit = f.ticket.commit!!
        f.service.javaClass.getDeclaredMethod("setStatus", VpnStatus::class.java)
            .apply { isAccessible = true }.invoke(f.service, VpnStatus.Stopping)
        var persisted = 0
        assertFalse(commit.invoke { persisted++ })
        assertEquals(0, persisted)
    }

    @Test fun checkingInvalidatesAlreadyPublishedWorkingTicket() = withFixture { f ->
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        assertFalse(f.ticket.current())
    }

    @Test fun realServiceStoppingStatusClearsSnapshotAndInvalidatesBorrowedTicket() = withFixture { f ->
        f.service.javaClass.getDeclaredMethod("setStatus", VpnStatus::class.java)
            .apply { isAccessible = true }.invoke(f.service, VpnStatus.Stopping)
        assertNull(f.get("personalRefreshSnapshot"))
        assertFalse(f.ticket.current())
    }

    @Test fun replacementGenerationRejectsBorrowedTicket() = rejectedAfter { it.set("generation", 8L) }
    @Test fun replacementDescriptorRejectsBorrowedTicket() = rejectedAfter { it.set("vpnInterface", null) }
    @Test fun closedOriginalDescriptorRejectsBorrowedTicket() = rejectedAfter { it.pfd.close() }
    @Test fun replacementNetworkRejectsBorrowedTicket() = rejectedAfter { it.set("currentNetwork", null) }
    @Test fun changedPhysicalRevisionRejectsBorrowedTicket() = rejectedAfter { f ->
        f.snapshots.onLinkPropertiesChanged(f.network, LinkProperties().apply {
            setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))))
        })
    }
    @Test fun nativeTransportStoppedRejectsBorrowedTicket() = rejectedAfter { it.nativeRunning.set(false) }
    @Test fun nativeTransportEpochChangedRejectsBorrowedTicket() = rejectedAfter {
        (it.get("personalTransportEpoch") as AtomicLong).incrementAndGet()
    }
    @Test fun tunBridgeEpochChangedRejectsBorrowedTicket() = rejectedAfter {
        (it.get("personalBridgeEpoch") as AtomicLong).incrementAndGet()
    }
    @Test fun bridgeWorkerStoppedRejectsBorrowedTicket() = rejectedAfter {
        it.set("tun2socksStarted", false)
    }
    @Test fun replacementBootstrapSessionRejectsBorrowedTicket() = rejectedAfter {
        it.set("personalActiveBootstrap", bootstrap())
    }
    @Test fun selectedStorageChangedRejectsBorrowedTicket() = rejectedAfter {
        it.set("connectedStorageId", "replacement")
    }
    @Test fun selectedUrlChangedRejectsBorrowedTicket() = rejectedAfter {
        it.set("connectedPersonalSubscriptionUrl", "$URL?replacement=1")
    }

    @Test fun repeatedHealthProofEpochDoesNotInvalidateStableWorkingRefreshTicket() = withFixture { f ->
        (f.get("personalProofEpoch") as AtomicLong).incrementAndGet()
        assertTrue("session ticket must not expire merely because a health proof starts", f.ticket.current())
        assertTrue(f.ticket.allowsUrl(URL))
        var persisted = 0
        assertTrue(f.ticket.commit!!.invoke { persisted++ })
        assertEquals(1, persisted)
        // Invoke Kotlin's actual default-argument entry point: ordinary proof reads stay strict.
        assertFalse("ordinary proof guard must retain its default epoch fence", f.strictDefaultProofCurrent())
    }

    private fun rejectedAfter(mutate: (Fixture) -> Unit) = withFixture { f ->
        assertTrue("fixture must begin with a valid Service-produced ticket", f.ticket.current())
        mutate(f)
        assertFalse("borrowed ticket survived a changed session boundary", f.ticket.current())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        private val file = File.createTempFile("rga-refresh-fence", ".bin")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        val nativeRunning = AtomicBoolean(true)
        private val releaseBridge = CountDownLatch(1)
        private val bridge = thread(name = "rga-refresh-test-bridge", isDaemon = true) { releaseBridge.await() }
        val snapshots = AndroidPhysicalNetworkSnapshots(7, PhysicalNetworkSnapshotLedger())
        val network: android.net.Network
        private val snapshot: Any
        val ticket: SubscriptionFetchFence

        init {
            try {
            ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getPackageName() = "tech.gatealtas.rga.personal"
            })
            val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            set("connectivityManager", cm)
            val shadow = Shadows.shadowOf(cm)
            shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_MOBILE, 0, true, true))
            network = cm.activeNetwork!!
            val caps = ShadowNetworkCapabilities.newInstance().also {
                Shadows.shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            shadow.setNetworkCapabilities(network, caps)
            val link = LinkProperties().apply {
                setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))))
            }
            shadow.setLinkProperties(network, link)
            snapshots.onAvailable(network)
            snapshots.onCapabilitiesChanged(network, caps)
            val physical = snapshots.onLinkPropertiesChanged(network, link)!!
            val location = LocationConfig(kind = LocationKind.Olcrtc)
            val ordinary = LocationConfig(kind = LocationKind.Vless)
            val bootstrap = bootstrap()
            val stamp = PersonalTunProof.RuntimeStamp(7, physical.networkId, physical.revision, 11, 13, 17)
            set("generation", 7L)
            set("vpnInterface", pfd)
            set("physicalNetworkSnapshots", snapshots)
            set("currentNetwork", network)
            set("tun2socksThread", bridge)
            set("tun2socksStarted", true)
            set("tun2socksBackendPort", get("socksListenPort"))
            set("personalOrdinaryLocation", ordinary)
            set("personalActiveBootstrap", bootstrap)
            set("connectedStorageId", "selected")
            set("connectedPersonalSubscriptionUrl", URL)
            (get("personalProofEpoch") as AtomicLong).set(17)
            (get("personalTransportEpoch") as AtomicLong).set(11)
            (get("personalBridgeEpoch") as AtomicLong).set(13)
            val runtime = Proxy.newProxyInstance(AndroidOlcRtcRuntime::class.java.classLoader,
                arrayOf(AndroidOlcRtcRuntime::class.java)) { _, method, _ ->
                if (method.name == "isRunning") nativeRunning.get() else null
            } as AndroidOlcRtcRuntime
            set("olcrtc\$delegate", lazy { runtime })
            OlcboxVpnState.activeLocation = location
            OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
            OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
            val evidence = PersonalTunProof.validateExactFlow(stamp, stamp, 99,
                "10.0.88.88", "10.0.88.88", longArrayOf(99, 1, 1, 240, 360, 0x0A005858))!!
            check(OlcboxVpnState.publishPersonalWorking(OlcboxVpnState.personalPhaseEpoch(), evidence))
            OlcboxVpnState.setStatus(VpnStatus.Connected)
            val snapshotClass = service.javaClass.declaredClasses.single { it.simpleName == "PersonalProofSnapshot" }
            snapshot = snapshotClass.declaredConstructors.single { it.parameterCount == 11 }
                .apply { isAccessible = true }.newInstance(pfd, network, physical, bridge, location,
                    ordinary, bootstrap, "selected", URL, OlcboxVpnState.personalPhaseEpoch(), stamp)
            set("personalRefreshSnapshot", snapshot)
            check(strictDefaultProofCurrent()) { "refresh fixture is not an actual current proof snapshot" }
            publish()
            ticket = OlcboxVpnState.channelProxy!!.fence!!
            check(ticket.current()) { "Service did not publish a current refresh ticket" }
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        fun strictDefaultProofCurrent(): Boolean = service.javaClass.declaredMethods.single {
            it.name == "personalProofCurrentNow\$default"
        }.apply { isAccessible = true }.invoke(null, service, snapshot, false, 2, null) as Boolean

        fun publish() {
            service.javaClass.getDeclaredMethod("publishPersonalRefreshProxy", SubscriptionFetchProxy::class.java)
                .apply { isAccessible = true }.invoke(service, SubscriptionFetchProxy("127.0.0.1", 1080))
        }
        fun get(name: String): Any? = service.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(service)
        fun set(name: String, value: Any?) { service.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(service, value) }
        fun close() {
            (get("job") as Job).cancel()
            releaseBridge.countDown()
            bridge.join(1_000)
            runCatching { pfd.close() }
            file.delete()
            OlcboxVpnState.channelProxy = null
            OlcboxVpnState.activeLocation = null
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }

    companion object {
        private const val URL = "https://panel.invalid/sub/selected"
        private fun bootstrap() = PersonalBootstrapV1(1, "telemost", "vp8channel", "room-test-01", "NL",
            "a".repeat(64), System.currentTimeMillis() + 60_000L, 1)
    }
}
