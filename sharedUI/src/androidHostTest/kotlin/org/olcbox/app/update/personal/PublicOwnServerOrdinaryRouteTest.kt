package org.olcbox.app.update.personal

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublicOwnServerOrdinaryRouteTest {
    private val profile = OwnServerUpdateProfile.publicBinding("a".repeat(64))!!
    @Test fun sameRealMonitorLostAndRestoredPermanentlyRetiresPublicCapture() = fixture { manager, cm ->
        val route = manager.publicOwnServerUpdateFetchRoute(profile)
        assertTrue(route is PersonalUpdateFetchRoute.Ordinary); assertTrue(route.current())
        val callback = Shadows.shadowOf(cm).networkCallbacks.single()
        val network = cm.activeNetwork!!; val caps = cm.getNetworkCapabilities(network)!!
        val properties = cm.getLinkProperties(network)!!
        callback.onLost(network); callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, caps); callback.onLinkPropertiesChanged(network, properties)
        var ran = false
        assertFalse(route.authorize(profile.manifestUrl) { ran = true }); assertFalse(ran)
        assertFalse(route.current())
    }
    @Test fun realDnsChangeAndRestoreHasNoIntermediateGuardButStillRetires() = fixture { manager, cm ->
        val route = manager.publicOwnServerUpdateFetchRoute(profile); assertTrue(route.current())
        val network = cm.activeNetwork!!; val original = cm.getLinkProperties(network)!!
        val changed = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)))) }
        val callback = Shadows.shadowOf(cm).networkCallbacks.single()
        Shadows.shadowOf(cm).setLinkProperties(network, changed); callback.onLinkPropertiesChanged(network, changed)
        Shadows.shadowOf(cm).setLinkProperties(network, original); callback.onLinkPropertiesChanged(network, original)
        assertFalse(route.authorize(profile.manifestUrl) { fail("retired capture ran action") })
    }
    @Test fun publicActiveOlcTransitionNeverReturnsSelectedOrOrdinaryFallback() = fixture { manager, _ ->
        val route = manager.publicOwnServerUpdateFetchRoute(profile); assertTrue(route.current())
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        assertTrue(manager.publicOwnServerUpdateFetchRoute(profile) is PersonalUpdateFetchRoute.Blocked)
        assertFalse(route.current())
    }
    @Test fun ownerDestroyUnregistersAndQueuedCallbackCannotRevive() = fixture { manager, cm ->
        val route = manager.publicOwnServerUpdateFetchRoute(profile); assertTrue(route.current())
        val callbacks = Shadows.shadowOf(cm).networkCallbacks.toList()
        val context = manager.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(manager) as Owner
        context.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertTrue(Shadows.shadowOf(cm).networkCallbacks.isEmpty())
        callbacks.single().onAvailable(cm.activeNetwork!!)
        assertFalse(route.authorize(profile.manifestUrl) { fail("destroyed owner ran action") })
    }
    @Test @Config(sdk = [30]) fun publicLegacySdkRemainsClosed() = fixture { manager, _ ->
        assertTrue(manager.publicOwnServerUpdateFetchRoute(profile) is PersonalUpdateFetchRoute.Blocked)
    }
    private class Owner(app: Context) : ContextWrapper(app), LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        override fun getPackageName() = "tech.gatealtas.rga"
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
            flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }
    private fun fixture(test: (AndroidVpnManager, ConnectivityManager) -> Unit) {
        val app = RuntimeEnvironment.getApplication(); val owner = Owner(app)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val network = cm.activeNetwork!!; val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(network, caps)
        shadow.setLinkProperties(network, LinkProperties().apply {
            setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))) })
        OlcboxVpnState.channelProxy = null; OlcboxVpnState.personalUpdateTicket = null
        OlcboxVpnState.activeLocation = null; OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        val manager = AndroidVpnManager(owner)
        try { test(manager, cm) } finally {
            if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            (manager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope).cancel()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }
}
