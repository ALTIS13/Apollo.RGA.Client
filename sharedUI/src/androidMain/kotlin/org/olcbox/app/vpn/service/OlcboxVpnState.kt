package org.olcbox.app.vpn.service

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.olcbox.app.util.nowMillis
import org.olcbox.app.vpn.TrafficCounters
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.PersonalTunnelUiState
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.OlcChannelRole
import org.olcbox.app.vpn.OlcConnectionProgress
import org.olcbox.app.vpn.OlcConnectionStage
import org.olcbox.app.log.LogScrubber

object OlcboxVpnState {
    @Volatile internal var generalControlCapture: GeneralControlCapture? = null
    @Volatile internal var personalUpdateTicket: org.olcbox.app.update.personal.PersonalUpdateFetchTicket? = null
    @Volatile var channelProxy: org.olcbox.app.data.repository.SubscriptionFetchProxy? = null
    @Volatile var channelProbe: org.olcbox.app.net.ChannelLatency.Session? = null
    @Volatile var activeLocation: org.olcbox.app.data.model.LocationConfig? = null

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _personalTunnel = MutableStateFlow(PersonalTunnelUiState())
    val personalTunnel = _personalTunnel.asStateFlow()
    private val _generalTunnel = MutableStateFlow(GeneralTunnelUiState())
    val generalTunnel = _generalTunnel.asStateFlow()
    private val _olcConnection = MutableStateFlow(OlcConnectionProgress())
    val olcConnection = _olcConnection.asStateFlow()

    /** Presentation of the fenced actual attempt; never an admission or connectivity grant. */
    @Synchronized internal fun setGeneralChannel(
        channel: OlcChannelRole,
        primaryAttemptFailed: Boolean = false
    ) {
        if (_olcConnection.value.stage == OlcConnectionStage.Denied) {
            _generalTunnel.value = GeneralTunnelUiState(GeneralTunnelPhase.Checking)
        }
        _olcConnection.value = OlcConnectionProgress(
            if (channel == OlcChannelRole.Reserve && primaryAttemptFailed)
                OlcConnectionStage.ConnectingReserve else OlcConnectionStage.Connecting,
            channel
        )
    }
    @Synchronized internal fun setGeneralPhase(phase: GeneralTunnelPhase) {
        // A confirmed denial owns this attempt; neither cleanup nor a late
        // positive callback can restore evidence. Only an explicit reset/new attempt can.
        if (_olcConnection.value.stage == OlcConnectionStage.Denied &&
            phase != GeneralTunnelPhase.Inactive
        ) return
        if (phase != GeneralTunnelPhase.TransportVerified) generalControlCapture = null
        _generalTunnel.value = GeneralTunnelUiState(phase)
        val current = _olcConnection.value
        _olcConnection.value = when (phase) {
            GeneralTunnelPhase.Inactive -> OlcConnectionProgress()
            GeneralTunnelPhase.Checking -> when {
                current.stage == OlcConnectionStage.ChannelReady -> current.copy(stage = OlcConnectionStage.Checking)
                current.active -> current
                else -> OlcConnectionProgress(OlcConnectionStage.Connecting)
            }
            // A completed channel check ends its busy presentation. This does
            // not change VpnStatus, admission, or the full-TUN qualification gate.
            GeneralTunnelPhase.TransportVerified -> current.copy(stage = OlcConnectionStage.ChannelReady)
            GeneralTunnelPhase.Denied -> current.copy(stage = OlcConnectionStage.Denied)
            GeneralTunnelPhase.Unavailable -> current.copy(stage = OlcConnectionStage.Unavailable)
            GeneralTunnelPhase.Stopping -> current.copy(stage = OlcConnectionStage.Stopping)
        }
    }
    /** Fenced live presentation observation only; never grants authority. */
    @Synchronized internal fun setGeneralChannelHealth(healthy: Boolean) {
        if (_generalTunnel.value.phase != GeneralTunnelPhase.TransportVerified) return
        val current = _olcConnection.value
        if (current.stage != OlcConnectionStage.ChannelReady && current.stage != OlcConnectionStage.Checking) return
        _olcConnection.value = current.copy(stage = if (healthy) OlcConnectionStage.ChannelReady
            else OlcConnectionStage.Checking)
    }

    private var phaseEpoch = 0L

