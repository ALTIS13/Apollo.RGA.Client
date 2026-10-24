@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.olcbox.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState

/** Separate finite physical QA; uses real production admission and independent UID UDP. */
@RunWith(AndroidJUnit4::class)
class PersonalManagedUdpAcceptanceTest {
    private data class HoldSnapshot(
        val stage: String,
        val tick: Int,
        val elapsedMs: Long,
        val ticketSame: Boolean,
        val originalFenceCurrent: Boolean,
        val currentTicketPresent: Boolean,
        val currentFenceCurrent: Boolean,
        val phase: PersonalTunnelPhase,
        val activePath: PersonalActivePath,
        val status: String,
        val cellular: Boolean,
        val wifi: Boolean
    ) {
        fun postEchoCurrent(): Boolean = originalFenceCurrent && currentTicketPresent &&
            currentFenceCurrent &&
            phase == PersonalTunnelPhase.Working && activePath == PersonalActivePath.OlcRtc &&
            status == "Connected"

        fun boundedHoldCurrent(): Boolean = postEchoCurrent() && cellular && !wifi

        fun safeJson(): JSONObject = JSONObject()
            .put("stage", stage)
            .put("tick", tick)
            .put("elapsed_ms", elapsedMs)
            .put("ticket_same", ticketSame)
            .put("original_fence_current", originalFenceCurrent)
            .put("current_ticket_present", currentTicketPresent)
            .put("current_fence_current", currentFenceCurrent)
            .put("phase", phase.name)
            .put("active_path", activePath.name)
            .put("status", status)
            .put("cellular", cellular)
            .put("wifi", wifi)
    }

