package org.olcbox.app.net

import android.app.Service
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ApolloVpnServiceConsentTest {
    @Test fun stopCommandDoesNotRequireApolloAcknowledgement() {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val result = service.onStartCommand(
            Intent(service, OlcboxVpnService::class.java).apply {
                action = OlcboxVpnService.ACTION_STOP_VPN
            }, 0, 1
        )
        assertEquals(Service.START_NOT_STICKY, result)
        service.onDestroy()
    }

    @Test fun serviceRejectsLegacyConsentBeforeLookingForActiveLocationOrStartingCore() {
        val service = Robolectric.buildService(OlcboxVpnService::class.java).get()
        val bundle = LocationBundleV4(vpnDisclosureAcceptedAt = 1_000L)
        val fake = Proxy.newProxyInstance(
            LocationsRepository::class.java.classLoader,
            arrayOf(LocationsRepository::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBundle" -> bundle
                "getChanges" -> MutableStateFlow(0L)
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as LocationsRepository
        OlcboxVpnService::class.java.getDeclaredField("repository\$delegate").apply {
            isAccessible = true
            set(service, lazy { fake })
        }
        OlcboxVpnService::class.java.getDeclaredMethod(
            "startTunnel", Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).apply {
            isAccessible = true
            invoke(service, true, false, false)
        }

        val deadline = System.currentTimeMillis() + 3_000L
        while (OlcboxVpnState.status.value !is VpnStatus.Error && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val status = OlcboxVpnState.status.value
        assertTrue(status is VpnStatus.Error)
        assertEquals(ApolloVpnDisclosureGate.NOTICE_REQUIRED, status.message)
        service.onDestroy()
    }
}
