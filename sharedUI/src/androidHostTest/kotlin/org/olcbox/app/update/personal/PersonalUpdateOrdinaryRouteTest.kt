package org.olcbox.app.update.personal

import android.content.Context
import android.content.ContextWrapper
import android.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LocationKind
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalUpdateOrdinaryRouteTest {
    @Test fun sameNetworkSignalTelemetryKeepsBorrowedOrdinaryAuthority() = fixture { manager, cm ->
        telemetryChangeKeepsAuthority(manager, cm) { changed ->
            changed.javaClass.getMethod("setSignalStrength", Int::class.javaPrimitiveType).invoke(changed, -53)
        }
    }
    @Test fun sameNetworkBandwidthTelemetryKeepsBorrowedOrdinaryAuthority() = fixture { manager, cm ->
        telemetryChangeKeepsAuthority(manager, cm) { changed ->
            changed.javaClass.getMethod("setLinkUpstreamBandwidthKbps", Int::class.javaPrimitiveType).invoke(changed, 12000)
            changed.javaClass.getMethod("setLinkDownstreamBandwidthKbps", Int::class.javaPrimitiveType).invoke(changed, 48000)
        }
    }
    @Test fun sameNetworkTransportTelemetryKeepsBorrowedOrdinaryAuthority() = fixture { manager, cm ->
        telemetryChangeKeepsAuthority(manager, cm) { changed ->
            changed.javaClass.getMethod("setTransportInfo", TransportInfo::class.java).invoke(changed, TestTransportInfo())
        }
    }
    private class TestTransportInfo : TransportInfo
    @Test @Config(sdk = [30]) fun legacyOrdinarySdkFailsClosedInsteadOfDroppingRoutingFacts() = fixture { manager, _ ->
        assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
    }
    @Test @Config(sdk = [31]) fun api31SignalTelemetryStillKeepsOrdinaryAuthority() = fixture { manager, cm ->
        telemetryChangeKeepsAuthority(manager, cm) { changed ->
            changed.javaClass.getMethod("setSignalStrength", Int::class.javaPrimitiveType).invoke(changed, -59)
        }
    }
    @Test fun transportChangeAndRestoreStillRetiresOrdinaryAuthority() = fixture { manager, cm ->
        semanticChangeAndRestoreRetires(manager, cm) { changed ->
            Shadows.shadowOf(changed).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
    @Test fun capabilityOutsidePublicEnumWhitelistStillRetiresOrdinaryAuthority() = fixture { manager, cm ->
        semanticChangeAndRestoreRetires(manager, cm) { changed ->
            // Framework OEM_PAID is a valid bit, absent from the public compile SDK enum.
            Shadows.shadowOf(changed).addCapability(22)
        }
    }
    @Test fun ownerUidChangeAndRestoreStillRetiresOrdinaryAuthority() = fixture { manager, cm ->
        semanticChangeAndRestoreRetires(manager, cm) { changed ->
            changed.javaClass.getMethod("setOwnerUid", Int::class.javaPrimitiveType).invoke(changed, 23456)
        }
    }
    @Test fun specifierChangeAndRestoreStillRetiresOrdinaryAuthority() = fixture { manager, cm ->
        semanticChangeAndRestoreRetires(manager, cm) { changed ->
            val specifier = TelephonyNetworkSpecifier.Builder().setSubscriptionId(7).build()
            changed.javaClass.getMethod("setNetworkSpecifier", NetworkSpecifier::class.java).invoke(changed, specifier)
        }
    }
    @Test fun subscriptionChangeAndRestoreStillRetiresOrdinaryAuthority() = fixture { manager, cm ->
        semanticChangeAndRestoreRetires(manager, cm) { changed ->
            changed.javaClass.getMethod("setSubscriptionIds", Set::class.java).invoke(changed, setOf(7))
        }
    }
    @Test fun enterpriseChangeAndRestoreStillRetiresOrdinaryAuthority() = fixture { manager, cm ->
        semanticChangeAndRestoreRetires(manager, cm) { changed ->
            changed.javaClass.getMethod("addEnterpriseId", Int::class.javaPrimitiveType).invoke(changed, 1)
        }
    }
    private fun semanticChangeAndRestoreRetires(manager: AndroidVpnManager, cm: ConnectivityManager,
        change: (NetworkCapabilities) -> Unit) {
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val network = cm.activeNetwork!!; val original = cm.getNetworkCapabilities(network)!!
        val changed = NetworkCapabilities(original); change(changed)
        assertNotEquals(original, changed)
        val callback = Shadows.shadowOf(cm).networkCallbacks.single()
        Shadows.shadowOf(cm).setNetworkCapabilities(network, changed); callback.onCapabilitiesChanged(network, changed)
        Shadows.shadowOf(cm).setNetworkCapabilities(network, original); callback.onCapabilitiesChanged(network, original)
        // No guard read sees the intermediate change; semantic event history must still deny.
        var performed = false
        assertFalse(route.authorize(PERSONAL_UPDATE_MANIFEST_URL) { performed = true }); assertFalse(performed)
    }
    private fun telemetryChangeKeepsAuthority(manager: AndroidVpnManager, cm: ConnectivityManager,
        change: (NetworkCapabilities) -> Unit) {
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val network = cm.activeNetwork!!
        val original = cm.getNetworkCapabilities(network)!!
        val changed = NetworkCapabilities(original)
        // Hidden framework setters are fixture-only: production may not rely on hidden APIs.
        change(changed)
        assertNotEquals("fixture must actually change full capabilities", original, changed)
        Shadows.shadowOf(cm).setNetworkCapabilities(network, changed)
        Shadows.shadowOf(cm).networkCallbacks.single().onCapabilitiesChanged(network, changed)
        assertTrue("telemetry alone must not retire current ordinary authority", route.current())
        var performed = false
        assertTrue(route.authorize(PERSONAL_UPDATE_MANIFEST_URL) { performed = true }); assertTrue(performed)
        assertTrue("fresh capture must agree with normalized callback facts", manager.personalUpdateFetchRoute().current())
    }
    @Test fun suspendedResponseDnsChangeAndRestoreWithoutGuardReadIsSessionChanged() = fixture { manager, cm ->
        suspendedRestore(manager, cm) { callback, network ->
            val original = cm.getLinkProperties(network)!!
            val changed = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)))) }
            Shadows.shadowOf(cm).setLinkProperties(network, changed); callback.onLinkPropertiesChanged(network, changed)
            Shadows.shadowOf(cm).setLinkProperties(network, original); callback.onLinkPropertiesChanged(network, original)
        }
    }
    @Test fun suspendedResponseCapabilitiesChangeAndRestoreWithoutGuardReadIsSessionChanged() = fixture { manager, cm ->
        suspendedRestore(manager, cm) { callback, network ->
            val original = cm.getNetworkCapabilities(network)!!
            val changed = NetworkCapabilities(original)
            Shadows.shadowOf(changed).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            Shadows.shadowOf(cm).setNetworkCapabilities(network, changed); callback.onCapabilitiesChanged(network, changed)
            Shadows.shadowOf(cm).setNetworkCapabilities(network, original); callback.onCapabilitiesChanged(network, original)
        }
    }
    @Test fun lostAndRestoredDefaultCannotAuthorizeWithoutIntermediateGuard() = fixture { manager, cm ->
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val network = cm.activeNetwork!!
        Shadows.shadowOf(cm).networkCallbacks.toList().forEach { callback ->
            callback.onLost(network); callback.onAvailable(network)
            callback.onCapabilitiesChanged(network, cm.getNetworkCapabilities(network)!!)
            callback.onLinkPropertiesChanged(network, cm.getLinkProperties(network)!!)
        }
        var performed = false
        assertFalse(route.authorize(PERSONAL_UPDATE_MANIFEST_URL) { performed = true }); assertFalse(performed)
    }
    @Test fun dnsChangeAndRestoreCannotAuthorizeWithoutIntermediateGuard() = fixture { manager, cm ->
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val network = cm.activeNetwork!!; val original = cm.getLinkProperties(network)!!
        val changed = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)))) }
        Shadows.shadowOf(cm).networkCallbacks.single().let { callback ->
            Shadows.shadowOf(cm).setLinkProperties(network, changed); callback.onLinkPropertiesChanged(network, changed)
            Shadows.shadowOf(cm).setLinkProperties(network, original); callback.onLinkPropertiesChanged(network, original)
        }
        var performed = false
        assertFalse(route.authorize(PERSONAL_UPDATE_MANIFEST_URL) { performed = true }); assertFalse(performed)
    }
    @Test fun sameValueInitialCallbacksDoNotRetireAndScopeCompletionUnregisters() = fixture { manager, cm ->
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val callbacks = Shadows.shadowOf(cm).networkCallbacks.toList()
        assertEquals(1, callbacks.size)
        val network = cm.activeNetwork!!
        callbacks.single().onAvailable(network)
        callbacks.single().onCapabilitiesChanged(network, cm.getNetworkCapabilities(network)!!)
        callbacks.single().onLinkPropertiesChanged(network, cm.getLinkProperties(network)!!)
        assertTrue(route.current())
        runBlocking { withTimeout(5000) { managerScope(manager).coroutineContext[Job]!!.cancelAndJoin() } }
        assertTrue(Shadows.shadowOf(cm).networkCallbacks.isEmpty())
        callbacks.single().onAvailable(network) // A callback already queued before unregister must not revive authority.
        assertFalse(route.current())
        assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
    }
    @Test fun activityOwnerDestructionUnregistersAndPermanentlyRetiresBorrowedRoute() = fixture { manager, cm ->
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val callbacks = Shadows.shadowOf(cm).networkCallbacks.toList(); assertEquals(1, callbacks.size)
        val owner = manager.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(manager) as TestContext
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertTrue(Shadows.shadowOf(cm).networkCallbacks.isEmpty())
        assertFalse(route.authorize(PERSONAL_UPDATE_MANIFEST_URL) { fail("destroyed owner ran update action") })
        callbacks.single().onAvailable(cm.activeNetwork!!)
        assertFalse(route.current()); assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
    }
    private fun suspendedRestore(manager: AndroidVpnManager, cm: ConnectivityManager,
        changeRestore: (ConnectivityManager.NetworkCallback, Network) -> Unit) = runBlocking {
        // Read-only reuse of the real-JCA/actual-APK controller fixture, with an actual manager route.
        val type = Class.forName("org.olcbox.app.update.personal.PersonalUpdateControllerTest\$Fixture")
        val fixture = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val route = manager.personalUpdateFetchRoute()
        type.getDeclaredMethod("setRoute", PersonalUpdateFetchRoute::class.java).apply { isAccessible = true }.invoke(fixture, route)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val before: suspend () -> Unit = { entered.complete(Unit); release.await() }
        type.declaredMethods.single { it.name == "setBeforeResponse" }.apply { isAccessible = true }.invoke(fixture, before)
        val controller = type.getDeclaredMethod("getController").apply { isAccessible = true }.invoke(fixture) as PersonalUpdateController
        val check = launch { controller.manualCheck() }
        withTimeout(5000) { entered.await() }
        // Neither current() nor authorize() runs between the two callback facts.
        Shadows.shadowOf(cm).networkCallbacks.toList().forEach { changeRestore(it, cm.activeNetwork!!) }
        release.complete(Unit); check.join()
        assertEquals(PersonalUpdateState.SessionChanged, controller.state.value)
        val store = type.getDeclaredMethod("getStore").apply { isAccessible = true }.invoke(fixture) as PersonalUpdateHighWaterStore
        assertEquals(PersonalHighWaterRead.Ready(PersonalUpdateHighWater(0, null)), store.read())
        assertTrue(controller.install(true) is PersonalInstallResult.Refused)
        assertEquals(0, type.getDeclaredMethod("getHandoffs").apply { isAccessible = true }.invoke(fixture))
    }
    private fun managerScope(manager: AndroidVpnManager) =
        manager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope
    private class TestContext(app: Context) : ContextWrapper(app), LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        override fun getPackageName() = "tech.gatealtas.rga.personal"
        override fun getApplicationContext(): Context = this
    }
    @Test fun inactiveOrdinaryNetworkIsCapturedButTransitionIsBlocked() = fixture { manager, cm ->
        val route = manager.personalUpdateFetchRoute()
        assertTrue(route is PersonalUpdateFetchRoute.Ordinary); assertTrue(route.current())
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
        assertFalse(route.current()); assertNotNull(cm)
    }
    @Test fun sameNetworkDnsRevisionPermanentlyRetiresBorrowedOrdinaryRoute() = fixture { manager, cm ->
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        val network = cm.activeNetwork!!; val original = cm.getLinkProperties(network)!!
        Shadows.shadowOf(cm).setLinkProperties(network, LinkProperties().apply {
            setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1)))) })
        assertFalse(route.current())
        Shadows.shadowOf(cm).setLinkProperties(network, original)
        assertFalse("retired ordinary route must never revive", route.current())
    }
    @Test fun modeChangedAndRestoredBetweenReadsStillRetiresOrdinaryRoute() = fixture { manager, _ ->
        val route = manager.personalUpdateFetchRoute(); assertTrue(route.current())
        manager.selectConnectionMode(AndroidConnectionMode.Proxy); manager.selectConnectionMode(AndroidConnectionMode.Tun)
        assertFalse(route.current())
    }
    @Test fun missingNetworkStoppingAndErrorNeverMeanOrdinaryDefaultFallback() = fixture { manager, cm ->
        OlcboxVpnState.setStatus(VpnStatus.Stopping)
        assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
        OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        Shadows.shadowOf(cm).setActiveNetworkInfo(null)
        assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
    }
    @Test fun inactiveButConnectedOlcRtcIsInconsistentAndNeverOrdinary() = fixture { manager, _ ->
        OlcboxVpnState.activeLocation = LocationConfig(kind = LocationKind.Olcrtc)
        OlcboxVpnState.setStatus(VpnStatus.Connected)
        assertTrue(manager.personalUpdateFetchRoute() is PersonalUpdateFetchRoute.Blocked)
    }
    private fun fixture(test: (AndroidVpnManager, ConnectivityManager) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val context = TestContext(app)
        context.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val network = cm.activeNetwork!!
        val capabilities = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(network, capabilities)
        shadow.setLinkProperties(network, LinkProperties().apply {
            setDnsServers(listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))) })
        OlcboxVpnState.channelProxy = null; OlcboxVpnState.personalUpdateTicket = null
        OlcboxVpnState.activeLocation = null; OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        val manager = AndroidVpnManager(context)
        try { test(manager, cm) } finally {
            if (context.lifecycle.currentState != Lifecycle.State.DESTROYED) context.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            managerScope(manager).cancel()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }
}
