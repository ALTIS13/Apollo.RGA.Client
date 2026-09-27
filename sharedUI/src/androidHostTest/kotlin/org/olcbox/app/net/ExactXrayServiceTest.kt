package org.olcbox.app.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.XrayJsonSubscriptionFixtures
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.service.OlcboxVpnService
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.coreChainAlive
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class ExactXrayServiceTest {
    private fun service() = Robolectric.buildService(OlcboxVpnService::class.java).get()
    private val location = LocationConfig(
        name = "Synthetic", kind = LocationKind.Vless,
        rawLink = "SECRET_RAW_LINK_MUST_NOT_BE_PARSED",
        xrayConfig = Json.parseToJsonElement(XrayJsonSubscriptionFixtures.vless).jsonObject
    )

    @Test fun chainedCoreIsUnhealthyWhenEitherRequiredProcessExits() {
        assertTrue(coreChainAlive(true, singBoxAlive = true, xrayAlive = true))
        assertFalse(coreChainAlive(true, singBoxAlive = true, xrayAlive = false))
        assertFalse(coreChainAlive(true, singBoxAlive = false, xrayAlive = true))
        assertTrue(coreChainAlive(false, singBoxAlive = true, xrayAlive = false))
        assertTrue(coreChainAlive(false, singBoxAlive = false, xrayAlive = true))
    }

    @Test fun realServiceUsesExactConfigBeforeRawLinkAndExplicitlyRejectsProxyMode() = runBlocking {
        val service = service()
        setField(service, "connectionMode", AndroidConnectionMode.Proxy)
        assertFalse(startCore(service))
        assertTrue(OlcboxVpnState.logs.value.last().contains("UNSUPPORTED_PROXY_MODE"))
        assertFalse(OlcboxVpnState.logs.value.last().contains("SECRET_RAW_LINK"))
    }

    @Test fun missingCoreStartupDoesNotLeakConfigOrExceptionToAppLogs() = runBlocking {
        val service = service()
        setField(service, "socksUsername", "local-user")
        setField(service, "socksPassword", "local-secret")
        assertFalse(startCore(service)) // Robolectric has no executable Android core.
        val log = OlcboxVpnState.logs.value.last()
        assertTrue(log.contains("Xray subscription transport failed"))
        for (secret in listOf("SYNTHETIC_PUBLIC_KEY", "local-secret", "SECRET_RAW_LINK", "config.json", "Cannot run program")) {
            assertFalse(log.contains(secret))
        }
    }

    @Test fun ruleBasedServiceRejectsAProviderDirectDomainBeforeStartingCores() = runBlocking {
        val service = service()
        setField(service, "socksUsername", "local-user")
        setField(service, "socksPassword", "local-secret")
        val unsafe = JsonObject(location.xrayConfig!! + ("routing" to Json.parseToJsonElement("""{"rules":[
            {"type":"field","domain":["domain:vk.cc"],"outboundTag":"direct"}
        ]}""")))
        val regional = Routing.Rules("/data/rules", DirectDns.Servers(listOf("192.0.2.53")), "ru")
        assertFalse(startCore(service, regional, location.copy(xrayConfig = unsafe)))
        val log = OlcboxVpnState.logs.value.last()
        assertTrue(log.contains("incompatible with local policy"))
        for (secret in listOf("SYNTHETIC_PUBLIC_KEY", "local-secret", "SECRET_RAW_LINK")) {
            assertFalse(log.contains(secret))
        }
    }

    @Test fun hevConnectHostMatchesFixedExactXrayLoopbackDespiteProxyPreference() {
        val service = service()
        setField(service, "socksListenHost", "::1")
        OlcboxVpnState.activeLocation = location
        try {
            val method = OlcboxVpnService::class.java.getDeclaredMethod("socksConnectHost").apply { isAccessible = true }
            assertEquals("127.0.0.1", method.invoke(service))
        } finally { OlcboxVpnState.activeLocation = null }
    }

    private fun setField(service: OlcboxVpnService, name: String, value: Any) {
        OlcboxVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private suspend fun startCore(
        service: OlcboxVpnService,
        routing: Routing = Routing.Global,
        active: LocationConfig = location
    ): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = OlcboxVpnService::class.java.getDeclaredMethod(
            "startCore", LocationConfig::class.java, Boolean::class.javaPrimitiveType,
            Routing::class.java, Boolean::class.javaPrimitiveType, Continuation::class.java
        ).apply { isAccessible = true }
        method.invoke(service, active, false, routing, false, continuation)
    }
}