    @Test(timeout = 360_000L)
    fun automaticPersonalPathThenIndependentUidUdpEchoAndStop() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val holdDiagnosticFile = File(context.filesDir, "managed-udp-hold-diagnostic-v2.json")
        assertTrue("Stale hold diagnostic removal failed",
            !holdDiagnosticFile.exists() || holdDiagnosticFile.delete())
        assertTrue("Exact personal package required", context.packageName == "tech.gatealtas.rga.personal")
        assertTrue("Target singleton mismatch", OlcboxVpnState::class.java ===
            context.classLoader.loadClass(OlcboxVpnState::class.java.name))
        assertTrue("Host network control required", InstrumentationRegistry.getArguments()
            .getString("externalNetworkControl") == "true")
        val portArgument = InstrumentationRegistry.getArguments().getString("managedUdpPort") ?: "18446"
        assertTrue("Exact managed UDP port required", portArgument == "18446" || portArgument == "443")
        val managedUdpPort = portArgument.toInt()
        val holdArgument = InstrumentationRegistry.getArguments().getString("managedUdpHoldSeconds") ?: "0"
        assertTrue("Exact managed UDP hold required", holdArgument == "0" || holdArgument == "60")
        val holdSeconds = holdArgument.toInt()
        assertTrue("Existing VPN consent required", VpnService.prepare(context) == null)
        assertTrue("Physical Wi-Fi prestate required", hasPhysicalTransport(context, NetworkCapabilities.TRANSPORT_WIFI))
        val preferences = context.getSharedPreferences("olcrtc_personal_join_installation_v1", Context.MODE_PRIVATE)
        val identity = preferences.getString("installation_id", null)
        val fingerprint = preferences.getString("public_key_sha256", null)
        assertTrue("Existing identity required", identity != null && fingerprint != null)
        var started = false
        try {
            if (OlcboxVpnState.status.value !is VpnStatus.Disconnected) {
                control(context, DebugVpnControlReceiver.ACTION_DEBUG_STOP_VPN)
                await(30_000) { OlcboxVpnState.status.value is VpnStatus.Disconnected }
            }
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, AppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            started = true
            control(context, DebugVpnControlReceiver.ACTION_DEBUG_START_VPN)
            await(60_000) { OlcboxVpnState.status.value is VpnStatus.Connected &&
                OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive }
            marker("RGA_QA_READY_FOR_CELLULAR")
            await(90_000) { OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working &&
                OlcboxVpnState.personalTunnel.value.activePath == PersonalActivePath.OlcRtc &&
                OlcboxVpnState.status.value is VpnStatus.Connected &&
                hasPhysicalTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !hasPhysicalTransport(context, NetworkCapabilities.TRANSPORT_WIFI) }
            val ticket = OlcboxVpnState.channelProxy
            val diagnosticStartedAt = SystemClock.elapsedRealtime()
            val holdSnapshots = ArrayList<HoldSnapshot>(62)
            fun captureSnapshot(stage: String, tick: Int): HoldSnapshot {
                val currentTicket = OlcboxVpnState.channelProxy
                val tunnel = OlcboxVpnState.personalTunnel.value
                val vpnStatus = OlcboxVpnState.status.value
                val originalFenceCurrent = ticket?.fence?.current() == true
                return HoldSnapshot(
                    stage = stage,
                    tick = tick,
                    elapsedMs = SystemClock.elapsedRealtime() - diagnosticStartedAt,
                    ticketSame = ticket === currentTicket,
                    originalFenceCurrent = originalFenceCurrent,
                    currentTicketPresent = currentTicket != null,
                    currentFenceCurrent = if (ticket === currentTicket) originalFenceCurrent
                        else currentTicket?.fence?.current() == true,
                    phase = tunnel.phase,
                    activePath = tunnel.activePath,
                    status = when (vpnStatus) {
                        is VpnStatus.Connected -> "Connected"
                        is VpnStatus.Connecting -> "Connecting"
                        is VpnStatus.Disconnected -> "Disconnected"
                        is VpnStatus.Reconnecting -> "Reconnecting"
                        is VpnStatus.Stopping -> "Stopping"
                        is VpnStatus.Error -> "Error"
                        else -> "Other"
                    },
                    cellular = hasPhysicalTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR),
                    wifi = hasPhysicalTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
                )
            }
            fun saveHoldFailure(stage: String, tick: Int) {
                try {
                    val snapshots = JSONArray()
                    holdSnapshots.forEach { snapshots.put(it.safeJson()) }
                    val diagnostic = JSONObject()
                        .put("schema", "managed_udp_hold_diagnostic_v2")
                        .put("failed_stage", stage)
                        .put("failed_tick", tick)
                        .put("snapshots", snapshots)
                    context.openFileOutput(holdDiagnosticFile.name, Context.MODE_PRIVATE)
                        .use { it.write(diagnostic.toString().toByteArray(Charsets.UTF_8)) }
                    runCatching { marker("RGA_QA_MANAGED_UDP_HOLD_DIAGNOSTIC_SAVED") }
                } catch (_: Exception) {
                    runCatching { marker("RGA_QA_MANAGED_UDP_HOLD_DIAGNOSTIC_WRITE_FAILED") }
                }
            }
            val admittedSnapshot = captureSnapshot("admission", 0).also { holdSnapshots.add(it) }
            if (!admittedSnapshot.originalFenceCurrent) saveHoldFailure("admission", 0)
            assertTrue("Current admitted ticket required", admittedSnapshot.originalFenceCurrent)
            marker("RGA_QA_READY_FOR_MANAGED_UDP")
            // Host starts the finite NL receiver when this control marker appears.
            // Readiness is acknowledged with a private app-owned flag, not a route proof.
            val ready = File(context.filesDir, "managed-udp-receiver-ready-v2")
            await(12_000) { ready.isFile }
            assertTrue("Ready flag removal failed", ready.delete())
            val sample = PersonalManagedUdpProbeClient.sample(context, "139.28.240.24", 3, managedUdpPort)
            assertTrue("Independent UID managed UDP echo unavailable", sample != null &&
                sample.independentOf(Process.myUid()) && sample.matchesCount(3) &&
                sample.matchesTarget("139.28.240.24", managedUdpPort))
            val postEchoSnapshot = captureSnapshot("post_echo", 0).also { holdSnapshots.add(it) }
            if (!postEchoSnapshot.postEchoCurrent()) saveHoldFailure("post_echo", 0)
            assertTrue("Personal path changed during UDP proof", postEchoSnapshot.postEchoCurrent())
            assertTrue("Installation identity changed", preferences.getString("installation_id", null) == identity &&
                preferences.getString("public_key_sha256", null) == fingerprint)
            if (holdSeconds == 60) {
                marker("RGA_QA_MANAGED_UDP_HOLD_STARTED")
                repeat(60) { tick ->
                    delay(1_000)
                    val holdSnapshot = captureSnapshot("hold", tick + 1).also { holdSnapshots.add(it) }
                    if (!holdSnapshot.boundedHoldCurrent()) saveHoldFailure("hold", tick + 1)
                    assertTrue("Personal path changed during bounded hold", holdSnapshot.boundedHoldCurrent())
                }
                marker("RGA_QA_MANAGED_UDP_HOLD_PASS")
            }
            val actualHoldSnapshots = holdSnapshots.filter { it.stage == "hold" }
            val wrapperReplacementObserved = holdSeconds == 60 && holdSnapshots.any { !it.ticketSame }
            val ownerFencesContinuous = holdSeconds == 60 && actualHoldSnapshots.size == 60 &&
                actualHoldSnapshots.all { it.originalFenceCurrent && it.currentTicketPresent &&
                    it.currentFenceCurrent }
            val receipt = JSONObject().put("nonce_sha256", sample!!.nonceSha256())
                .put("echo_count", 3).put("independent_uid", true).put("current_path", true)
                .put("hold_seconds", holdSeconds)
                .put("wrapper_replacement_observed", wrapperReplacementObserved)
                .put("owner_fences_continuous", ownerFencesContinuous)
            context.openFileOutput("managed-udp-proof-v2.json", Context.MODE_PRIVATE)
                .use { it.write(receipt.toString().toByteArray()) }
            marker("RGA_QA_MANAGED_UDP_ECHO_PASS")
        } finally {
            File(context.filesDir, "managed-udp-receiver-ready-v2").delete()
            try {
                if (started) {
                    control(context, DebugVpnControlReceiver.ACTION_DEBUG_STOP_VPN)
                    await(35_000) { OlcboxVpnState.status.value is VpnStatus.Disconnected &&
                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive }
                }
            } finally {
                marker("RGA_QA_RESTORE_WIFI")
                await(25_000) { hasPhysicalTransport(context, NetworkCapabilities.TRANSPORT_WIFI) }
            }
        }
    }

    private suspend fun await(ms: Long, predicate: () -> Boolean) = withTimeout(ms) {
        while (!predicate()) delay(150)
    }

    private fun marker(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$value\n") })
    }

    private fun control(context: Context, action: String) {
        var error: Exception? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { DebugVpnControlReceiver().onReceive(context, Intent(action)) }
            catch (caught: Exception) { error = caught }
        }
        check(error == null) { "Personal debug control refused" }
    }

    private fun hasPhysicalTransport(context: Context, transport: Int): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.let { caps ->
            caps.hasTransport(transport) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true }
    }
}
