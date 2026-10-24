package org.olcbox.app.net

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
class OlcRtcRestrictedHandoffServiceTest {
    @Test fun nonPersonalPackageCannotEnterTheAlternateState() {
        val fixture = serviceOn(NetworkCapabilities.TRANSPORT_CELLULAR)
        val ordinary = LocationConfig(kind = LocationKind.Vless)
        try {
            setField(fixture.service, "runtimePolicy\$delegate", lazy { RuntimeLocationPolicy.ExperimentalDebug })
            setField(fixture.service, "connectedStorageId", "selected-ordinary")
            setField(fixture.service, "currentNetwork", fixture.network)
            OlcboxVpnState.activeLocation = ordinary
            OlcboxVpnState.setStatus(VpnStatus.Connected)

            assertFalse(review(fixture.service, fixture.snapshot))

            assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
            assertSame(ordinary, OlcboxVpnState.activeLocation)
            assertEquals("selected-ordinary", field(fixture.service, "connectedStorageId"))
            assertNull(field(fixture.service, "startupJob"))
            assertNull("notice cannot create a TUN-closing restart", field(fixture.service, "recoveryJob"))
            val manager = fixture.service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            updateNotification(fixture.service, "Reconnecting...")
            updateNotification(fixture.service, "VPN Connected")
            assertFalse(notificationText(manager).contains("Режим недоступен"))
        } finally {
            release(fixture.service)
        }
    }