    /** Service phase transitions only. The proof publisher is the sole Working transition. */
    @Synchronized
    fun setPersonalPhase(phase: PersonalTunnelPhase, activePath: PersonalActivePath) {
        require(phase != PersonalTunnelPhase.Working) { "Independent-UID full-TUN proof unavailable" }
        require(phase != PersonalTunnelPhase.Inactive || activePath == PersonalActivePath.Unknown)
        phaseEpoch++
        _personalTunnel.value = PersonalTunnelUiState(phase, activePath)
    }

    @Synchronized fun personalPhaseEpoch(): Long = phaseEpoch

    /** A visible Checking phase from a replacement handoff has a different epoch. */
    @Synchronized
    internal fun publishPersonalWorking(expectedPhaseEpoch: Long, evidence: PersonalTunProof.Validated): Boolean {
        if (expectedPhaseEpoch <= 0 || expectedPhaseEpoch != phaseEpoch ||
            evidence.observerEpoch <= 0 || evidence.stamp.proofEpoch <= 0 ||
            _status.value !is VpnStatus.Reconnecting ||
            _personalTunnel.value != PersonalTunnelUiState(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
        ) return false
        phaseEpoch++
        _personalTunnel.value = PersonalTunnelUiState(PersonalTunnelPhase.Working, PersonalActivePath.OlcRtc)
        return true
    }

    /**
     * Retired unbound marker. It carries neither a handoff/TUN identity nor an
     * independent-UID request and matching response-path proof. A late caller
     * could otherwise promote a replacement handoff with the same visible phase.
     * Keep the signature for compatibility, but never turn missing proof into Working.
     */
    @Deprecated("Unbound marker is not full-TUN proof; always rejected")
    fun markPersonalFullTunVerified(): Boolean = false

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince = _connectedSince.asStateFlow()

    private val _traffic = MutableStateFlow<TrafficCounters?>(null)
    val traffic = _traffic.asStateFlow()

    fun setTraffic(bytesIn: Long, bytesOut: Long) {
        _traffic.value = TrafficCounters(bytesIn = bytesIn, bytesOut = bytesOut)
    }

    @Synchronized
    fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
        when (status) {
            is VpnStatus.Disconnected -> setGeneralPhase(GeneralTunnelPhase.Inactive)
            is VpnStatus.Stopping -> if (_generalTunnel.value.phase != GeneralTunnelPhase.Inactive || _olcConnection.value.active) setGeneralPhase(GeneralTunnelPhase.Stopping)
            is VpnStatus.Error, is VpnStatus.Connecting -> if (_generalTunnel.value.phase != GeneralTunnelPhase.Inactive || _olcConnection.value.active) setGeneralPhase(GeneralTunnelPhase.Unavailable)
            else -> Unit
        }
        // A failed native stop reports Error and may still hold TUN: retain Stop,
        // but never leave a stale Working claim after a runtime error.
        when (status) {
            is VpnStatus.Disconnected -> setPersonalPhase(PersonalTunnelPhase.Inactive, PersonalActivePath.Unknown)
            is VpnStatus.Error -> if (_personalTunnel.value.phase != PersonalTunnelPhase.Inactive) {
                setPersonalPhase(PersonalTunnelPhase.Unavailable, _personalTunnel.value.activePath)
            }
            is VpnStatus.Reconnecting -> if (_personalTunnel.value.phase == PersonalTunnelPhase.Working) {
                setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
            }
            is VpnStatus.Stopping, is VpnStatus.Connecting ->
                if (_personalTunnel.value.phase == PersonalTunnelPhase.Working) {
                    setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Unknown)
                }
            else -> Unit
        }
        // A session's counters belong to that session; carrying them into the
        // next one would show yesterday's gigabytes on a fresh connection.
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) {
            _traffic.value = null
        }
        _connectedSince.value = when (status) {
            // Only the first Connected of a session stamps the clock; a
            // reconnect passes through Reconnecting and back, and must not
            // restart it.
            VpnStatus.Connected -> _connectedSince.value ?: nowMillis()
            VpnStatus.Reconnecting -> _connectedSince.value
            else -> null
        }
    }

    fun addLog(msg: String) {
        // Scrubbed once, at the top: logcat and a captured bug report are exports too.
        val safe = LogScrubber.default.scrub(msg)
        Log.d(TAG, safe)
        _logs.update { (it + safe).takeLast(MAX_LOG_ENTRIES) }
    }

    private const val MAX_LOG_ENTRIES = 1_000
    private const val TAG = "OlcboxVpnService"
}
