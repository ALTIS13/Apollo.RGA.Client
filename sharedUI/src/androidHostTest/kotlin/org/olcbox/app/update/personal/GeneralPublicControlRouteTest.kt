package org.olcbox.app.update.personal

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.vpn.AndroidVpnManager
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.GeneralTunnelPhase
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.AndroidGeneralServiceConsumerTest
import org.olcbox.app.vpn.service.GeneralControlOperationTest
import org.olcbox.app.vpn.service.GeneralServiceResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Caller regression: GENERAL must never translate missing/retired capture into direct HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeneralPublicControlRouteTest {
    @Test fun sameLiveGeneralCaptureAllowsOnlyPublicProfileOta(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, GeneralControlOperationTest.controlOptions(f), f.ops))
        try {
            fixture { manager ->
                val capture = f.consumer.captureControl({ f.current }, { action -> if (!f.current) false else { action(); true } })!!
                OlcboxVpnState.generalControlCapture = capture
                val profile = OwnServerUpdateProfile.publicBinding("a".repeat(64))!!
                val route = manager.publicOwnServerUpdateFetchRoute(profile)
                assertTrue("actual public manager still blocks the same live GENERAL capture", route is PersonalUpdateFetchRoute.GeneralSelected)
                assertTrue(route.forProfile(profile)); assertTrue(route.current())
                assertFalse(route.forProfile(OwnServerUpdateProfile.Personal))
                var effects = 0
                assertTrue(route.authorize(profile.manifestUrl) { effects++ }); assertEquals(1,effects)
                assertFalse(route.authorize(PERSONAL_UPDATE_MANIFEST_URL) { effects++ })
                assertFalse(route.authorize("https://rga.apollot.ru/updates/public/v1/9/apollo-rga.apk") { effects++ })
                f.current = false
                assertFalse(route.authorize(profile.manifestUrl) { effects++ }); assertEquals(1,effects)
            }
        } finally { f.consumer.retire()?.let { assertTrue(f.consumer.stopCaptured(it,500)) } }
    }
    @Test fun generalMissingCaptureCannotUseNullProxyDefaultHttp(): Unit = runBlocking {
        fixture { manager ->
            var directReads = 0
            val direct = HttpClient(MockEngine { directReads++; respond("[]") })
            val source = object : LocationsDataSource {
                var bundle: LocationBundleV4? = null
                override suspend fun loadLocationBundle() = bundle
                override suspend fun saveLocationBundle(bundle: LocationBundleV4) { this.bundle = bundle }
                override suspend fun loadLegacyLocations() = emptyList<Pair<String, String>>()
                override suspend fun loadLegacyActiveLocationId(): String? = null
            }
            try {
                val repo = LocationsRepositoryImpl(source, direct, partnerLinkResolver = null)
                repo.importText("https://rga.apollot.ru/sub/owned-synthetic", manager.subscriptionFetchProxy())
                assertEquals("GENERAL null capture escaped into the actual default HTTP client", 0, directReads)
            } finally { direct.close() }
        }
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
    private suspend fun fixture(test: suspend (AndroidVpnManager) -> Unit) {
        val owner = Owner(RuntimeEnvironment.getApplication())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        OlcboxVpnState.setStatus(VpnStatus.Reconnecting)
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.TransportVerified)
        val manager = AndroidVpnManager(owner)
        try { test(manager) } finally {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            (manager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope).cancel()
            OlcboxVpnState.setStatus(VpnStatus.Disconnected)
        }
    }
}