    @Test fun staleOrNonCellularCandidateCannotChangeSessionStatus() {
        for (transport in listOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_WIFI)) {
            val fixture = serviceOn(transport)
            try {
                setField(fixture.service, "runtimePolicy\$delegate", lazy { RuntimeLocationPolicy.ExperimentalDebug })
                setField(fixture.service, "connectedStorageId", "selected-ordinary")
                setField(fixture.service, "currentNetwork", fixture.network)
                OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Vless)
                OlcboxVpnState.setStatus(VpnStatus.Connected)

                val candidate = RestrictedNetworkCandidate(
                    fixture.snapshot.generation,
                    fixture.snapshot.networkId,
                    fixture.snapshot.revision + 1
                )
                assertFalse(review(fixture.service, candidate))
                if (transport == NetworkCapabilities.TRANSPORT_WIFI) {
                    assertFalse(review(fixture.service, fixture.snapshot))
                }
                assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
            } finally {
                release(fixture.service)
            }
        }
    }

    @Test fun manualStopOrProxyModeCannotPublishAlternateNotice() {
        val fixture = serviceOn(NetworkCapabilities.TRANSPORT_CELLULAR)
        try {
            setField(fixture.service, "runtimePolicy\$delegate", lazy { RuntimeLocationPolicy.ExperimentalDebug })
            setField(fixture.service, "connectedStorageId", "selected-ordinary")
            setField(fixture.service, "currentNetwork", fixture.network)
            OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.setStatus(VpnStatus.Stopping)
            assertFalse(review(fixture.service, fixture.snapshot))

            OlcboxVpnState.setStatus(VpnStatus.Connected)
            setField(fixture.service, "connectionMode", AndroidConnectionMode.Proxy)
            assertFalse(review(fixture.service, fixture.snapshot))
            assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
        } finally {
            release(fixture.service)
        }
    }

    @Test fun verifiedOrdinaryPathPrefetchesTheSelectedLinkWithoutReplacingTheTunnel() {
        val fixture = serviceOn(NetworkCapabilities.TRANSPORT_WIFI)
        val url = "https://pan.gate-altas.tech/SelectedBearer_01"
        val calledWith = AtomicReference<String>()
        val called = CountDownLatch(1)
        try {
            setField(fixture.service, "currentNetwork", fixture.network)
            setField(fixture.service, "connectedPersonalSubscriptionUrl", url)
            val prefetcher: suspend (String) -> Boolean = { link ->
                calledWith.set(link)
                called.countDown()
                false
            }
            setField(fixture.service, "personalBootstrapPrefetcher", prefetcher)
            val ordinary = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.activeLocation = ordinary
            OlcboxVpnState.setStatus(VpnStatus.Connected)

            assertTrue(markVerified(fixture.service, fixture.network, ordinary))
            assertTrue("verified ordinary path did not schedule personal prefetch", called.await(3, TimeUnit.SECONDS))
            assertEquals(url, calledWith.get())
            assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
            assertSame(ordinary, OlcboxVpnState.activeLocation)
            assertNull(field(fixture.service, "startupJob"))
        } finally {
            release(fixture.service)
        }
    }

    @Test fun nonPersonalPackageCannotReadACachedRoomOrStartHandoff() {
        val fixture = serviceOn(NetworkCapabilities.TRANSPORT_CELLULAR)
        val url = "https://pan.gate-altas.tech/SelectedBearer_01"
        val readLink = AtomicReference<String>()
        val inspected = CountDownLatch(1)
        try {
            setField(fixture.service, "runtimePolicy\$delegate", lazy { RuntimeLocationPolicy.ExperimentalDebug })
            setField(fixture.service, "connectedStorageId", "selected-ordinary")
            setField(fixture.service, "connectedPersonalSubscriptionUrl", url)
            setField(fixture.service, "currentNetwork", fixture.network)
            val reader: (String) -> PersonalBootstrapV1? = { link ->
                readLink.set(link)
                inspected.countDown()
                PersonalBootstrapV1(1, "telemost", "vp8channel", "room-12345", "NL", "a".repeat(64),
                    System.currentTimeMillis() + 60_000L, 1)
            }
            setField(fixture.service, "personalBootstrapReader", reader)
            val ordinary = LocationConfig(kind = LocationKind.Vless)
            OlcboxVpnState.activeLocation = ordinary
            OlcboxVpnState.setStatus(VpnStatus.Connected)

            assertFalse(review(fixture.service, fixture.snapshot))
            assertFalse("non-personal package read a cached room", inspected.await(100, TimeUnit.MILLISECONDS))
            assertNull(readLink.get())
            assertSame(ordinary, OlcboxVpnState.activeLocation)
            assertEquals("selected-ordinary", field(fixture.service, "connectedStorageId"))
            assertNull(field(fixture.service, "startupJob"))
            assertNull(field(fixture.service, "recoveryJob"))
            assertEquals(VpnStatus.Connected, OlcboxVpnState.status.value)
        } finally {
            release(fixture.service)
        }
    }

    private data class Fixture(
        val service: OlcboxVpnService,
        val network: Network,
        val snapshot: PhysicalNetworkSnapshot
    )

    private fun serviceOn(transport: Int): Fixture {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        Shadows.shadowOf(service.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(service.packageName),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = service.packageName
                    name = "org.olcbox.app.AppActivity"
                }
            }
        )
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        setField(service, "connectivityManager", cm)
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            if (transport == NetworkCapabilities.TRANSPORT_CELLULAR) ConnectivityManager.TYPE_MOBILE
                else ConnectivityManager.TYPE_WIFI,
            0, true, true
        ))
        val network = cm.activeNetwork!!
        val caps = ShadowNetworkCapabilities.newInstance().also {
            Shadows.shadowOf(it).addTransportType(transport)
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadow.setNetworkCapabilities(network, caps)
        val link = LinkProperties().apply {
            setDnsServers(listOf(InetAddress.getByName("192.0.2.1")))
        }
        shadow.setLinkProperties(network, link)
        val snapshots = AndroidPhysicalNetworkSnapshots(0, PhysicalNetworkSnapshotLedger())
        snapshots.onAvailable(network)
        snapshots.onCapabilitiesChanged(network, caps)
        val snapshot = snapshots.onLinkPropertiesChanged(network, link)!!
        setField(service, "physicalNetworkSnapshots", snapshots)
        return Fixture(service, network, snapshot)
    }

    private fun review(service: OlcboxVpnService, snapshot: PhysicalNetworkSnapshot): Boolean =
        review(service, RestrictedNetworkCandidate(snapshot.generation, snapshot.networkId, snapshot.revision))

    private fun review(service: OlcboxVpnService, candidate: RestrictedNetworkCandidate): Boolean =
        OlcboxVpnService::class.java.getDeclaredMethod(
            "reviewRestrictedCandidate", RestrictedNetworkCandidate::class.java
        ).apply { isAccessible = true }.invoke(service, candidate) as Boolean

    private fun markVerified(service: OlcboxVpnService, network: Network, location: LocationConfig): Boolean =
        OlcboxVpnService::class.java.getDeclaredMethod(
            "markVerifiedOrdinaryNetwork", Network::class.java, LocationConfig::class.java
        ).apply { isAccessible = true }.invoke(service, network, location) as Boolean

    private fun setField(service: OlcboxVpnService, name: String, value: Any) {
        OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun field(service: OlcboxVpnService, name: String): Any? =
        OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun updateNotification(service: OlcboxVpnService, text: String) {
        OlcboxVpnService::class.java.getDeclaredMethod("updateNotification", String::class.java)
            .apply { isAccessible = true }.invoke(service, text)
    }

    private fun setServiceStatus(service: OlcboxVpnService, status: VpnStatus) {
        OlcboxVpnService::class.java.getDeclaredMethod("setStatus", VpnStatus::class.java)
            .apply { isAccessible = true }.invoke(service, status)
    }

    private fun notificationText(manager: NotificationManager): String =
        Shadows.shadowOf(manager).getNotification(100)
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun buildNotification(service: OlcboxVpnService, text: String): Notification =
        OlcboxVpnService::class.java.getDeclaredMethod("buildNotification", String::class.java)
            .apply { isAccessible = true }.invoke(service, text) as Notification

    private fun release(service: OlcboxVpnService) {
        service.onDestroy()
        OlcboxVpnState.activeLocation = null
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
    }
}
