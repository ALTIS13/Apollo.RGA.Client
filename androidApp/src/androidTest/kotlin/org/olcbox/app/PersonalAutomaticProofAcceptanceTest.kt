@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Instrumentation only; production admission remains intact.

package org.olcbox.app

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.net.AndroidRuntimeLocationPolicy
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.net.SubscriptionTunnelAccess
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState
import org.olcbox.app.vpn.service.selectPersonalSubscriptionUrl

/** Real same-process state only; never unlocks the phone or manufactures proof/state. */
@RunWith(AndroidJUnit4::class)
class PersonalAutomaticProofAcceptanceTest {
    @Test(timeout = 300_000L)
    fun wifiOrdinaryToCellularAutomaticProofThenCleanStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val externalNetworkControl = InstrumentationRegistry.getArguments()
            .getString("externalNetworkControl") == "true"
        val ruDirectParity = InstrumentationRegistry.getArguments().getString("ruDirectParity") == "true"
        val subscriptionRefresh = InstrumentationRegistry.getArguments().getString("subscriptionRefresh") == "true"
        var stage = "preflight"
        var started = false
        var restoreWifi = false
        var failure: String? = null
        var ruSample: PersonalRuParityProbeClient.Sample? = null
        var ruNetworkHandle: Long? = null
        try {
            runBlocking {
                stage = "build policy"
                assertTrue("Personal package required", context.packageName == "tech.gatealtas.rga.personal")
                val policy = AndroidRuntimeLocationPolicy.forApp(context)
                assertTrue("Personal debug policy required", policy == RuntimeLocationPolicy.ExperimentalDebug)
                assertTrue("Exact remote personal build required", GeneratedAppInfo.PERSONAL_BRIDGE_REMOTE_ORIGIN ==
                    "https://rga.apollot.ru" && GeneratedAppInfo.PERSONAL_BRIDGE_CERT_SHA256.isEmpty())
                stage = "host-managed radio control mode"
                assertTrue("Host-managed network control required", externalNetworkControl)
                stage = "target production state singleton"
                assertTrue("Target production state singleton mismatch", OlcboxVpnState::class.java ===
                    instrumentation.targetContext.classLoader.loadClass(OlcboxVpnState::class.java.name))
                stage = "existing VPN permission"
                assertTrue("Existing VPN permission required", VpnService.prepare(context) == null)
                if (OlcboxVpnState.status.value !is VpnStatus.Disconnected) {
                    stage = "own VPN preflight Stop"
                    started = true
                    // Exact personal package, matching target singleton and existing consent were checked above.
                    debugControl(context, DebugVpnControlReceiver.ACTION_DEBUG_STOP_VPN)
                    await(45_000L, "Own VPN preflight Stop did not reach Disconnected/Inactive") {
                        OlcboxVpnState.status.value is VpnStatus.Disconnected &&
                            OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive
                    }
                }
                stage = "disconnected state"
                assertTrue("Disconnected prestate required", OlcboxVpnState.status.value is VpnStatus.Disconnected &&
                    OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive)
                // The host independently verifies/restores radio scalar 1; this gate checks the real network.
                stage = "Wi-Fi physical network prestate"
                assertTrue("Connected physical Wi-Fi prestate required", wifiConnected(context))
                restoreWifi = true

                stage = "stored subscription"
                val repository = LocationsRepositoryImpl(LocationsDataSourceImpl(context), runtimePolicy = policy)
                val before = repository.getBundle()
                val selected = before.activeLocationId
                val subscription = selectPersonalSubscriptionUrl(context.packageName, policy, AndroidConnectionMode.Tun,
                    before, selected)
                assertTrue("Stored selected subscription required", subscription != null)
                assertTrue("Stored subscription admission denied", SubscriptionTunnelAccess.denial(
                    before, selected, System.currentTimeMillis(), policy) == null)
                stage = "stored installation identity"
                val preferences = context.getSharedPreferences(IDENTITY_PREFERENCES, Context.MODE_PRIVATE)
                val identityId = preferences.getString("installation_id", null)
                val fingerprint = preferences.getString("public_key_sha256", null)
                assertTrue("Existing installation identity required", identityId != null &&
                    identityId.matches(Regex("[A-Za-z0-9_-]{16,128}")) && fingerprint != null)
                assertTrue("Existing Keystore binding required", currentFingerprint() == fingerprint)

                stage = "ordinary startup"
                started = true // Even a partially accepted start must be stopped in finally.
                debugControl(context, DebugVpnControlReceiver.ACTION_DEBUG_START_VPN)
                await(60_000L, "Ordinary startup did not reach Connected", rejectError = true) {
                    OlcboxVpnState.status.value is VpnStatus.Connected &&
                        OlcboxVpnState.activeLocation?.kind in setOf(LocationKind.Vless, LocationKind.Hysteria2) &&
                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive
                }

                stage = "automatic cellular proof"
                hostMarker(READY_FOR_CELLULAR) // Control signal only, never evidence of radio/state/proof.
                await(90_000L, "Automatic independent-UID proof did not reach Working", rejectError = true) {
                    val personal = OlcboxVpnState.personalTunnel.value
                    personal.phase == PersonalTunnelPhase.Working && personal.activePath == PersonalActivePath.OlcRtc &&
                        OlcboxVpnState.status.value is VpnStatus.Connected &&
                        OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc &&
                        physicalCellularAvailable(context) && !physicalWifiAvailable(context)
                }

                if (subscriptionRefresh) {
                    stage = "selected-path subscription refresh"
                    val handle = currentCellularHandle(context)
                    val ticket = OlcboxVpnState.channelProxy
                    val fence = ticket?.fence
                    assertTrue("Attested subscription refresh ticket required", handle != null &&
                        fence != null && fence.current() && fence.allowsUrl(subscription!!))
                    var proxyClients = 0
                    val refreshRepository = LocationsRepositoryImpl(
                        LocationsDataSourceImpl(context), runtimePolicy = policy,
                        proxyHttpClientFactory = { supplied ->
                            assertTrue("Selected-path refresh escaped its exact ticket", supplied === ticket &&
                                supplied.fence?.current() == true)
                            proxyClients++
                            createProxyHttpClient(supplied, followRedirects = false)
                        },
                    )
                    val report = withTimeout(15_000L) {
                        refreshRepository.refreshSubscription(subscription!!, ticket)
                    }
                    assertTrue("Selected-path subscription refresh failed", proxyClients > 0 &&
                        report.updatedCount > 0 && report.failures.isEmpty())
                    assertTrue("Subscription refresh changed the live selected path", currentCellularHandle(context) == handle &&
                        !physicalWifiAvailable(context) &&
                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working &&
                        OlcboxVpnState.status.value is VpnStatus.Connected &&
                        OlcboxVpnState.personalTunnel.value.activePath == PersonalActivePath.OlcRtc)
                    val refreshed = refreshRepository.getBundle()
                    assertTrue("Subscription refresh changed its stored identity", refreshed.activeLocationId == selected &&
                        selectPersonalSubscriptionUrl(context.packageName, policy, AndroidConnectionMode.Tun,
                            refreshed, selected) == subscription)
                    hostMarker(SUBSCRIPTION_REFRESH_PASSED)
                }

                if (ruDirectParity) {
                    stage = "same-cellular RU VPN sample"
                    val handle = currentCellularHandle(context)
                    assertTrue("Unique cellular network required for RU parity", handle != null)
                    val sample = PersonalRuParityProbeClient.sample(context, true, handle!!)
                    assertTrue("RU VPN sample unavailable or network changed", sample != null &&
                        currentCellularHandle(context) == handle && !physicalWifiAvailable(context) &&
                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working)
                    ruSample = sample
                    ruNetworkHandle = handle
                }

                stage = "selection and identity preservation"
                val after = repository.getBundle()
                assertTrue("Imported selection changed", after.activeLocationId == selected &&
                    selectPersonalSubscriptionUrl(context.packageName, policy, AndroidConnectionMode.Tun, after, selected) == subscription)
                assertTrue("Installation identity changed", preferences.getString("installation_id", null) == identityId &&
                    preferences.getString("public_key_sha256", null) == fingerprint && currentFingerprint() == fingerprint)
            }
        } catch (assertion: AssertionError) {
            val fixedReason = assertion.message?.takeIf { it in FIXED_ASSERTION_MESSAGES }
                ?: "Automatic proof acceptance failed at $stage"
            failure = "$fixedReason; ${runtimeSummary()}" // Capture the failure state before finally Stop.
        } catch (exception: Exception) {
            val fixedContext = if (Build.VERSION.SDK_INT >= 31 && exception is ForegroundServiceStartNotAllowedException) {
                "Background foreground-service start blocked; foreground the personal app before retry"
            } else "Automatic proof acceptance failed at $stage"
            val privateMessage = exception.message.orEmpty()
            val automationNotConnected = privateMessage.contains("UiAutomation not connected", ignoreCase = true) ||
                privateMessage.contains("UiAutomation is not connected", ignoreCase = true)
            val automationAlreadyConnected = privateMessage.contains("already connected", ignoreCase = true)
            failure = "$fixedContext; exceptionClass=${exception.javaClass.name}; " +
                "uiAutomationNotConnected=$automationNotConnected; uiAutomationAlreadyConnected=$automationAlreadyConnected; " +
                runtimeSummary()
        } finally {
            // Stop and restoration are independent: neither failure suppresses the other cleanup.
            if (started) {
                try {
                    debugControl(context, DebugVpnControlReceiver.ACTION_DEBUG_STOP_VPN)
                    runBlocking {
                        await(45_000L, "Stop did not reach Disconnected/Inactive") {
                            OlcboxVpnState.status.value is VpnStatus.Disconnected &&
                                OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive &&
                                OlcboxVpnState.personalTunnel.value.activePath == PersonalActivePath.Unknown
                        }
                    }
                    // Sample after Stop, before restoring Wi-Fi, on the exact same physical cellular network.
                    if (ruDirectParity && ruSample != null) {
                        val before = currentCellularHandle(context)
                        val baseline = PersonalRuParityProbeClient.sample(context, false, ruNetworkHandle!!)
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val active = manager?.activeNetwork
                        val activeCapabilities = active?.let { manager?.getNetworkCapabilities(it) }
                        val sameNetwork = before == ruNetworkHandle && currentCellularHandle(context) == ruNetworkHandle &&
                            active?.networkHandle == ruNetworkHandle && !physicalWifiAvailable(context) &&
                            activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
                        if (baseline == null || !sameNetwork || !ruSample!!.sameEgress(baseline)) {
                            failure = fixedFailure(failure, "Same-cellular RU direct parity failed or unavailable")
                        } else hostMarker(RU_PARITY_PASSED)
                    }
                } catch (_: Throwable) { failure = fixedFailure(failure, "Stop did not reach Disconnected/Inactive") }
            }
            if (restoreWifi) {
                try {
                    hostMarker(RESTORE_WIFI)
                    runBlocking {
                        await(30_000L, "Wi-Fi prestate restoration failed") {
                            wifiConnected(context)
                        }
                    }
                } catch (_: Throwable) { failure = fixedFailure(failure, "Wi-Fi prestate restoration failed") }
            }
        }
        failure?.let(::fail) // Fixed stage labels only; never include state Error text or exceptions.
    }

    private suspend fun await(timeoutMs: Long, message: String, rejectError: Boolean = false, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            if (rejectError && OlcboxVpnState.status.value is VpnStatus.Error) fail(message)
            if (SystemClock.elapsedRealtime() >= deadline) fail(message)
            delay(250L)
        }
    }

    private fun debugControl(context: Context, action: String) {
        var rejected: Exception? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { DebugVpnControlReceiver().onReceive(context, Intent(action)) }
            catch (exception: Exception) { rejected = exception }
        }
        rejected?.let { throw it } // Report sanitized failure on the test thread, never crash the main looper.
    }

    private fun hostMarker(marker: String) {
        check(marker == READY_FOR_CELLULAR || marker == RESTORE_WIFI || marker == RU_PARITY_PASSED ||
            marker == SUBSCRIPTION_REFRESH_PASSED)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\n$marker\n")
        })
    }

    private fun wifiConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun physicalCellularAvailable(context: Context): Boolean =
        physicalTransportAvailable(context, NetworkCapabilities.TRANSPORT_CELLULAR)

    private fun physicalWifiAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun physicalTransportAvailable(context: Context, transport: Int): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            capabilities != null && capabilities.hasTransport(transport) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }

    private fun currentCellularHandle(context: Context): Long? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.allNetworks.filter { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }.singleOrNull()?.networkHandle
    }

    private fun currentFingerprint(): String? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val publicDer = store.getCertificate(KEY_ALIAS)?.publicKey?.encoded ?: return null
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicDer),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun fixedFailure(previous: String?, cleanup: String): String =
        if (previous == null) cleanup else "$previous; $cleanup"

    private fun runtimeSummary(): String = synchronized(OlcboxVpnState) {
        val personal = OlcboxVpnState.personalTunnel.value
        "runtimeStatus=${OlcboxVpnState.status.value.javaClass.simpleName}; " +
            "personalPhase=${personal.phase.name}; activePath=${personal.activePath.name}; " +
            "activeKind=${OlcboxVpnState.activeLocation?.kind?.name ?: "null"}"
    }

    private companion object {
        const val IDENTITY_PREFERENCES = "olcrtc_personal_join_installation_v1"
        const val KEY_ALIAS = "org.olcbox.app.olcrtc_personal_join_p256_v1"
        const val READY_FOR_CELLULAR = "RGA_QA_READY_FOR_CELLULAR"
        const val RESTORE_WIFI = "RGA_QA_RESTORE_WIFI"
        const val RU_PARITY_PASSED = "RGA_QA_RU_DIRECT_PARITY_PASS"
        const val SUBSCRIPTION_REFRESH_PASSED = "RGA_QA_SUBSCRIPTION_REFRESH_PASS"
        val FIXED_ASSERTION_MESSAGES = setOf(
            "Personal package required", "Personal debug policy required", "Exact remote personal build required",
            "Host-managed network control required",
            "Target production state singleton mismatch", "Existing VPN permission required",
            "Own VPN preflight Stop did not reach Disconnected/Inactive", "Disconnected prestate required",
            "Connected physical Wi-Fi prestate required",
            "Stored selected subscription required", "Stored subscription admission denied",
            "Existing installation identity required", "Existing Keystore binding required",
            "Ordinary startup did not reach Connected", "Automatic independent-UID proof did not reach Working",
            "Unique cellular network required for RU parity", "RU VPN sample unavailable or network changed",
            "Attested subscription refresh ticket required", "Selected-path refresh escaped its exact ticket",
            "Selected-path subscription refresh failed", "Subscription refresh changed the live selected path",
            "Subscription refresh changed its stored identity",
            "Imported selection changed", "Installation identity changed"
        )
    }
}
