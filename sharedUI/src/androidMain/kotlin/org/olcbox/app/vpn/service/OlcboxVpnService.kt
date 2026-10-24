package org.olcbox.app.vpn.service


import org.olcbox.app.net.toRules
import org.olcbox.app.data.model.RoutingSettings
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.blocked_no_location
import multiplatform_app.sharedui.generated.resources.notification_connecting
import multiplatform_app.sharedui.generated.resources.notification_connection_failed
import multiplatform_app.sharedui.generated.resources.notification_protecting
import multiplatform_app.sharedui.generated.resources.notification_proxy_connected
import multiplatform_app.sharedui.generated.resources.notification_reconnecting
import multiplatform_app.sharedui.generated.resources.notification_split_error
import multiplatform_app.sharedui.generated.resources.notification_stop
import multiplatform_app.sharedui.generated.resources.notification_title_proxy
import multiplatform_app.sharedui.generated.resources.notification_tunnel_error
import multiplatform_app.sharedui.generated.resources.notification_tunnel_failed
import multiplatform_app.sharedui.generated.resources.notification_verifying
import multiplatform_app.sharedui.generated.resources.notification_vpn_connected
import multiplatform_app.sharedui.generated.resources.notification_waiting_network
import multiplatform_app.sharedui.generated.resources.notification_waiting_transport
import org.jetbrains.compose.resources.getString as resourceString
import org.jetbrains.compose.resources.StringResource
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.RoutingMode
import org.olcbox.app.net.UpstreamDns
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.net.AndroidSingBoxController
import org.olcbox.app.net.AndroidPhysicalNetworkSnapshots
import org.olcbox.app.net.AndroidRestrictedNetworkCoordinator
import org.olcbox.app.net.AndroidSelectedPathProbe
import org.olcbox.app.net.AndroidPersonalBootstrapCache
import org.olcbox.app.net.AndroidGeneralBootstrapCache
import org.olcbox.app.net.GeneralBootstrapDiagnostic
import org.olcbox.app.net.observeGeneralBootstrap
import org.olcbox.app.net.AndroidGeneralBootstrapTransport
import org.olcbox.app.net.AndroidVerifiedGeneralBootstrapProducer
import org.olcbox.app.net.GeneralBootstrapSelection
import org.olcbox.app.net.AndroidVerifiedPersonalBootstrapProducer
import org.olcbox.app.net.PersonalBootstrapV1
import org.olcbox.app.net.AndroidKeystoreInstallationIdentity
import org.olcbox.app.net.PersonalJoinProofFactory
import org.olcbox.app.net.RemnawavePersonalBearer
import org.olcbox.app.net.AndroidXrayController
import org.olcbox.app.net.DirectDns
import org.olcbox.app.net.PhysicalNetworkSnapshot
import org.olcbox.app.net.PhysicalNetworkSnapshotLedger
import org.olcbox.app.net.ProbeOutcome
import org.olcbox.app.net.RestrictedNetworkCandidate
import org.olcbox.app.net.SelectedPathEvidence
import org.olcbox.app.net.BoundPhysicalNetworkProbe
import org.olcbox.app.net.ExactXrayChainPlan
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.OlcrtcDirectRules
import org.olcbox.app.net.AndroidRuntimeLocationPolicy
import org.olcbox.app.net.RuntimeLocationPolicy
import org.olcbox.app.net.OutboundSpec
import org.olcbox.app.net.Routing
import org.olcbox.app.net.RuleSets
import org.olcbox.app.net.SingBoxConfig
import org.olcbox.app.net.SocksLogin
import org.olcbox.app.net.TransportSpec
import org.olcbox.app.net.TunnelExit
import org.olcbox.app.net.TunnelVerifier
import org.olcbox.app.net.XrayConfig
import org.olcbox.app.net.XrayJsonRuntimeConfig
import org.olcbox.app.net.SubscriptionTunnelAccess
import org.olcbox.app.net.ApolloVpnDisclosureGate
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.HevTunnelConfig
import org.olcbox.app.vpn.OlcRtcUdpRelay
import org.olcbox.app.vpn.AndroidOlcRtcRuntimeProvider
import org.olcbox.app.vpn.AndroidGeneralRuntimeProvider
import org.olcbox.app.vpn.AndroidGeneralRuntimeOwner
import org.olcbox.app.vpn.AndroidGeneralRuntimeOptions
import org.olcbox.app.vpn.GeneralCleanupBudget
import org.olcbox.app.vpn.GeneralTransportHealth33
import org.olcbox.app.update.personal.PersonalUpdateDiagnosticRecords
import org.olcbox.app.update.personal.PersonalUpdateDiagnosticOperation
import org.olcbox.app.update.personal.PersonalUpdateDiagnosticFence
import org.olcbox.app.vpn.AndroidOlcRtcRuntime
import org.olcbox.app.vpn.UpstreamCandidate
import org.olcbox.app.vpn.UpstreamNetworkSelector
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.vpn.NetworkRefreshDecision
import org.olcbox.app.vpn.OrdinaryNetworkRefreshPolicy
import org.olcbox.app.vpn.PersonalActivePath
import org.olcbox.app.vpn.PersonalTunnelPhase
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import org.olcbox.app.log.LogScrubber

class OlcboxVpnService : VpnService() {

    private external fun prepareTun2socksNative(): Long
    private external fun prepareTunDnsScopeNative(): Long
    private external fun closeTunDnsScopeNative(scopeId: Long): Boolean
    private external fun releasePreparedTun2socksNative(epoch: Long): Boolean
    private external fun isTun2socksNativeRetired(epoch: Long): Boolean
    private external fun startTun2socksNative(configPath: String, fd: Int, epoch: Long, dnsScopeId: Long): Int
    private external fun stopTun2socksNative(epoch: Long): Boolean
    private external fun getTun2socksStatsNative(): LongArray
    private external fun armPersonalTunFlowNative(epoch: Long, remoteIPv4: Long, localPort: Int, remotePort: Int)
    private external fun clearPersonalTunFlowNative(epoch: Long)
    private external fun readPersonalTunFlowNative(epoch: Long): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val tunnelMutex = Mutex()
    private val runtimePolicy: RuntimeLocationPolicy by lazy {
        AndroidRuntimeLocationPolicy.forApp(applicationContext)
    }
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext), runtimePolicy = runtimePolicy)
    }
    private val deviceIdentityProvider by lazy {
        PersistentDeviceIdentityProvider(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var generalConsumer: AndroidGeneralServiceConsumer? = null
    private var generalProcessLease: AndroidGeneralProcessCustody.Lease? = null
    @Volatile private var generalLifecycleRetired = false
    private var generalBootstrapCache: AndroidGeneralBootstrapCache? = null
    private var generalReserveBootstrapCache: AndroidGeneralBootstrapCache? = null
    private var connectedGeneralSubscriptionUrl: String? = null
    private var generalPrefetchJob: Job? = null
    private var generalHandoffJob: Job? = null
    private var generalRecoveryJob: Job? = null
    private var generalActiveBinding: GeneralServiceBinding? = null
    private var generalOrdinaryLocation: LocationConfig? = null
    private var generalOptions: AndroidGeneralRuntimeOptions? = null
    private val generalBridgeEpoch = AtomicLong()
    private var watchdogJob: Job? = null
    private var connectedStorageId: String? = null
    private var connectedPersonalSubscriptionUrl: String? = null
    private var personalBootstrapPrefetchJob: Job? = null
    private var personalBootstrapInspectionJob: Job? = null
    private var personalHandoffJob: Job? = null
    private var personalReturnJob: Job? = null
    @Volatile private var personalProofJob: Job? = null
    @Volatile
    private var personalWorkingNetwork: Network? = null
    @Volatile
    private var personalWorkingSnapshot: PhysicalNetworkSnapshot? = null
    private var personalRefreshSnapshot: PersonalProofSnapshot? = null
    private val personalProofEpoch = AtomicLong()
    private val personalTransportEpoch = AtomicLong()
    private val personalBridgeEpoch = AtomicLong()
    private var personalOrdinaryLocation: LocationConfig? = null
    private var personalActiveBootstrap: PersonalBootstrapV1? = null
    private var lastPersonalPrefetchAtElapsedMs = 0L
    private var personalBootstrapPrefetcher: suspend (String) -> Boolean = { url ->
        val producer = AndroidVerifiedPersonalBootstrapProducer.forPersonalUsbTest(applicationContext)
        producer != null && AndroidPersonalBootstrapCache.forApp(applicationContext).prefetch(url, producer)
    }
    private var personalBootstrapReader: (String) -> PersonalBootstrapV1? = { url ->
        AndroidPersonalBootstrapCache.forApp(applicationContext).read(url)
    }
    private var coldStartupRestrictionAssessment: suspend (Long) -> Boolean = ::assessColdRestrictedStartup
    private var cleanupJob: Job? = null
    // An explicit Stop belongs to this Service lifetime. A background cleanup
    // cannot consume it, nor may a late Start borrow the retiring instance.
    @Volatile private var cleanupStopServiceRequested = false
    @Volatile private var stopCleanupPending = false
    private var networkLossJob: Job? = null
    private var recoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L
    private var watchdogTunStats: Tun2SocksStats? = null
    private var watchdogStalledSamples = 0
    private var lastWakeLockRefreshAtMs = 0L
    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0
    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L

    private var vpnInterface: ParcelFileDescriptor? = null
    private data class TunDnsScope(val descriptor: ParcelFileDescriptor, val id: Long)
    private var tunDnsScope: TunDnsScope? = null
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksBackendPort: Int? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false
    @Volatile
    private var tun2socksNativeEpoch = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private val physicalNetworkLedger = PhysicalNetworkSnapshotLedger()
    private var physicalNetworkSnapshots: AndroidPhysicalNetworkSnapshots? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val selectedPathProbe = AndroidSelectedPathProbe()
    private var restrictedNetworkCoordinator: AndroidRestrictedNetworkCoordinator? = null
    private var restrictedNoticeActive = false
    private var lastConnectedOrdinaryNetwork: Network? = null
    private var lastConnectedOrdinaryNetworkId: Long? = null
    private var lastConnectedOrdinaryTransport: UpstreamTransport? = null
    private var lastConnectedOrdinaryDns: List<String>? = null
    private data class OrdinaryRoutingFacts(
        val generation: Long,
        val network: Network,
        val networkId: Long?,
        val transport: UpstreamTransport?,
        val dnsServers: List<String>
    )
    private var ordinaryRoutingFacts: OrdinaryRoutingFacts? = null
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksListenHost = AndroidSocksProxySettings.DEFAULT_HOST
    private var socksListenPort = AndroidSocksProxySettings.DEFAULT_PORT
    private var socksUsername = ""
    private var socksPassword = ""
    private var splitTunnelMode = AndroidSplitTunnelMode.AllApps
    private var splitTunnelProxyApps = emptySet<String>()
    private var splitTunnelBypassApps = emptySet<String>()
    private var socksProxy: AuthenticatedSocksProxy? = null

    // Unified client: sing-box / Xray cores for vless/hy2/xhttp locations. Exec'd
    // subprocesses (this VpnService is a Context). The app's UID already bypasses
    // the tun (addDisallowedApp(self) in applySplitTunneling), so a core's own
    // sockets don't loop back through the tunnel. `activeCorePort` is the SOCKS
    // port the hev bridge targets when a core is active (null = olcrtc default),
    // and `activeCoreLogin` the login that port demands (null = none, proxy mode).
    private val singBoxCore by lazy { AndroidSingBoxController(this) }
    private val xrayCore by lazy { AndroidXrayController(this) }
    private var activeCorePort: Int? = null
    private var activeCoreBackendPort: Int? = null
    private var activeCoreLogin: SocksLogin? = null
    private var activeCoreChainRequiresBoth = false
    private val retiredCorePorts = mutableSetOf<Int>()

    /** The routing choice read at the last start, so a reconnect in place keeps it. */
    private var routingMode = RoutingMode.Global
    private var routingSettings = RoutingSettings()
    private var verboseDebugLogs = false

    // An ordinary installed app has no olcRTC binding. The optional debug
    // implementation is loaded only after the installed-app policy opts in.
    private val olcrtc by lazy { AndroidOlcRtcRuntimeProvider.create(applicationContext) }

    private data class StartOptions(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelMode: AndroidSplitTunnelMode,
        val splitTunnelProxyApps: Set<String>,
        val splitTunnelBypassApps: Set<String>
    )

    private data class Tun2SocksStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    private fun createNetworkCallback(
        registrationGeneration: Long,
        snapshots: AndroidPhysicalNetworkSnapshots
    ): ConnectivityManager.NetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            queueNetworkChange(network, snapshots.onAvailable(network), "Available")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            queueNetworkChange(network, snapshots.onCapabilitiesChanged(network, caps), "Capabilities")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            queueNetworkChange(network, snapshots.onLinkPropertiesChanged(network, linkProperties), "Link properties")
        }

        override fun onLost(network: Network) {
            val generalLoss = synchronized(this@OlcboxVpnService) {
                val binding = generalActiveBinding
                if (registrationGeneration != generation || stopCleanupPending || generalLifecycleRetired ||
                    OlcboxVpnState.status.value is VpnStatus.Stopping || network != currentNetwork ||
                    binding?.serviceGeneration != registrationGeneration || generalConsumer?.hasOwnedRuntime() != true
                ) null else generalConsumer?.retire()?.let { ticket ->
                    OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
                    ticket to binding.snapshot
                }
            }
            // Capture only the selected personal owner at callback receipt, before queued B metadata
            // can win tunnelMutex. This immutable capture grants nothing; outer/grace revalidate it.
            val personalLoss = synchronized(this@OlcboxVpnService) {
                val ordinary = personalOrdinaryLocation
                val alternate = OlcboxVpnState.activeLocation
                val bootstrap = personalActiveBootstrap
                val storageId = connectedStorageId
                val url = connectedPersonalSubscriptionUrl
                val pfd = vpnInterface
                val transportEpoch = personalTransportEpoch.get()
                val bridgeEpoch = personalBridgeEpoch.get()
                val acceptedSnapshot = personalRefreshSnapshot
                val workingSnapshot = personalWorkingSnapshot
                if (registrationGeneration != generation || network != currentNetwork || ordinary == null ||
                    alternate?.kind != LocationKind.Olcrtc || bootstrap == null || storageId == null || url == null ||
                    pfd == null || !pfd.fileDescriptor.valid() || packageName != PERSONAL_TEST_PACKAGE ||
                    runtimePolicy != RuntimeLocationPolicy.ExperimentalDebug || connectionMode != AndroidConnectionMode.Tun
                ) null else pfd to {
                    val currentAcceptedSnapshot = personalRefreshSnapshot
                    generation == registrationGeneration && !stopCleanupPending && vpnInterface === pfd &&
                        pfd.fileDescriptor.valid() && personalOrdinaryLocation === ordinary &&
                        OlcboxVpnState.activeLocation === alternate && personalActiveBootstrap === bootstrap &&
                        connectedStorageId == storageId && connectedPersonalSubscriptionUrl == url &&
                        personalTransportEpoch.get() == transportEpoch && personalBridgeEpoch.get() == bridgeEpoch &&
                        packageName == PERSONAL_TEST_PACKAGE && runtimePolicy == RuntimeLocationPolicy.ExperimentalDebug &&
                        connectionMode == AndroidConnectionMode.Tun &&
                        (OlcboxVpnState.status.value is VpnStatus.Connected ||
                            OlcboxVpnState.status.value is VpnStatus.Reconnecting) &&
                        // Old Working A must pass the outer guard so its lost receipt is demoted.
                        // A newly accepted receipt (including same-Network/new proof) retires this loss.
                        !(OlcboxVpnState.status.value is VpnStatus.Connected &&
                            OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working &&
                            (personalWorkingNetwork != network || personalWorkingSnapshot !== workingSnapshot ||
                                (currentAcceptedSnapshot != null && currentAcceptedSnapshot !== acceptedSnapshot)))
                }
            }
            val lostPersonalPfd = personalLoss?.first
            val ownsLostPersonalTransport = personalLoss?.second ?: { false }
            snapshots.onLost(network)
            if (generalLoss != null) {
                scheduleGeneralRecovery(network, generalLoss.second, generalLoss.first)
                return
            }
            scope.launch {
                tunnelMutex.withLock outer@ {
                    if (registrationGeneration != generation) return@outer
                    if (lostPersonalPfd != null) {
                        if (!ownsLostPersonalTransport()) return@outer
                    } else if (network != currentNetwork) return@outer
                    invalidateWorkingPersonalProofForNetwork(network, null)
                    networkLossJob?.cancel()
                    networkLossJob = scope.launch {
                        delay(NETWORK_LOSS_GRACE_MS)
                        tunnelMutex.withLock inner@ {
                            if (registrationGeneration != generation) return@inner
                            // Same-owner Cellular callbacks may move metadata to B during A's grace.
                            // New transport/bridge epochs or a fresh Working receipt retire this loss.
                            if (lostPersonalPfd != null) {
                                if (!ownsLostPersonalTransport()) return@inner
                            } else if (network != currentNetwork) return@inner
                            val upstream = findActiveUpstreamNetwork()
                            val fallback = upstream?.let(snapshots::current)
                            if (upstream != null && fallback != null && snapshots.networkFor(fallback) != null) {
                                if (denyConnectedSubscriptionIfNeeded()) return@inner
                                if (lostPersonalPfd != null && !ownsLostPersonalTransport()) return@inner
                                networkLossJob = null
                                handleAllowedNetworkChange(upstream, fallback, "Fallback")
                            } else if (OlcboxVpnState.status.value is VpnStatus.Connected ||
                                OlcboxVpnState.status.value is VpnStatus.Reconnecting
                            ) {
                                updateUnderlyingNetwork(null)
                                unbindProcessFromNetwork()
                                setStatus(VpnStatus.Reconnecting)
                                updateNotification("Waiting for network...")
                                addLog("Waiting for upstream network")
                            }
                            if (lostPersonalPfd != null && ownsLostPersonalTransport() &&
                                !(fallback != null && fallback.transport == UpstreamTransport.Wifi &&
                                    fallback.usable && !fallback.captive)
                            ) {
                                // Actual selected loss is the trigger, not arbitrary snapshot/proof failure.
                                recoverStoppedPersonalTransport(lostPersonalPfd, registrationGeneration,
                                    PersonalTransportRecoveryTrigger.PhysicalLoss)
                            }
                        }
                    }
                }
            }
        }

        private fun queueNetworkChange(network: Network, snapshot: PhysicalNetworkSnapshot?, reason: String) {
            if (snapshot == null) return
            scope.launch {
                tunnelMutex.withLock {
                    if (registrationGeneration != generation ||
                        snapshots.current(network) != snapshot || snapshots.networkFor(snapshot) == null
                    ) return@withLock
                    if (denyConnectedSubscriptionIfNeeded()) return@withLock
                    if (network == currentNetwork) invalidateWorkingPersonalProofForNetwork(network, snapshot)
                    handleAllowedNetworkChange(network, snapshot, reason)
                    if (network == findActiveUpstreamNetwork()) {
                        if (packageName == PERSONAL_TEST_PACKAGE) {
                            addLog("Physical path: ${snapshot.transport}, probeReady=${snapshot.isProbeReady}, " +
                                "usable=${snapshot.usable}, captive=${snapshot.captive}")
                        }
                        restrictedNetworkCoordinator?.onNetworkEvent(network, snapshot)
                    }
                }
            }
        }
    }

    private fun handleAllowedNetworkChange(network: Network, snapshot: PhysicalNetworkSnapshot, reason: String) {
        val upstream = findActiveUpstreamNetwork() ?: return
        if (network != upstream || !snapshot.usable) return
        if (generalRecoveryJob?.isActive == true) return // Held descriptor/owner recovery owns replacement.
        if (generalConsumer?.hasOwnedRuntime() == true) {
            val binding = generalActiveBinding
            val health = binding?.let { generalConsumer?.observeTransportHealth(it) }
            val reason = binding?.let { GeneralNetworkRecoveryPolicy.assess(it.network, it.snapshot,
                upstream, snapshot, generalConsumer?.isRunning() == true, isTunBridgeReadyForActiveTransport(),
                health?.health ?: GeneralTransportHealth33.UNKNOWN) }
            if (binding == null || GeneralNetworkRecoveryPolicy.recoveryRequired(reason, health?.recoveryDue ?: true)) {
                Log.d("RgaGeneralRuntime", "recoveryOrigin=NETWORK_CALLBACK reason=${reason?.name ?: "BindingAbsent"}")
                binding?.let { Log.d("RgaGeneralRuntime", "snapshotDelta=${GeneralNetworkRecoveryPolicy.snapshotDelta(it.snapshot, snapshot).name}") }
                val captured = generalConsumer?.retire()
                if (captured != null) scheduleGeneralRecovery(upstream, snapshot, captured)
            }
            return
        }
        if (snapshot.transport == UpstreamTransport.Wifi) restrictedNoticeActive = false
        if (OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc) {
            if (personalOrdinaryLocation != null) {
                if (currentNetwork != upstream) updateUnderlyingNetwork(upstream)
                if (snapshot.transport == UpstreamTransport.Wifi) schedulePersonalReturn()
                return
            }
            if (currentNetwork != upstream && recoveryJob?.isActive != true) {
                updateUnderlyingNetwork(upstream)
                requestTransportRecovery("Upstream network changed", fullRestart = false,
                    delayMs = NETWORK_STABILITY_GRACE_MS)
            }
            return
        }
        val decision = OrdinaryNetworkRefreshPolicy.decide(
            eventIsSelected = true,
            sameNetwork = upstream == lastConnectedOrdinaryNetwork &&
                (lastConnectedOrdinaryNetworkId == null || snapshot.networkId == lastConnectedOrdinaryNetworkId),
            previousTransport = lastConnectedOrdinaryTransport,
            nextTransport = snapshot.transport,
            previousDns = lastConnectedOrdinaryDns,
            nextDns = snapshot.dnsServers,
            status = OlcboxVpnState.status.value
        )
        if (decision == NetworkRefreshDecision.AwaitFacts || decision == NetworkRefreshDecision.Ignore) return
        networkLossJob?.cancel()
        if (currentNetwork != upstream) updateUnderlyingNetwork(upstream)
        if (decision == NetworkRefreshDecision.Keep) return
        if (recoveryJob?.isActive == true || startupJob?.isActive == true) return
        addLog("Network $reason: recovering selected transport")
        requestTransportRecovery("Upstream network changed", fullRestart = false,
            delayMs = personalRestrictedRecoveryDelay(NETWORK_STABILITY_GRACE_MS, snapshot.transport))
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch { notificationWords = NOTIFICATION_TEXTS.mapValues { resourceString(it.value) } }
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olcbox::VpnWakeLock")
            .apply { setReferenceCounted(false) }

        installMobileCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (generalLifecycleRetired || AndroidGeneralProcessCustody.actual.retryForeign(this)) {
            addLog("Previous GENERAL service custody is unresolved; retrying its exact cleanup")
            return START_NOT_STICKY
        }
        val startIntent = intent ?: run {
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }
        if (cleanupStopServiceRequested && startIntent.action != OlcboxVpnActions.ACTION_STOP_VPN)
            return START_NOT_STICKY
        when (startIntent.action) {
            OlcboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_START_VPN -> Unit
            // Android's always-on VPN starts the service with the interface
            // action and nothing else, at boot and whenever it brings the VPN
            // back: connect the way a tap on Connect does, from what the app last
            // saved. It used to fall to the branch below and stop at once, so an
            // always-on VPN never came up — and with "Block connections without
            // VPN" on, neither did the phone's network.
            SERVICE_INTERFACE -> addLog("Started by Android (always-on VPN)")
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (stopCleanupPending || generalProcessLease?.takeIf { AndroidGeneralProcessCustody.actual.owns(it) }?.isRetiring() == true) {
            addLog("Previous VPN stop is incomplete; retrying cleanup before start")
            cleanup()
            return START_NOT_STICKY
        }

        val options = loadStartOptions(startIntent)
        // Always-on is a VPN by definition: a saved proxy mode never establishes
        // the tun the system is waiting for.
        applyStartOptions(
            if (startIntent.action == SERVICE_INTERFACE) options.copy(connectionMode = AndroidConnectionMode.Tun) else options
        )
        val isRestart = shouldRestartForStartCommand()
        if (isRestart) {
            addLog("Restarting ${activeModeLabel()} for selected location")
        }
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                "Starting proxy..."
            } else {
                "Protecting your connection"
            }
        )
        startTunnel(isMigration = false, isRestart = isRestart)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        generalLifecycleRetired = true
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        generalLifecycleRetired = true
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        val runtime = olcrtc ?: return
        runtime.setProtector { fd ->
            connectionMode == AndroidConnectionMode.Proxy || this@OlcboxVpnService.protect(fd.toInt())
        }
        runtime.setLogWriter { msg ->
            val line = msg.trimEnd()
            addLog("rtc: $line")
            // Its own tag, so it needs its own scrub: this one does not go through
            // addLog, and logcat is captured by bug reports.
            Log.v("olcrtc", LogScrubber.default.scrub(line))
            handleRtcLine(line)
        }
    }

    private fun loadStartOptions(intent: Intent): StartOptions {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        val socksPort = if (intent.hasExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT)) {
            intent.getIntExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.DEFAULT_PORT
            )
        } else {
            preferences?.get(KEY_ANDROID_SOCKS_PORT)
        }

        return StartOptions(
            connectionMode = AndroidConnectionMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE)
                    ?: preferences?.get(KEY_ANDROID_CONNECTION_MODE)
            ),
            socksListenHost = AndroidSocksProxySettings.sanitizeHost(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_HOST)
            ),
            socksListenPort = AndroidSocksProxySettings.sanitizePort(socksPort),
            socksUsername = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_USERNAME)
                ).orEmpty().takeIf { it.isNotBlank() }.orEmpty(),
            socksPassword = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_PASSWORD)
                ).orEmpty(),
            splitTunnelMode = AndroidSplitTunnelMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE)
                    ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
            ),
            splitTunnelProxyApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty(),
            splitTunnelBypassApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty()
        )
    }

    private fun applyStartOptions(options: StartOptions) {
        connectionMode = options.connectionMode
        socksListenHost = options.socksListenHost
        socksListenPort = options.socksListenPort
        socksUsername = options.socksUsername
        socksPassword = options.socksPassword
        splitTunnelMode = options.splitTunnelMode
        splitTunnelProxyApps = options.splitTunnelProxyApps
        splitTunnelBypassApps = options.splitTunnelBypassApps
    }

    private fun Intent.stringCollectionExtra(key: String): Set<String>? {
        @Suppress("DEPRECATION")
        val value = extras?.get(key) ?: return null
        val items = when (value) {
            is ArrayList<*> -> value.asSequence()
            is Set<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return emptySet()
        }
        return items
            .mapNotNull { (it as? String)?.trim()?.takeIf { item -> item.isNotBlank() } }
            .toSet()
    }

    @Synchronized
    private fun startTunnel(
        isMigration: Boolean,
        forceFullRestart: Boolean = false,
        isRestart: Boolean = false
    ) {
        if (generalLifecycleRetired || cleanupStopServiceRequested || AndroidGeneralProcessCustody.actual.retryForeign(this) ||
            generalProcessLease?.takeIf { AndroidGeneralProcessCustody.actual.owns(it) }?.isRetiring() == true ||
            stopCleanupPending || OlcboxVpnState.status.value is VpnStatus.Stopping) return
        val capturedGeneral = generalConsumer?.retire() // SAME owner BEFORE generation invalidation/cancel.
        generalPrefetchJob?.cancel()
        generalHandoffJob?.cancel()
        generalRecoveryJob?.cancel()
        val previousPersonalSubscriptionUrl = connectedPersonalSubscriptionUrl
        val previousStartupJob = startupJob
        val hadPendingStartup = previousStartupJob?.isActive == true
        previousStartupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        personalBootstrapPrefetchJob?.cancel()
        personalBootstrapInspectionJob?.cancel()
        personalHandoffJob?.cancel()
        personalReturnJob?.cancel()
        invalidatePersonalProof()
        personalOrdinaryLocation = null
        personalActiveBootstrap = null
        connectedPersonalSubscriptionUrl = null
        if (hadPendingStartup) {
            addLog("Canceling pending olcRTC start")
            stopMobile()
            stopTun2socks()
        }
        if (!isMigration) {
            resetRecoveryState()
        }
        val requestedGeneration = ++generation
        refreshWakeLock(force = true)

        startupJob = scope.launch {
            try {
                cleanupJob?.takeIf { it.isActive }?.let {
                    addLog("Waiting for previous olcRTC cleanup")
                    val completed = withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) {
                        it.join()
                        true
                    } ?: false

                    if (!completed) {
                        addLog("Previous olcRTC cleanup is still pending; forcing transport cleanup")
                        it.cancel()
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                    }
                }

                if (requestedGeneration != generation) return@launch
                registerNetworkMonitor(requestedGeneration)
                if (!isMigration) {
                    updateUnderlyingNetwork(findActiveUpstreamNetwork())
                }

                tunnelMutex.withLock {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) return@withLock
                    if (capturedGeneral != null && !stopGeneralForCleanup(capturedGeneral, 20_000)) {
                        stopCleanupPending = true
                        failIncompleteTransportStop()
                        return@withLock
                    }

                    val bundle = repository.getBundle()
                    ApolloVpnDisclosureGate.denial(bundle)?.let { denial ->
                        setStatus(VpnStatus.Error(denial))
                        updateNotification(denial)
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }
                    val denial = SubscriptionTunnelAccess.denial(bundle, bundle.activeLocationId, System.currentTimeMillis(), runtimePolicy)
                    if (denial != null) {
                        setStatus(VpnStatus.Error(denial))
                        updateNotification(denial)
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }
                    val active = bundle.locations.firstOrNull { it.storageId == bundle.activeLocationId }
                    val location = active?.location?.normalized()
                    if (location == null || !location.isComplete()) {
                        setStatus(VpnStatus.Error("No active location"))
                        updateNotification("Add a location first")
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }
                    OlcboxVpnState.activeLocation = location.normalized()
                    connectedStorageId = active.storageId
                    connectedPersonalSubscriptionUrl = selectPersonalSubscriptionUrl(
                        packageName, runtimePolicy, connectionMode, bundle, active.storageId
                    )
                    connectedGeneralSubscriptionUrl = selectGeneralSubscriptionUrl(packageName, connectionMode,
                        splitTunnelMode, bundle, active.storageId, ensureGeneralConsumer() != null)
                    if (packageName == "tech.gatealtas.rga") observeGeneralBootstrap(
                        if (connectedGeneralSubscriptionUrl != null) GeneralBootstrapDiagnostic.Eligible else GeneralBootstrapDiagnostic.Ineligible)
                    if (connectedPersonalSubscriptionUrl != previousPersonalSubscriptionUrl) {
                        lastPersonalPrefetchAtElapsedMs = 0L
                    }
                    val routingSettings = repository.getRoutingSettings()
                    routingMode = routingSettings.mode
                    this@OlcboxVpnService.routingSettings = routingSettings
                    verboseDebugLogs = routingSettings.verboseDebugLogs

                    if (isMigration && !forceFullRestart && canReconnectTransportInPlace(location)) {
                        reconnectTransport(location, requestedGeneration)
                    } else {
                        startFullTunnel(location, requestedGeneration, isMigration, isRestart)
                    }
                }
            } finally {
                if (requestedGeneration == generation) {
                    releaseWakeLock()
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification("Waiting for network...")
            addLog("No upstream network; keeping tunnel alive")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        val previousStopped = stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return
        if (!previousStopped) {
            failIncompleteTransportStop()
            return
        }

        if (startTransport(location, upstream, requestedGeneration, setErrorOnFailure = false, reuseBridge = true)) {
            if (connectionMode == AndroidConnectionMode.Tun &&
                !isTunBridgeReadyForActiveTransport()
            ) {
                addLog("TUN bridge endpoint changed; rebuilding tunnel")
                startFullTunnel(location, requestedGeneration, isMigration = true, isRestart = false)
                return
            }
            val exit = verifyTunnel()
            if (requestedGeneration != generation) return
            if (exit == null) {
                failUnverifiedTunnel(
                    what = "${activeModeLabel()} transport",
                    isMigration = true,
                    requestedGeneration = requestedGeneration
                )
                return
            }
            if (!markVerifiedOrdinaryNetwork(upstream, location)) {
                retryAfterVerifiedNetworkChange(requestedGeneration)
                return
            }
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected — exit ${exit.label()}")
            startWatchdog()
        } else {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
            scheduleTransportRetry(requestedGeneration, "transport reconnect failed")
        }
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean
    ) {
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Connecting...")
        val previousStopped = stopTransportProcesses(closeTun = true, waitForSocksPort = true)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return
        if (!previousStopped) {
            failIncompleteTransportStop()
            return
        }

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("No upstream network")
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for network...")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }
        updateUnderlyingNetwork(upstream)

        if (!startTransport(location, upstream, requestedGeneration, setErrorOnFailure = !isMigration)) {
            if (isMigration) {
                updateUnderlyingNetwork(null)
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Waiting for transport...")
                scheduleTransportRetry(requestedGeneration, "transport start failed")
            }
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (connectionMode == AndroidConnectionMode.Proxy) {
//            if (!startAuthenticatedSocksProxy()) {
//                stopTransportProcesses(closeTun = true)
//                return
//            }
            val proxyExit = verifyTunnel()
            if (requestedGeneration != generation) return
            if (proxyExit == null) {
                failUnverifiedTunnel("Proxy mode", isMigration, requestedGeneration)
                return
            }
            if (!markVerifiedOrdinaryNetwork(upstream, location)) {
                retryAfterVerifiedNetworkChange(requestedGeneration)
                return
            }
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog(
                "Proxy mode connected on SOCKS $socksListenHost:" +
                    "${activeCorePort ?: socksListenPort} — exit ${proxyExit.label()}"
            )
            startWatchdog()
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobileAndWait()
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        if (!isTunBridgeReadyForActiveTransport()) {
            addLog("TUN bridge endpoint does not match transport")
            stopTransportProcesses(closeTun = true, waitForSocksPort = false)
            if (isMigration) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Waiting for transport...")
                scheduleTransportRetry(requestedGeneration, "TUN bridge endpoint mismatch")
            } else {
                setStatus(VpnStatus.Error("TUN bridge endpoint mismatch"))
                updateNotification("Tunnel failed")
            }
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val exit = verifyTunnel()
        if (requestedGeneration != generation) return
        if (exit == null) {
            failUnverifiedTunnel("VPN tunnel", isMigration, requestedGeneration)
            return
        }

        if (!markVerifiedOrdinaryNetwork(upstream, location)) {
            retryAfterVerifiedNetworkChange(requestedGeneration)
            return
        }
        setStatus(VpnStatus.Connected)
        resetRecoveryState()
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established — exit ${exit.label()}")
        startWatchdog()
    }

    /**
     * Ask the internet what this tunnel looks like from the far end, before the app
     * is allowed to call itself connected.
     *
     * Every connect failure seen in the field so far reported success: a core whose
     * port was already taken, a hysteria2 outbound rejected at TLS, an xhttp path
     * that never carried a byte. Each of them ran its steps without error, so the
     * status meant "we did the setup", not "traffic reaches the internet". A reply
     * that came back through the tunnel is the one signal that cannot be faked.
     *
     * The probe goes through the local SOCKS port the transport opened, which proves
     * the transport itself reaches the internet. It says nothing about the
     * tun → tun2socks leg: this app's UID bypasses the tun by design, so its own
     * traffic cannot travel that path.
     */
    private suspend fun verifyTunnel(): TunnelExit? {
        updateNotification("Verifying tunnel...")
        val (username, password) = upstreamLogin()
        return TunnelVerifier.verify(
            socksHost = socksConnectHost(),
            socksPort = activeCorePort ?: socksListenPort,
            username = username,
            password = password
        )
    }

    /**
     * A transport that came up but carried nothing. On a first connect that is a
     * plain failure; during a migration the recovery machinery owns the retry, the
     * same way a failed transport start is handled.
     */
    private suspend fun failUnverifiedTunnel(
        what: String,
        isMigration: Boolean,
        requestedGeneration: Long
    ) {
        addLog("$what is up but no traffic reached the internet through it")
        val status = OlcboxVpnState.status.value
        val linkedGeneralMigration = isMigration && packageName == "tech.gatealtas.rga" &&
            connectedGeneralSubscriptionUrl != null && status is VpnStatus.Reconnecting
        if ((!isMigration && status is VpnStatus.Connecting) || linkedGeneralMigration) {
            val handled = try {
                coldStartupRestrictionAssessment(requestedGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unknown probes/cache errors never authorize a fallback or skip cleanup.
                false
            }
            if (handled) return
            coroutineContext.ensureActive()
            // Stop/new Connect owns its own resources; never clean up a newer attempt.
            if (requestedGeneration != generation || OlcboxVpnState.status.value is VpnStatus.Stopping) return
        }
        if (isMigration) {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
            scheduleTransportRetry(requestedGeneration, "tunnel carried no traffic")
        } else {
            stopTransportProcesses(closeTun = true, waitForSocksPort = false)
            setStatus(VpnStatus.Error("No traffic reached the internet through the tunnel"))
            updateNotification("Connection failed")
        }
    }

    /** Runs under the existing startup mutex, BEFORE cold-failure cleanup.
     * Never call the locked warm coordinator from here or promote Connecting globally.
     */
    @Synchronized private fun ensureGeneralConsumer(): AndroidGeneralServiceConsumer? {
        if (packageName != "tech.gatealtas.rga" || connectionMode != AndroidConnectionMode.Tun ||
            splitTunnelMode != AndroidSplitTunnelMode.AllApps || generalLifecycleRetired || stopCleanupPending ||
            AndroidGeneralProcessCustody.actual.retryForeign(this)) return null
        generalProcessLease?.takeIf { AndroidGeneralProcessCustody.actual.owns(it) && !it.isRetiring() }
            ?.let { return it.consumer }
        val retained = generalConsumer
        val factory = if (retained == null) AndroidGeneralRuntimeProvider.forApp(applicationContext) ?: return null else null
        val lease = AndroidGeneralProcessCustody.actual.acquire(this,
            { retained ?: AndroidGeneralServiceConsumer.actual(AndroidGeneralRuntimeOwner(checkNotNull(factory))) },
            { cleanup(stopService = false) }) ?: return null
        if (lease.isRetiring()) return null
        generalProcessLease = lease
        return lease.consumer.also { generalConsumer = it }
    }

    private suspend fun generalSelectionStillCurrent(storageId: String, link: String, expectedGeneration: Long): Boolean {
        val bundle = repository.getBundle()
        coroutineContext.ensureActive()
        return generation == expectedGeneration && !stopCleanupPending && !generalLifecycleRetired &&
            generalProcessLease?.let { AndroidGeneralProcessCustody.actual.owns(it) && !it.isRetiring() } == true &&
            selectGeneralSubscriptionUrl(packageName, connectionMode, splitTunnelMode, bundle, storageId,
                generalConsumer != null) == link &&
            SubscriptionTunnelAccess.denial(bundle, storageId, System.currentTimeMillis(), runtimePolicy) == null &&
            connectedStorageId == storageId && connectedGeneralSubscriptionUrl == link
    }

    /** Explicit local policy denial only. Never infer subscription state from a
     * carrier timeout, SDK text or native UNKNOWN. The server remains authority. */
    private suspend fun publishGeneralPolicyDenial(storageId: String, link: String, expectedGeneration: Long) {
        val bundle = repository.getBundle()
        coroutineContext.ensureActive()
        if (selectGeneralSubscriptionUrl(packageName, connectionMode, splitTunnelMode, bundle, storageId,
                generalConsumer != null) != link ||
            SubscriptionTunnelAccess.denial(bundle, storageId, System.currentTimeMillis(), runtimePolicy) == null) return
        synchronized(this) {
            if (generation == expectedGeneration && !stopCleanupPending && !generalLifecycleRetired &&
                connectedStorageId == storageId && connectedGeneralSubscriptionUrl == link &&
                OlcboxVpnState.status.value !is VpnStatus.Stopping && OlcboxVpnState.status.value !is VpnStatus.Disconnected)
                OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Denied)
        }
    }

    @Synchronized private fun generalCache(channel: org.olcbox.app.vpn.OlcChannelRole =
        org.olcbox.app.vpn.OlcChannelRole.Primary): AndroidGeneralBootstrapCache? {
        if (packageName != "tech.gatealtas.rga") return null
        if (channel == org.olcbox.app.vpn.OlcChannelRole.Reserve) return generalReserveBootstrapCache ?:
            AndroidGeneralBootstrapCache.forApp(applicationContext, channel)?.also { generalReserveBootstrapCache = it }
        return generalBootstrapCache ?: AndroidGeneralBootstrapCache.forApp(applicationContext)?.also { generalBootstrapCache = it }
    }

    private fun scheduleGeneralBootstrapPrefetch(upstream: Network) {
        if (packageName != "tech.gatealtas.rga") return
        observeGeneralBootstrap(GeneralBootstrapDiagnostic.Schedule)
        val link = connectedGeneralSubscriptionUrl ?: run { observeGeneralBootstrap(GeneralBootstrapDiagnostic.LinkAbsent); return }
        val storageId = connectedStorageId ?: return
        if (ensureGeneralConsumer() == null || generalConsumer?.hasOwnedRuntime() == true ||
            generalPrefetchJob?.isActive == true || currentNetwork != upstream) {
            observeGeneralBootstrap(GeneralBootstrapDiagnostic.Busy); return
        }
        val capturedGeneration = generation
        val capturedSnapshot = physicalNetworkSnapshots?.current(upstream) ?: run {
            observeGeneralBootstrap(GeneralBootstrapDiagnostic.SnapshotAbsent); return
        }
        generalPrefetchJob = scope.launch(Dispatchers.IO) {
            try {
                observeGeneralBootstrap(GeneralBootstrapDiagnostic.Prefetch)
                if (!generalSelectionStillCurrent(storageId, link, capturedGeneration) ||
                    findActiveUpstreamNetwork() != upstream || physicalNetworkSnapshots?.current(upstream) != capturedSnapshot) {
                    observeGeneralBootstrap(GeneralBootstrapDiagnostic.Superseded); return@launch
                }
                for (channel in org.olcbox.app.vpn.OlcChannelRole.entries) {
                if (!generalSelectionStillCurrent(storageId, link, capturedGeneration) ||
                    findActiveUpstreamNetwork() != upstream || physicalNetworkSnapshots?.current(upstream) != capturedSnapshot) return@launch
                val cache = generalCache(channel) ?: continue
                val transport = AndroidGeneralBootstrapTransport.forApp(applicationContext, upstream) ?: return@launch
                val producer = if (channel == org.olcbox.app.vpn.OlcChannelRole.Reserve)
                    AndroidVerifiedGeneralBootstrapProducer.managedReserve(transport) else
                    AndroidVerifiedGeneralBootstrapProducer.managed(transport)
                val fetched = withTimeoutOrNull(12_000L) { cache.prefetch(link, producer) } == true
                observeGeneralBootstrap(if (fetched) GeneralBootstrapDiagnostic.Ready else GeneralBootstrapDiagnostic.Unavailable)
                // Discovery receipt remains link/key bound even when this no-admission publication is superseded.
                generalSelectionStillCurrent(storageId, link, capturedGeneration)
                coroutineContext.ensureActive()
                if (findActiveUpstreamNetwork() != upstream || physicalNetworkSnapshots?.current(upstream) != capturedSnapshot) return@launch
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional online discovery never blocks the ordinary selected host. */ }
        }
    }

    private suspend fun assessGeneralColdStartup(expectedGeneration: Long): Boolean {
        val consumer = ensureGeneralConsumer() ?: return false
        val status = OlcboxVpnState.status.value
        if (consumer.hasOwnedRuntime() || expectedGeneration != generation ||
            (status !is VpnStatus.Connecting && status !is VpnStatus.Reconnecting)) return false
        val pfd = vpnInterface ?: return false
        val storageId = connectedStorageId ?: return false
        val link = connectedGeneralSubscriptionUrl ?: return false
        val network = findActiveUpstreamNetwork() ?: return false
        val snapshots = physicalNetworkSnapshots ?: return false
        val snapshot = snapshots.current(network) ?: return false
        val front = activeCorePort ?: return false
        val backend = activeCoreBackendPort
        val probePort = AndroidSelectedPathProbe.transportPort(front, backend, routingSettings.needsRules) ?: return false
        val facts = ordinaryRoutingFacts ?: return false
        if (!snapshot.isProbeReady || snapshot.generation != expectedGeneration ||
            facts.generation != expectedGeneration || facts.network != network ||
            facts.networkId != snapshot.networkId || facts.dnsServers != snapshot.dnsServers) return false
        val credentials = upstreamLogin()
        val current: suspend () -> Boolean = {
            generalSelectionStillCurrent(storageId, link, expectedGeneration) && vpnInterface === pfd && pfd.fileDescriptor.valid() &&
                (OlcboxVpnState.status.value is VpnStatus.Connecting || OlcboxVpnState.status.value is VpnStatus.Reconnecting) &&
                currentNetwork == network && findActiveUpstreamNetwork() == network && snapshots.current(network) == snapshot &&
                snapshots.networkFor(snapshot) == network && activeCorePort == front && activeCoreBackendPort == backend &&
                ordinaryRoutingFacts == facts && isTunBridgeReadyForActiveTransport() && isActiveTransportRunning()
        }
        return withTimeoutOrNull(90_000L) {
            // Fixed vocabulary only; no subscription, room, network address or key.
            Log.d("RgaGeneralFallback", "assessment=ENTERED")
            // Reuse the same non-admitting physical/ordinary-path classifier, never personal metadata or proof.
            val candidate = PersonalColdRestrictedStartup(current,
                { BoundPhysicalNetworkProbe().collect(network, snapshot.generation, snapshot.networkId, snapshot.revision).also {
                    Log.d("RgaGeneralFallback", "physical=${it.foreignA}/${it.foreignB}/${it.domestic}")
                } },
                { selectedPathProbe.collect(true, isActiveTransportRunning(), isTunBridgeReadyForActiveTransport(),
                    SubscriptionFetchProxy(socksConnectHost(), probePort, credentials.first, credentials.second)).also {
                    Log.d("RgaGeneralFallback", "selected=${it.selectedTransport},bridge=${it.tunBridge}")
                } }).assess(snapshot)
                ?: run { Log.d("RgaGeneralFallback", "assessment=INCONCLUSIVE"); return@withTimeoutOrNull false }
            if (!current() || candidate.generation != expectedGeneration) return@withTimeoutOrNull false
            Log.d("RgaGeneralFallback", "assessment=CANDIDATE")
            handoffToGeneral(network, snapshot, expectedGeneration)
        } ?: false
    }

    /** Typed cache is discovery. Only the owner/native fresh-server proof can start the GENERAL runtime. */
    private suspend fun handoffToGeneral(network: Network, snapshot: PhysicalNetworkSnapshot, expectedGeneration: Long): Boolean {
        val consumer = ensureGeneralConsumer() ?: return false
        if (consumer.hasOwnedRuntime()) return true
        val storageId = connectedStorageId ?: return false
        val link = connectedGeneralSubscriptionUrl ?: return false
        val heldTun = vpnInterface ?: return false
        val ordinary = generalOrdinaryLocation ?: OlcboxVpnState.activeLocation ?: return false
        if (!generalSelectionStillCurrent(storageId, link, expectedGeneration)) {
            publishGeneralPolicyDenial(storageId, link, expectedGeneration)
            return false
        }
        val candidates = withContext(Dispatchers.IO) {
            val key = AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate()
            org.olcbox.app.net.generalBootstrapCandidates(generalCache()?.read(link),
                generalCache(org.olcbox.app.vpn.OlcChannelRole.Reserve)?.read(link), key, System.currentTimeMillis())
        }
        val current: suspend () -> Boolean = {
            coroutineContext.ensureActive()
            val allowed = generalSelectionStillCurrent(storageId, link, expectedGeneration)
            if (!allowed) publishGeneralPolicyDenial(storageId, link, expectedGeneration)
            allowed && vpnInterface === heldTun &&
                heldTun.fileDescriptor.valid() && findActiveUpstreamNetwork() == network &&
                physicalNetworkSnapshots?.current(network) == snapshot
        }
        return org.olcbox.app.net.runGeneralBootstrapCandidates(candidates, current, { candidate, primaryFailed ->
            synchronized(this) {
                if (generation != expectedGeneration || stopCleanupPending || generalLifecycleRetired ||
                    vpnInterface !== heldTun || connectedStorageId != storageId || connectedGeneralSubscriptionUrl != link ||
                    currentNetwork != network || physicalNetworkSnapshots?.current(network) != snapshot) false
                else {
                    try {
                        Log.d("RgaGeneralRuntime", if (candidate.channel == org.olcbox.app.vpn.OlcChannelRole.Reserve)
                            "channel=RESERVE" else "channel=PRIMARY")
                    } catch (_: Exception) { /* Diagnostics must not alter selection. */ }
                    OlcboxVpnState.setGeneralChannel(candidate.channel, primaryFailed); true
                }
            }
        }, { candidate, hasNext ->
            handoffToGeneralCandidate(network, snapshot, expectedGeneration, candidate.selection, hasNext)
        }, {
            // The next profile may be rejected before it owns native resources.
            // Converge the already-retired transition; never abandon a held TUN.
            if (generation != expectedGeneration || vpnInterface !== heldTun || stopCleanupPending ||
                generalLifecycleRetired || OlcboxVpnState.status.value is VpnStatus.Stopping) true
            else {
                val restored = current() && !consumer.hasOwnedRuntime() &&
                    recoverGeneralOrdinaryOnHeldTun(heldTun, ordinary, network, expectedGeneration, current) && current()
                if (restored) {
                    generalActiveBinding = null; generalOptions = null; generalOrdinaryLocation = null
                    OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
                    setStatus(VpnStatus.Connected); startWatchdog()
                } else cleanup(stopService = false)
                true
            }
        })
    }

    private suspend fun handoffToGeneralCandidate(network: Network, snapshot: PhysicalNetworkSnapshot, expectedGeneration: Long,
        selection: GeneralBootstrapSelection, continueAfterFailure: Boolean): org.olcbox.app.net.GeneralCandidateOutcome {
        Log.d("RgaGeneralRuntime", "handoff=ENTERED")
        val consumer = ensureGeneralConsumer() ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        if (consumer.hasOwnedRuntime()) return org.olcbox.app.net.GeneralCandidateOutcome.Handled // Unknown retained owner must be cleaned up, never replaced.
        val pfd = vpnInterface ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val storageId = connectedStorageId ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val link = connectedGeneralSubscriptionUrl ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val ordinary = generalOrdinaryLocation ?: OlcboxVpnState.activeLocation ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        generalProcessLease?.captureTun(pfd) ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped // Process custody BEFORE actual owner/native startup.
        Log.d("RgaGeneralRuntime", "handoff=LEASE_READY")
        if (!generalSelectionStillCurrent(storageId, link, expectedGeneration)) return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        Log.d("RgaGeneralRuntime", "handoff=CACHE_READY")
        if (!generalSelectionStillCurrent(storageId, link, expectedGeneration)) return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val key = withContext(Dispatchers.IO) { AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate() }
        Log.d("RgaGeneralRuntime", "handoff=KEY_READY")
        if (!generalSelectionStillCurrent(storageId, link, expectedGeneration) ||
            !selection.matchesKeyAndIsUsableAt(key, System.currentTimeMillis()) || vpnInterface !== pfd ||
            findActiveUpstreamNetwork() != network || physicalNetworkSnapshots?.current(network) != snapshot) return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val binding = GeneralServiceBinding(expectedGeneration, pfd, network, snapshot, storageId, link, selection, key)
        val login = SocksLogin(java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString())
        val routing = routingFor(network, LocationKind.Olcrtc)
        if (!generalSelectionStillCurrent(storageId, link, expectedGeneration) || vpnInterface !== pfd ||
            findActiveUpstreamNetwork() != network || physicalNetworkSnapshots?.current(network) != snapshot) return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val rules = OlcrtcDirectRules.forRouting(routing)
        if (!generalSelectionStillCurrent(storageId, link, expectedGeneration) || vpnInterface !== pfd ||
            findActiveUpstreamNetwork() != network || physicalNetworkSnapshots?.current(network) != snapshot) return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val controlHost = GeneralControlCapture.canonicalHost(link) ?: return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
        val controlRules = listOf("tunnel-domain:rga.apollot.ru", "tunnel-domain:$controlHost").distinct().joinToString("\n", postfix="\n")
        val options = AndroidGeneralRuntimeOptions({ fd ->
            generation == expectedGeneration && currentNetwork == network && this@OlcboxVpnService.protect(fd.toInt())
        }, freeLoopbackPort().toLong(), login.username, login.password, upstreamDnsList(network),
            controlRules + AndroidGeneralServiceConsumer.PROBE_RULE + "\n" + rules,
            log = { event -> Log.d("RgaGeneralRuntime", event) })
        Log.d("RgaGeneralRuntime", "handoff=CONTEXT_READY")
        generalActiveBinding = binding; generalOrdinaryLocation = ordinary; generalOptions = options
        var expectedBridgeEpoch = generalBridgeEpoch.get()
        var bridgeStopped = false
        val current: suspend () -> Boolean = {
            val allowed = generalSelectionStillCurrent(storageId, link, expectedGeneration)
            if (!allowed) publishGeneralPolicyDenial(storageId, link, expectedGeneration)
            allowed &&
                binding.matchesKey(AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate()) &&
                selection.matchesKeyAndIsUsableAt(key, System.currentTimeMillis()) && generalActiveBinding === binding &&
                vpnInterface === pfd && pfd.fileDescriptor.valid() && currentNetwork == network &&
                findActiveUpstreamNetwork() == network && physicalNetworkSnapshots?.current(network) == snapshot &&
                physicalNetworkSnapshots?.networkFor(snapshot) == network && generalBridgeEpoch.get() == expectedBridgeEpoch &&
                OlcboxVpnState.status.value !is VpnStatus.Stopping && OlcboxVpnState.status.value !is VpnStatus.Disconnected
        }
        val ops = GeneralServiceOperations(current,
            { current() && stopBridgeKeepingTun(pfd).also { bridgeStopped = it } },
            {
                if (!current()) false else {
                    stopCoreProcesses()
                    if (!awaitRetiredCorePorts() || !current()) false else { bindProcessToNetwork(network); true }
                }
            },
            {
                if (!current()) false else {
                    activeCorePort = options.socksPort.toInt(); activeCoreBackendPort = null; activeCoreLogin = login
                    expectedBridgeEpoch = generalBridgeEpoch.incrementAndGet()
                    startBridgeOnHeldTun(pfd)
                }
            },
            {
                if (!current() || consumer.hasOwnedRuntime()) false else {
                    unbindProcessFromNetwork()
                    val restored = recoverGeneralOrdinaryOnHeldTun(pfd, ordinary, network, expectedGeneration, current)
                    if (restored && current()) {
                        generalActiveBinding = null; generalOrdinaryLocation = null; generalOptions = null
                        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
                        setStatus(VpnStatus.Connected); startWatchdog()
                        true
                    } else false
                }
            }, { expectedBridgeEpoch }, { phase ->
                val keyCurrent = phase == GeneralTunnelPhase.Stopping || try {
                    binding.matchesKey(AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate()) &&
                        selection.matchesKeyAndIsUsableAt(key, System.currentTimeMillis())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                synchronized(this) {
                    if (generalActiveBinding === binding && vpnInterface === pfd &&
                        (phase == GeneralTunnelPhase.Stopping || generation == expectedGeneration &&
                            currentNetwork == network && physicalNetworkSnapshots?.current(network) == snapshot &&
                            generalBridgeEpoch.get() == expectedBridgeEpoch)) {
                        val fencedPhase = if (!keyCurrent && phase == GeneralTunnelPhase.TransportVerified)
                            GeneralTunnelPhase.Unavailable else phase
                        OlcboxVpnState.setGeneralPhase(fencedPhase)
                        if (fencedPhase == GeneralTunnelPhase.Checking || fencedPhase == GeneralTunnelPhase.TransportVerified)
                            setStatus(VpnStatus.Reconnecting) // Other-UID/full-TUN qualification is intentionally pending.
                    }
                }
            })
        val result = consumer.handoff(binding, options, ops, restoreOnFailure = !continueAfterFailure)
        if (result == GeneralServiceResult.CandidateRetired) {
            // Exact physical settlement was observed. Keep the same TUN and
            // ordinary choice, but never reuse this provider's identity/key.
            generalActiveBinding = null; generalOptions = null; generalOrdinaryLocation = ordinary
            return org.olcbox.app.net.GeneralCandidateOutcome.Retired
        }
        Log.d("RgaGeneralRuntime", "result=${result.name}")
        if (result == GeneralServiceResult.TransportVerified) {
            if (current() && OlcboxVpnState.generalTunnel.value.phase == GeneralTunnelPhase.TransportVerified) {
                publishGeneralControlCapture(binding, expectedBridgeEpoch)
                updateNotification("GENERAL transport verified; full-TUN qualification pending"); startWatchdog()
            } else consumer.captureRecoveryTicket(binding)?.let { scheduleGeneralRecovery(network, snapshot, it) }
            return org.olcbox.app.net.GeneralCandidateOutcome.Handled
        }
        if (result == GeneralServiceResult.OrdinaryRestored) return org.olcbox.app.net.GeneralCandidateOutcome.Handled
        if (consumer.hasOwnedRuntime() || bridgeStopped) {
            if (generation == expectedGeneration && vpnInterface === pfd) {
                OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
                setStatus(VpnStatus.Reconnecting)
                updateNotification("GENERAL unavailable; cleanup/recovery pending")
                startWatchdog()
            }
            return org.olcbox.app.net.GeneralCandidateOutcome.Handled
        }
        generalActiveBinding = null; generalOrdinaryLocation = null; generalOptions = null
        return org.olcbox.app.net.GeneralCandidateOutcome.Skipped
    }

    /** Published only AFTER actual same-owner body verification; no State -> Service lock acquisition. */
    private fun publishGeneralControlCapture(binding: GeneralServiceBinding, bridgeEpoch: Long) {
        val consumer = generalConsumer ?: return
        fun tupleCurrent(): Boolean = generation == binding.serviceGeneration && !stopCleanupPending && !generalLifecycleRetired &&
            generalActiveBinding === binding && vpnInterface === binding.tun && vpnInterface?.fileDescriptor?.valid() == true &&
            currentNetwork == binding.network && GeneralNetworkRecoveryPolicy.sameActivePath(binding.snapshot, physicalNetworkSnapshots?.current(binding.network as Network)) &&
            connectedStorageId == binding.storageId && connectedGeneralSubscriptionUrl == binding.link &&
            generalBridgeEpoch.get() == bridgeEpoch && isTunBridgeReadyForActiveTransport() &&
            OlcboxVpnState.generalTunnel.value.phase == GeneralTunnelPhase.TransportVerified &&
            OlcboxVpnState.status.value is VpnStatus.Reconnecting &&
            generalProcessLease?.let { AndroidGeneralProcessCustody.actual.owns(it) && !it.isRetiring() } == true
        fun currentNow(): Boolean {
            val key = try { AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate() } catch (_: Exception) { return false }
            if (!binding.matchesKey(key)) return false
            return synchronized(this) { tupleCurrent() }
        }
        val captured = consumer.captureControl(::currentNow, { action ->
            if (!currentNow()) false else synchronized(this) { if (!tupleCurrent()) false else { action(); true } }
        }) ?: return
        synchronized(this) { if (tupleCurrent()) OlcboxVpnState.generalControlCapture = captured else captured.retire() }
    }

    @Synchronized
    private fun scheduleGeneralRecovery(network: Network, snapshot: PhysicalNetworkSnapshot,
        captured: AndroidGeneralServiceConsumer.CleanupTicket) {
        // A deferred callback carries its ORIGINAL generation; it must not borrow Stop's epoch.
        val expectedGeneration = captured.serviceGeneration
        if (expectedGeneration <= 0 || expectedGeneration != generation || stopCleanupPending ||
            generalLifecycleRetired || OlcboxVpnState.status.value is VpnStatus.Stopping ||
            generalRecoveryJob?.isActive == true) return
        val pfd = captured.tun as? ParcelFileDescriptor ?: return
        val ordinary = generalOrdinaryLocation ?: return
        var ticket: AndroidGeneralServiceConsumer.CleanupTicket? = captured
        OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
        setStatus(VpnStatus.Reconnecting)
        generalRecoveryJob = scope.launch {
            repeat(3) { attempt ->
                if (attempt > 0) delay(NETWORK_STABILITY_GRACE_MS * attempt)
                val finished = tunnelMutex.withLock {
                    if (expectedGeneration != generation || stopCleanupPending || vpnInterface !== pfd) return@withLock true
                    val storageId = connectedStorageId ?: return@withLock true
                    val link = connectedGeneralSubscriptionUrl ?: return@withLock true
                    if (!generalSelectionStillCurrent(storageId, link, expectedGeneration)) { cleanup(false); return@withLock true }
                    val held = ticket
                    if (held != null && !stopGeneralForCleanup(held, 20_000)) return@withLock false // SAME unknown owner retry.
                    ticket = null
                    if (expectedGeneration != generation || vpnInterface !== pfd) return@withLock true
                    val latest = findActiveUpstreamNetwork() ?: return@withLock false
                    val latestSnapshot = physicalNetworkSnapshots?.current(latest) ?: return@withLock false
                    if (!latestSnapshot.usable || latestSnapshot.captive) return@withLock false
                    updateUnderlyingNetwork(latest)
                    if (latestSnapshot.transport == UpstreamTransport.Wifi) {
                        val ordinaryCurrent: suspend () -> Boolean = {
                            generalSelectionStillCurrent(storageId, link, expectedGeneration) && vpnInterface === pfd &&
                                pfd.fileDescriptor.valid() && findActiveUpstreamNetwork() == latest &&
                                physicalNetworkSnapshots?.current(latest) == latestSnapshot &&
                                physicalNetworkSnapshots?.networkFor(latestSnapshot) == latest &&
                                generalConsumer?.hasOwnedRuntime() != true && OlcboxVpnState.status.value !is VpnStatus.Stopping
                        }
                        if (recoverGeneralOrdinaryOnHeldTun(pfd, ordinary, latest, expectedGeneration, ordinaryCurrent) &&
                            generalSelectionStillCurrent(storageId, link, expectedGeneration) &&
                            physicalNetworkSnapshots?.current(latest) == latestSnapshot) {
                            OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Inactive)
                            setStatus(VpnStatus.Connected); startWatchdog(); return@withLock true
                        }
                        return@withLock false
                    }
                    generalOrdinaryLocation = ordinary
                    val handled = handoffToGeneral(latest, latestSnapshot, expectedGeneration)
                    if (handled && OlcboxVpnState.generalTunnel.value.phase == GeneralTunnelPhase.TransportVerified)
                        return@withLock true
                    ticket = generalActiveBinding?.let { generalConsumer?.captureRecoveryTicket(it) }
                    false
                }
                if (finished) return@launch
            }
            if (generation == expectedGeneration && vpnInterface === pfd) {
                OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
                updateNotification("GENERAL recovery unavailable; retry Stop")
            }
        }
    }

    /** GENERAL-only restoration fencing; the established personal helper remains untouched. */
    private suspend fun recoverGeneralOrdinaryOnHeldTun(pfd: ParcelFileDescriptor, ordinary: LocationConfig,
        network: Network, expectedGeneration: Long, current: suspend () -> Boolean): Boolean {
        if (generalConsumer?.hasOwnedRuntime() == true || !current()) return false
        updateUnderlyingNetwork(network)
        if (!stopBridgeKeepingTun(pfd) || !current()) return false
        stopCoreProcesses()
        if (!awaitRetiredCorePorts() || !current()) return false
        unbindProcessFromNetwork()
        return startTransport(ordinary, network, expectedGeneration, false) && current() &&
            startBridgeOnHeldTun(pfd) && current() && isTunBridgeReadyForActiveTransport() &&
            verifyTunnel() != null && current() && markVerifiedOrdinaryNetwork(network, ordinary) && current()
    }

    private suspend fun assessColdRestrictedStartup(requestedGeneration: Long): Boolean {
        if (packageName == "tech.gatealtas.rga" && connectedGeneralSubscriptionUrl != null)
            return assessGeneralColdStartup(requestedGeneration)
        if (packageName != PERSONAL_TEST_PACKAGE ||
            runtimePolicy != RuntimeLocationPolicy.ExperimentalDebug ||
            connectionMode != AndroidConnectionMode.Tun ||
            OlcboxVpnState.status.value !is VpnStatus.Connecting ||
            requestedGeneration != generation || OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc
        ) return false
        val pfd = vpnInterface ?: return false
        val storageId = connectedStorageId ?: return false
        val url = connectedPersonalSubscriptionUrl ?: return false
        val network = findActiveUpstreamNetwork() ?: return false
        val snapshots = physicalNetworkSnapshots ?: return false
        val snapshot = snapshots.current(network) ?: return false
        val frontPort = activeCorePort ?: return false
        val backendPort = activeCoreBackendPort
        val routingFront = routingSettings.needsRules
        val probePort = AndroidSelectedPathProbe.transportPort(frontPort, backendPort, routingFront) ?: return false
        val facts = ordinaryRoutingFacts ?: return false
        if (facts.generation != requestedGeneration || facts.network != network ||
            facts.networkId != snapshot.networkId || facts.dnsServers != snapshot.dnsServers ||
            snapshot.generation != requestedGeneration || !snapshot.isProbeReady || snapshot.captive ||
            snapshot.transport != UpstreamTransport.Cellular
        ) return false
        val (username, password) = upstreamLogin()
        val proxy = SubscriptionFetchProxy(socksConnectHost(), probePort, username, password)
        val transitionCurrent: suspend () -> Boolean = {
            val bundle = repository.getBundle()
            val selectedMatches = selectPersonalSubscriptionUrl(packageName, runtimePolicy, connectionMode,
                bundle, storageId) == url
            val admittedSubscription = SubscriptionTunnelAccess.denial(bundle, storageId,
                System.currentTimeMillis(), runtimePolicy) == null
            coroutineContext.ensureActive()
            selectedMatches && admittedSubscription &&
                generation == requestedGeneration &&
                (OlcboxVpnState.status.value is VpnStatus.Connecting ||
                    OlcboxVpnState.status.value is VpnStatus.Reconnecting) &&
                vpnInterface === pfd && pfd.fileDescriptor.valid() &&
                currentNetwork == network && findActiveUpstreamNetwork() == network &&
                snapshots.current(network) == snapshot && snapshots.networkFor(snapshot) == network &&
                snapshots.current(network)?.isProbeReady == true &&
                snapshots.current(network)?.captive == false &&
                connectedStorageId == storageId && connectedPersonalSubscriptionUrl == url
        }
        val current: suspend () -> Boolean = {
            transitionCurrent() && OlcboxVpnState.status.value is VpnStatus.Connecting &&
                isTunBridgeReadyForActiveTransport() && isActiveTransportRunning() &&
                activeCorePort == frontPort && activeCoreBackendPort == backendPort &&
                routingSettings.needsRules == routingFront && ordinaryRoutingFacts == facts
        }
        // Deadline covers assessment, cache read and existing cancellable handoff.
        return withTimeoutOrNull(90_000L) {
            val candidate = PersonalColdRestrictedStartup(
                isCurrent = current,
                physicalRound = { BoundPhysicalNetworkProbe().collect(network,
                    snapshot.generation, snapshot.networkId, snapshot.revision) },
                selectedPath = { selectedPathProbe.collect(true, isActiveTransportRunning(),
                    isTunBridgeReadyForActiveTransport(), proxy) }
            ).assess(snapshot) ?: return@withTimeoutOrNull false
            if (!current()) return@withTimeoutOrNull false
            val cached = withContext(Dispatchers.IO) { personalBootstrapReader(url) }
                ?: return@withTimeoutOrNull false
            if (!current() || !cached.isUsableAt(System.currentTimeMillis())) return@withTimeoutOrNull false
            // Cached room only supplies bootstrap material; fresh PoP and server lease
            // admission are still enforced by the unchanged managed-join handoff.
            restrictedNetworkCoordinator?.cancel()
            addLog("Cold startup: restricted cellular path suspected")
            handoffToPersonal(candidate, cached, url, transitionCurrent)
        } ?: false
    }

    /**
     * Start the transport for [location], branching on its kind: olcrtc runs the
     * engine in this process ([startMobile]), which routes by itself under a
     * bypass; vless/hysteria2/xhttp start a sing-box / Xray core subprocess via
     * [startCore]. Both leave the hev bridge pointed at the right SOCKS port
     * (via [activeCorePort]).
     */
    private suspend fun startTransport(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean,
        reuseBridge: Boolean = false,
        personalClaimsJson: String? = null,
        personalAdmissionCurrent: (suspend () -> Boolean)? = null
    ): Boolean {
        runtimePolicy.denial(location.kind)?.let { denial ->
            if (setErrorOnFailure) setStatus(VpnStatus.Error(denial))
            return false
        }
        // Preserve the provider's exact Xray graph. A rule-based device policy
        // is applied by a separate local front, not by rewriting that graph.
        if (location.xrayConfig != null) {
            val routing = try {
                routingFor(upstream, location.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Route-set failures must not silently turn a split tunnel into
                // Global. Keep exception details (potentially file contents or
                // credentials) out of user-visible logs.
                val message = "Xray subscription routing unavailable"
                addLog(message)
                if (setErrorOnFailure) {
                    setStatus(VpnStatus.Error(message))
                    updateNotification("Connection failed")
                }
                return false
            }
            if (personalAdmissionCurrent != null && !personalAdmissionCurrent()) return false
            return startCore(location, setErrorOnFailure, routing, reuseBridge)
        }
        val routing = routingFor(upstream, location.kind)
        if (personalAdmissionCurrent != null && !personalAdmissionCurrent()) return false
        return if (location.kind == LocationKind.Olcrtc) {
            activeCorePort = null
            activeCoreLogin = null
            startMobile(location, upstream, requestedGeneration, setErrorOnFailure, routing, personalClaimsJson,
                personalAdmissionCurrent)
        } else {
            startCore(location, setErrorOnFailure, routing, reuseBridge)
        }
    }

    /**
     * Start a sing-box (reality/hy2) or Xray (xhttp) core.
     *
     * In proxy mode the core listens on [socksListenPort], because that is the
     * endpoint the app tells other apps to use — putting the core anywhere else
     * leaves that promised port dead — and, as before, without a login. In tun
     * mode nothing outside talks to it, so it takes a port the kernel hands out
     * for this start and demands the app's own login: tun2socks follows
     * [activeCorePort] and sends [activeCoreLogin]. It used to be 10810, open,
     * which any app on the phone could use to leave through the tunnel or to
     * read its exit address (see [SocksLogin]).
     */
    private suspend fun startCore(
        location: LocationConfig,
        setErrorOnFailure: Boolean,
        routing: Routing,
        reuseBridge: Boolean
    ): Boolean {
        val tun = connectionMode == AndroidConnectionMode.Tun
        if (location.xrayConfig != null) {
            return startExactXrayCore(location, setErrorOnFailure, routing, reuseBridge)
        }
        return try {
            val port = TunBridgePortPolicy.corePortForStart(
                tunMode = tun,
                reuseBridge = reuseBridge,
                bridgePort = tun2socksBackendPort,
                freshPort = if (tun && !reuseBridge) freeLoopbackPort() else null,
                proxyPort = socksListenPort
            ) ?: error("No valid core SOCKS port")
            val login = if (tun) SocksLogin.of(socksUsername, socksPassword) else null
            val raw = location.rawLink ?: error("core location has no link")
            val spec = LinkParser.parse(raw) ?: error("unparseable core link")
            stopCoreProcesses()
            check(awaitRetiredCorePorts()) { "Previous core listener still active" }
            waitForSocksPortReleased(port, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(port)) error("Core SOCKS port $port is still in use")
            val label: String
            // Which core to ask afterwards, decided here rather than re-derived
            // from the label — the failure path should not have to parse a string
            // we build for humans.
            val diagnose: () -> String
            // Which processes must be alive once the port answers. A port that
            // answers proves nothing about who answers.
            val alive: () -> Boolean
            val fronted = routing is Routing.Rules && connectionMode == AndroidConnectionMode.Tun
            if (spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp) {
                if (fronted) {
                    // Xray does not route; sing-box does, so it goes in front.
                    // Both ports are loopback and both demand the login: the one
                    // behind the front is as reachable from other apps as the
                    // front itself.
                    val xrayPort = freeLoopbackPort()
                    activeCoreBackendPort = xrayPort
                    xrayCore.start(
                        XrayConfig.buildXhttp(
                            spec,
                            socksPort = xrayPort,
                            verboseLogs = verboseDebugLogs,
                            login = login
                        )
                    )
                    singBoxCore.start(
                        SingBoxConfig.buildSocksChain(
                            xrayPort,
                            socksPort = port,
                            username = login?.username.orEmpty(),
                            password = login?.password.orEmpty(),
                            routing = routing,
                            verboseLogs = verboseDebugLogs,
                            login = login
                        )
                    )
                    label = "sing-box front + Xray/xhttp"
                    diagnose = { singBoxCore.diagnostics() + "\n" + xrayCore.diagnostics() }
                    alive = { singBoxCore.isRunning() && xrayCore.isRunning() }
                } else {
                    if (routing is Routing.Rules) addLog("Routing: proxy mode keeps xhttp global")
                    xrayCore.start(
                        XrayConfig.buildXhttp(spec, socksPort = port, verboseLogs = verboseDebugLogs, login = login)
                    )
                    label = "Xray/xhttp"
                    diagnose = xrayCore::diagnostics
                    alive = xrayCore::isRunning
                }
            } else {
                singBoxCore.start(
                    SingBoxConfig.build(
                        spec,
                        socksPort = port,
                        routing = routing,
                        verboseLogs = verboseDebugLogs,
                        login = login
                    )
                )
                label = "sing-box/${location.kind}"
                diagnose = singBoxCore::diagnostics
                alive = singBoxCore::isRunning
            }
            activeCorePort = port
            activeCoreLogin = login
            activeCoreChainRequiresBoth = fronted && spec is OutboundSpec.Vless && spec.transport is TransportSpec.Xhttp
            if (!waitForSocksPortOpen(port, MOBILE_READY_TIMEOUT_MS) || !alive()) {
                // The core's own account of what went wrong, which otherwise sits
                // in a cache file only root can read. Without it this branch says
                // a port did not open and nothing about why, and that is all a
                // user has ever been able to report.
                addLog(diagnose())
                error("$label SOCKS not ready on $port")
            }
            coroutineContext.ensureActive()
            addLog("$label ready on $socksListenHost:$port")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("core start canceled")
                stopCoreProcesses()
            }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Core transport failed"
            addLog("core start failed: $msg")
            stopCoreProcesses()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(msg))
                updateNotification("Connection failed")
            }
            false
        }
    }

    private suspend fun startExactXrayCore(
        location: LocationConfig,
        setErrorOnFailure: Boolean,
        routing: Routing,
        reuseBridge: Boolean
    ): Boolean {
        return try {
            val ingressPort = TunBridgePortPolicy.corePortForStart(
                tunMode = connectionMode == AndroidConnectionMode.Tun,
                reuseBridge = reuseBridge,
                bridgePort = tun2socksBackendPort,
                freshPort = if (!reuseBridge) freeLoopbackPort() else null,
                proxyPort = socksListenPort
            ) ?: throw XrayJsonRuntimeConfig.Rejected(XrayJsonRuntimeConfig.Reason.INVALID_PORT)
            val login = SocksLogin.of(socksUsername, socksPassword)
            val backendPort = if (routing is Routing.Rules) {
                (1..5).asSequence().map { freeLoopbackPort() }
                    .firstOrNull { it != ingressPort }
                    ?: throw XrayJsonRuntimeConfig.Rejected(XrayJsonRuntimeConfig.Reason.INVALID_PORT)
            } else null
            // Build and validate both configs before stopping an existing
            // transport. A missing rule asset never starts either core.
            val plan = ExactXrayChainPlan.build(
                config = location.xrayConfig!!,
                ingressPort = ingressPort,
                backendPort = backendPort,
                login = login,
                routing = routing,
                tunMode = connectionMode == AndroidConnectionMode.Tun
            )
            stopCoreProcesses()
            check(awaitRetiredCorePorts()) { "Previous core listener still active" }
            waitForSocksPortReleased(ingressPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            check(!isLocalSocksPortOpen(ingressPort))
            if (backendPort != null) {
                waitForSocksPortReleased(backendPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
                check(!isLocalSocksPortOpen(backendPort))
            }
            activeCoreBackendPort = backendPort
            xrayCore.start(plan.xrayConfig.toString())
            if (backendPort != null &&
                (!waitForSocksPortOpen(backendPort, MOBILE_READY_TIMEOUT_MS) || !xrayCore.isRunning())
            ) {
                addLog(xrayCore.redactedDiagnostics())
                error("Xray backend not ready")
            }
            plan.frontConfig?.let { singBoxCore.start(it) }
            activeCorePort = ingressPort
            activeCoreLogin = login
            activeCoreChainRequiresBoth = plan.frontConfig != null
            if (!waitForSocksPortOpen(ingressPort, MOBILE_READY_TIMEOUT_MS) ||
                !xrayCore.isRunning() ||
                (plan.frontConfig != null && !singBoxCore.isRunning())
            ) {
                addLog("Xray subscription chain not ready")
                error("Xray subscription chain not ready")
            }
            coroutineContext.ensureActive()
            addLog(if (plan.frontConfig != null) "Xray subscription routing ready" else "Xray subscription profile ready")
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopCoreProcesses() }
            throw e
        } catch (e: Exception) {
            // Exceptions from process/config/core can contain credentials. Only
            // our closed reason enum is safe; never forward arbitrary messages.
            val message = when (e) {
                is XrayJsonRuntimeConfig.Rejected,
                is ExactXrayChainPlan.UnsupportedProviderRouting -> e.message!!
                else -> "Xray subscription transport failed"
            }
            addLog(message)
            stopCoreProcesses()
            if (setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification("Connection failed")
            }
            false
        }
    }

    private fun stopCoreProcesses() {
        activeCorePort?.let(retiredCorePorts::add)
        activeCoreBackendPort?.let(retiredCorePorts::add)
        singBoxCore.stopNow()
        xrayCore.stopNow()
        activeCorePort = null
        activeCoreBackendPort = null
        activeCoreLogin = null
        activeCoreChainRequiresBoth = false
    }

    private suspend fun awaitRetiredCorePorts(): Boolean {
        for (port in retiredCorePorts.toList()) {
            waitForSocksPortReleased(port, SOCKS_RELEASE_TIMEOUT_MS)
            if (isLocalSocksPortOpen(port)) return false
            retiredCorePorts.remove(port)
        }
        return true
    }

    /**
     * A loopback port nothing holds at this moment, from the kernel's ephemeral
     * range. Another process can take it before the core binds it; the start then
     * fails the way any start does, and the next one draws again. Should the
     * kernel not hand one out, the old fixed port: the login still guards it.
     */
    private fun freeLoopbackPort(): Int = runCatching {
        ServerSocket(0, 1, InetAddress.getByName(AndroidSocksProxySettings.DEFAULT_HOST)).use { it.localPort }
    }.getOrDefault(SingBoxConfig.SINGBOX_SOCKS_PORT)

    /**
     * The login the SOCKS server behind the tun demands, as (username, password):
     * the core's on the core path, olcRTC's own pair otherwise, ("", "") for none.
     * Everything that talks to that server (tun2socks, the tunnel check, the
     * channel proxy) asks here, so none of them can disagree with what the server
     * was started with; see [HevTunnelConfig] for what a disagreement looks like.
     */
    private fun upstreamLogin(): Pair<String, String> =
        if (generalConsumer?.hasOwnedRuntime() == true && generalOptions != null) {
            generalOptions!!.let { it.socksUsername to it.socksPassword }
        } else if (activeCorePort != null) {
            activeCoreLogin?.let { it.username to it.password } ?: ("" to "")
        } else {
            socksUsername to socksPassword
        }

    /** Poll until the given local SOCKS port accepts connections, or timeout. */
    private suspend fun waitForSocksPortOpen(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isLocalSocksPortOpen(port)) return true
            delay(SOCKS_RELEASE_POLL_MS)
        }
        return isLocalSocksPortOpen(port)
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean,
        routing: Routing,
        personalClaimsJson: String?,
        personalAdmissionCurrent: (suspend () -> Boolean)? = null
    ): Boolean {
        personalTransportEpoch.incrementAndGet()
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        return try {
            val olcrtc = olcrtc ?: throw IllegalStateException("Experimental transport unavailable")
            installMobileCallbacks()
            val targetSocksPort = socksListenPort
            val deviceId = deviceIdentityProvider.hwid()
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (personalAdmissionCurrent != null && !personalAdmissionCurrent()) return false
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            waitForJitsiRoomCleanup(config.bypassProvider)
            if (personalAdmissionCurrent != null && !personalAdmissionCurrent()) return false
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            // The engine routes by itself (internal/route), as it does on iOS:
            // the region's lists as rules, or none under Global.
            val baseDirectRules = OlcrtcDirectRules.forRouting(routing)
            // The QA receipt must never inherit a regional .ru/control-plane DIRECT rule.
            // A negative-only rule is valid for personal Global with the packaged parser.
            val directRules = if (packageName == PERSONAL_TEST_PACKAGE && personalClaimsJson != null) {
                personalControlPlaneRules(baseDirectRules)
            } else baseDirectRules
            val udpDirectRules = if (packageName == PERSONAL_TEST_PACKAGE && personalClaimsJson != null) {
                baseDirectRules.lineSequence().any { it.isNotBlank() && !it.startsWith("tunnel-domain:") }
            } else directRules.isNotEmpty()
            configureMobileTransport(config, upstream, directRules, udpDirectRules)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=<redacted>"
            )
            lastMobileProvider = config.bypassProvider
            olcrtc.setProvider(config.bypassProvider)
            olcrtc.setRoom(config.id)
            olcrtc.setDeviceID(deviceId)
            olcrtc.setKey(config.key)
            olcrtc.setSocksPort(targetSocksPort.toLong())
            olcrtc.setSocksCredentials(socksUsername, socksPassword)
            if (personalClaimsJson != null) {
                if (personalAdmissionCurrent != null && !personalAdmissionCurrent()) return false
                PersonalManagedJoinStarter.start(olcrtc, personalClaimsJson)
            } else {
                olcrtc.start()
            }
            olcrtc.waitReady(OLCRTC_READY_TIMEOUT_MS)
            if (requestedGeneration != generation) {
                addLog("olcRTC start superseded")
                withContext(NonCancellable) {
                    unbindProcessFromNetwork()
                    stopMobileAndWait()
                }
                return false
            }
            coroutineContext.ensureActive()
            if (personalAdmissionCurrent != null && !personalAdmissionCurrent()) return false
            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            markRtcConnected()
            if (keepProcessBound) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            // Managed admission errors may embed a token/room in a native
            // exception. The personal path never forwards that text to logs.
            val message = if (personalClaimsJson != null) "Personal transport unavailable"
                else e.message ?: "Transport failed"
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification("Connection failed")
            }
            false
        } finally {
            if (!keepProcessBound || olcrtc?.isRunning() != true) {
                unbindProcessFromNetwork()
            }
        }
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileTransport(
        location: LocationConfig, upstream: Network?, directRules: String, udpDirectRules: Boolean,
    ) {
        val config = location.normalized()
        val olcrtc = olcrtc ?: throw IllegalStateException("Experimental transport unavailable")
        olcrtc.setTransport(config.transport)
        // The upstream network's own resolvers first, the public operator
        // behind them: some mobile networks answer only their own (olcbox#16).
        // Direct names under a bypass are resolved on the same list.
        olcrtc.setDNS(upstreamDnsList(upstream))
        olcrtc.setSocksListenHost(socksListenHost)
        olcrtc.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
        // Set on every start, the empty text included: the one Runtime keeps
        // its rules across Starts, so Global after a bypass would keep them.
        // Parsed here, so a list the engine refuses fails this start loudly.
        olcrtc.setDirectRules(directRules)
        if (directRules.isNotEmpty()) {
            addLog("Routing: olcRTC takes ${directRules.count { it == '\n' }} direct rules")
        }
        // Off on a Jitsi room, which has no datagram lane, unless a bypass gives
        // the relay direct flows to carry (OlcRtcUdpRelay). Set on every start,
        // for the same reason as the rules: a WB room after a Jitsi one would
        // otherwise run with no UDP.
        olcrtc.setUDP(
            OlcRtcUdpRelay.enabled(config.bypassProvider, config.transport, directRules = udpDirectRules)
        )
    }

    private var tun2socksFdDuplicator: (ParcelFileDescriptor) -> ParcelFileDescriptor = {
        ParcelFileDescriptor.dup(it.fileDescriptor)
    }
    private var tun2socksThreadLauncher: (() -> Unit) -> Thread = { work ->
        thread(name = "OlcboxTun2Socks", isDaemon = true, block = work)
    }
    private var tun2socksNativePreparer: () -> Long = { prepareTun2socksNative() }
    private var tun2socksDnsScopePreparer: () -> Long = { prepareTunDnsScopeNative() }
    private var tun2socksDnsScopeCloser: (Long) -> Boolean = { closeTunDnsScopeNative(it) }
    private var tun2socksNativeAbandoner: (Long) -> Boolean = { releasePreparedTun2socksNative(it) }
    private var tun2socksNativeRetirementWitness: (Long) -> Boolean = { isTun2socksNativeRetired(it) }
    private var tun2socksNativeStopper: (Long) -> Boolean = { stopTun2socksNative(it) }

    /** The held parent TUN, not a replaceable native worker, owns fake-DNS aliases. */
    private fun dnsScopeForTun(descriptor: ParcelFileDescriptor): Long = synchronized(this) {
        if (vpnInterface !== descriptor || !descriptor.fileDescriptor.valid()) return@synchronized 0L
        tunDnsScope?.let { return@synchronized if (it.descriptor === descriptor) it.id else 0L }
        val id = tun2socksDnsScopePreparer()
        if (id > 0L) tunDnsScope = TunDnsScope(descriptor, id)
        id
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        personalBridgeEpoch.incrementAndGet()
        var pendingFd: PendingTunDescriptor? = null
        var reservedEpoch = 0L
        var launched = false
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification("Tunnel failed")
                return false
            }

            val corePort = activeCorePort
            val configFile = writeTun2socksConfig(corePort)
            val dnsScopeId = dnsScopeForTun(pfd)
            check(dnsScopeId > 0L) { "VPN DNS owner is unavailable" }
            val nativeEpoch = tun2socksNativePreparer()
            check(nativeEpoch > 0L) { "Previous native bridge is not retired" }
            reservedEpoch = nativeEpoch
            tun2socksNativeEpoch = nativeEpoch
            val ownedFd = PendingTunDescriptor(tun2socksFdDuplicator(pfd))
            pendingFd = ownedFd
            tun2socksBackendPort = corePort ?: socksListenPort
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = tun2socksThreadLauncher {
                var linkageDescriptorClosed = true
                try {
                    val nativeFd = ownedFd.detachForNative()
                    val result = try {
                        // JNI owns and closes the detached fd, including native error returns.
                        startTun2socksNative(configFile.absolutePath, nativeFd, nativeEpoch, dnsScopeId)
                    } catch (notEntered: UnsatisfiedLinkError) {
                        // VM linkage failed before JNI could take ownership.
                        linkageDescriptorClosed = runCatching { ParcelFileDescriptor.adoptFd(nativeFd).close() }.isSuccess
                        throw notEntered
                    }
                    if (OlcboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    // Release the exact PREPARED reservation even when detach
                    // or descriptor cleanup failed before JNI. Never abort RUNNING.
                    val pendingClosed = runCatching { ownedFd.closePending() }.isSuccess
                    val nativeRetired = runCatching {
                        tun2socksNativeAbandoner(nativeEpoch) || tun2socksNativeRetirementWitness(nativeEpoch)
                    }.getOrDefault(false)
                    if (pendingClosed && linkageDescriptorClosed && nativeRetired) synchronized(this@OlcboxVpnService) {
                        if (tun2socksNativeEpoch == nativeEpoch) {
                            tun2socksStarted = false
                            tun2socksStopRequested = false
                            tun2socksNativeEpoch = 0L
                        }
                    }
                    else addLog("tun2socks cleanup is still pending")
                    val exitedThread = Thread.currentThread()
                    if (tun2socksThread == exitedThread) {
                        val generalBinding = generalActiveBinding
                        val generalExit = retireGeneralForBridgeExit(exitedThread, tun2socksBackendPort)
                        tun2socksBackendPort = null
                        personalBridgeEpoch.incrementAndGet()
                        scope.launch {
                            tunnelMutex.withLock {
                                if (generalExit != null && generalBinding != null &&
                                    generalActiveBinding === generalBinding && !stopCleanupPending &&
                                    generation == generalBinding.serviceGeneration && vpnInterface === generalBinding.tun &&
                                    OlcboxVpnState.status.value !is VpnStatus.Stopping) {
                                    scheduleGeneralRecovery(generalBinding.network as Network, generalBinding.snapshot, generalExit)
                                }
                                synchronized(this@OlcboxVpnService) {
                                    if (tun2socksThread === exitedThread && !stopCleanupPending &&
                                        personalOrdinaryLocation != null &&
                                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working
                                    ) {
                                        invalidatePersonalProof()
                                        setStatus(VpnStatus.Reconnecting)
                                        updateNotification("Ограниченная сеть · Проверяем")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            launched = true
            true
        } catch (e: Exception) {
            if (reservedEpoch == 0L) synchronized(this) {
                if (tun2socksNativeEpoch == reservedEpoch) {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                    tun2socksBackendPort = null
                    tun2socksNativeEpoch = 0L
                }
            }
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification("Tunnel failed")
            false
        } finally {
            // Also release on VM/thread creation Errors, without swallowing them.
            if (!launched) {
                val pendingClosed = runCatching { pendingFd?.closePending() }.isSuccess
                val released = reservedEpoch > 0L &&
                    runCatching { tun2socksNativeAbandoner(reservedEpoch) }.getOrDefault(false)
                if (pendingClosed && released) synchronized(this) {
                    if (tun2socksNativeEpoch == reservedEpoch) {
                        tun2socksStarted = false
                        tun2socksStopRequested = false
                        tun2socksBackendPort = null
                        tun2socksNativeEpoch = 0L
                    }
                }
            }
        }
    }

    /** Keep a managed duplicate until the worker actually enters the JNI handover. */
    private class PendingTunDescriptor(private val duplicate: ParcelFileDescriptor) {
        private var pending = true

        @Synchronized fun detachForNative(): Int {
            check(pending) { "TUN descriptor already transferred" }
            val fd = duplicate.detachFd()
            pending = false
            return fd
        }

        @Synchronized fun closePending() {
            if (!pending) return
            duplicate.close()
            pending = false
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Apollo.RGA VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(HevTunnelConfig.MAPDNS_ADDRESS)
                .setBlocking(true)

            if (!applySplitTunneling(builder)) return null

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "VPN establish failed"))
            updateNotification("VPN tunnel error")
            null
        }
    }

    private fun applySplitTunneling(builder: Builder): Boolean {
        return when (splitTunnelMode) {
            AndroidSplitTunnelMode.AllApps -> {
                if (!excludeOwnAppOrFail(builder)) return false
                addLog("Split tunneling: all apps use TUN")
                true
            }

            AndroidSplitTunnelMode.ProxySelected -> {
                val packages = splitTunnelProxyApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()

                if (packages.isEmpty()) {
                    addLog("Split tunneling proxy list is empty")
                    setStatus(VpnStatus.Error("Select apps for split tunneling"))
                    updateNotification("Split tunneling error")
                    return false
                }

                val applied = packages.count { addAllowedApp(builder, it) }
                if (applied == 0) {
                    addLog("Split tunneling has no valid proxy apps")
                    setStatus(VpnStatus.Error("Selected apps are unavailable"))
                    updateNotification("Split tunneling error")
                    false
                } else {
                    addLog("Split tunneling: $applied selected apps use TUN")
                    true
                }
            }

            AndroidSplitTunnelMode.BypassSelected -> {
                if (!excludeOwnAppOrFail(builder)) return false
                val applied = splitTunnelBypassApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .count { addDisallowedApp(builder, it) }

                if (applied == 0) {
                    addLog("Split tunneling: no selected apps bypass TUN")
                } else {
                    addLog("Split tunneling: $applied selected apps bypass TUN")
                }
                true
            }
        }
    }

    private fun excludeOwnAppOrFail(builder: Builder): Boolean = SelfAppExclusionGate.apply(
        excludeSelf = { addDisallowedApp(builder, packageName, "this app") },
        onFailure = {
            addLog("VPN tunnel refused: this app could not be excluded from TUN")
            setStatus(VpnStatus.Error("VPN app bypass failed"))
            updateNotification("VPN tunnel error")
        }
    )

    private fun addAllowedApp(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to route $targetPackage through TUN: ${it.message}")
            false
        }
    }

    private fun addDisallowedApp(
        builder: Builder,
        targetPackage: String,
        label: String = targetPackage
    ): Boolean {
        return runCatching {
            builder.addDisallowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to bypass $label from TUN: ${it.message}")
            false
        }
    }

    private fun writeTun2socksConfig(corePort: Int?): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)
        file.writeText(
            HevTunnelConfig.yaml(
                mtu = TUN_MTU,
                ipv4 = TUN_IPV4_ADDRESS,
                socksAddress = socksConnectHost(),
                socksPort = socksListenPort,
                corePort = corePort,
                username = upstreamLogin().first,
                password = upstreamLogin().second
            )
        )
        return file
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        watchdogJob = scope.launch {
            while (isActive && (OlcboxVpnState.status.value is VpnStatus.Connected ||
                    ((personalOrdinaryLocation != null || generalActiveBinding != null) &&
                        OlcboxVpnState.status.value is VpnStatus.Reconnecting))) {
                delay(WATCHDOG_INTERVAL_MS)
                if (tunnelMutex.withLock { denyConnectedSubscriptionIfNeeded() }) return@launch
                if (generalActiveBinding != null) {
                    // A proxy body is not the ordinary/full-TUN health receipt. Do not run ordinary
                    // restart logic or promote Connected while GENERAL qualification remains pending.
                    val binding = generalActiveBinding
                    val upstream = findActiveUpstreamNetwork()
                    val physical = upstream?.let { physicalNetworkSnapshots?.current(it) }
                    // Cache age gates construction, not the lifetime of an already proved native session.
                    val keyCurrent = binding == null || try {
                        binding.matchesKey(AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    val health = binding?.let { generalConsumer?.observeTransportHealth(it) }
                    val reason = binding?.let { GeneralNetworkRecoveryPolicy.assess(it.network, it.snapshot,
                        upstream, physical, generalConsumer?.isRunning() == true, isTunBridgeReadyForActiveTransport(),
                        health?.health ?: GeneralTransportHealth33.UNKNOWN) }
                    // Do not show a working badge during the existing finite
                    // reconnect allowance. This changes presentation only; the
                    // native lease, recovery timer and control authority stay intact.
                    synchronized(this@OlcboxVpnService) {
                        if (binding != null && generalActiveBinding === binding &&
                            generation == binding.serviceGeneration && vpnInterface === binding.tun &&
                            currentNetwork == binding.network && GeneralNetworkRecoveryPolicy.sameActivePath(binding.snapshot, physicalNetworkSnapshots?.current(binding.network as Network)) &&
                            !stopCleanupPending && !generalLifecycleRetired) {
                            OlcboxVpnState.setGeneralChannelHealth(keyCurrent && reason == GeneralNetworkRecoveryPolicy.Reason.Healthy)
                        }
                    }
                    if (generalRecoveryJob?.isActive != true && binding != null &&
                        (!keyCurrent || GeneralNetworkRecoveryPolicy.recoveryRequired(reason, health?.recoveryDue ?: true))) {
                        Log.d("RgaGeneralRuntime", "recoveryOrigin=WATCHDOG reason=${if (!keyCurrent) "KeyChanged" else reason?.name ?: "BindingAbsent"}")
                        Log.d("RgaGeneralRuntime", "snapshotDelta=${GeneralNetworkRecoveryPolicy.snapshotDelta(binding.snapshot, physical).name}")
                        generalConsumer?.captureRecoveryTicket(binding)?.let { captured ->
                            scheduleGeneralRecovery(upstream ?: binding.network as Network, physical ?: binding.snapshot, captured)
                        }
                    }
                    continue
                }
                if (personalOrdinaryLocation != null && OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc) {
                    if (personalProofJob?.isActive != true &&
                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Checking
                    ) startPersonalProofLoop()
                    val upstream = findActiveUpstreamNetwork()
                    if (upstream != null && physicalNetworkSnapshots?.current(upstream)?.transport == UpstreamTransport.Wifi) {
                        updateUnderlyingNetwork(upstream)
                        schedulePersonalReturn()
                    }
                    if (!isActiveTransportRunning() || !isTunBridgeReadyForActiveTransport()) {
                        val observedGeneration = generation
                        val observedPfd = vpnInterface
                        tunnelMutex.withLock {
                            if (generation == observedGeneration && observedPfd != null &&
                                vpnInterface === observedPfd && personalOrdinaryLocation != null
                            ) {
                                recoverStoppedPersonalTransport(observedPfd, observedGeneration)
                            }
                        }
                        return@launch
                    }
                    continue
                }
                when {
                    !isActiveTransportRunning() -> {
                        val what = activeTransportLabel()
                        addLog("Watchdog: $what stopped")
                        requestTransportRecovery("$what stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && !isLocalSocksPortOpen(socksListenPort) -> {
                        addLog("Watchdog: SOCKS port is not accepting connections")
                        requestTransportRecovery("SOCKS port unavailable", fullRestart = true)
                        return@launch
                    }
                }

                val upstream = findActiveUpstreamNetwork()
                if (upstream == null) {
                    addLog("Watchdog: no upstream network")
                    requestTransportRecovery("No upstream network", fullRestart = false)
                    return@launch
                }

                val snapshot = physicalNetworkSnapshots?.current(upstream)
                val networkChanged = currentNetwork != upstream
                val dnsChanged = snapshot?.dnsServers?.let { dns ->
                    lastConnectedOrdinaryDns?.let { it != dns }
                } == true
                if (networkChanged || dnsChanged) {
                    val decision = snapshot?.let {
                        OrdinaryNetworkRefreshPolicy.decide(
                            eventIsSelected = true,
                            sameNetwork = upstream == lastConnectedOrdinaryNetwork &&
                                (lastConnectedOrdinaryNetworkId == null || it.networkId == lastConnectedOrdinaryNetworkId),
                            previousTransport = lastConnectedOrdinaryTransport,
                            nextTransport = it.transport,
                            previousDns = lastConnectedOrdinaryDns,
                            nextDns = it.dnsServers,
                            status = OlcboxVpnState.status.value
                        )
                    }
                    if (networkChanged) updateUnderlyingNetwork(upstream)
                    if (decision == NetworkRefreshDecision.Keep ||
                        (decision == NetworkRefreshDecision.AwaitFacts && !networkChanged)
                    ) continue
                    addLog("Watchdog: upstream changed")
                    requestTransportRecovery("Upstream network changed", fullRestart = false)
                    return@launch
                }

                if (mode == AndroidConnectionMode.Tun && isTunTrafficStalled()) {
                    addLog("Watchdog: TUN traffic has no upstream response")
                    // A core whose SOCKS port opened is reported as connected, and
                    // the port opens before the core has touched the server — so
                    // "connected but nothing loads" and "the exit is unreachable"
                    // look identical from here. The core knows which it is.
                    addLog(activeCoreDiagnostics())
                    physicalNetworkSnapshots?.current(upstream)?.let { snapshot ->
                        restrictedNetworkCoordinator?.onNetworkEvent(upstream, snapshot)
                    }
                    requestTransportRecovery("TUN traffic stalled", fullRestart = false,
                        delayMs = personalRestrictedRecoveryDelay(0L, snapshot?.transport))
                    return@launch
                }
            }
        }
    }

    private data class PersonalProofSnapshot(
        val pfd: ParcelFileDescriptor,
        val network: Network,
        val physical: PhysicalNetworkSnapshot,
        val bridgeThread: Thread,
        val location: LocationConfig,
        val ordinary: LocationConfig,
        val bootstrap: PersonalBootstrapV1,
        val storageId: String,
        val url: String,
        val phaseEpoch: Long,
        val stamp: PersonalTunProof.RuntimeStamp,
    )

    private fun invalidatePersonalProof(cancelJob: Boolean = true) {
        personalProofEpoch.incrementAndGet()
        if (cancelJob) {
            personalProofJob?.cancel()
            personalProofJob = null
        }
        personalWorkingNetwork = null
        personalWorkingSnapshot = null
        personalRefreshSnapshot = null
        OlcboxVpnState.personalUpdateTicket = null
    }

    /** A changed/lost selected snapshot retires the old receipt before network recovery runs. */
    private fun invalidateWorkingPersonalProofForNetwork(
        network: Network, current: PhysicalNetworkSnapshot?,
    ) {
        val working = personalWorkingSnapshot ?: return
        if (network != personalWorkingNetwork || working == current) return
        invalidatePersonalProof()
        if (OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Ограниченная сеть · Проверяем")
            startPersonalProofLoop()
        }
    }

    private fun startPersonalProofLoop() {
        invalidatePersonalProof()
        personalProofJob = scope.launch {
            while (isActive) {
                val verified = try {
                    attemptPersonalTunProof()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (packageName == PERSONAL_TEST_PACKAGE) {
                        Log.d(TAG, "Personal TUN proof exception (${error.javaClass.simpleName})")
                    }
                    false
                }
                // No tight poll against either the companion or C counters. A failure
                // stays Checking; a previously accepted receipt is refreshed at most every 45 s.
                delay(if (verified) PERSONAL_PROOF_SUCCESS_INTERVAL_MS else PERSONAL_PROOF_RETRY_INTERVAL_MS)
                if (personalOrdinaryLocation == null ||
                    OlcboxVpnState.personalTunnel.value.activePath != PersonalActivePath.OlcRtc
                ) break
            }
        }
    }

    /** Snapshot only under tunnelMutex; never hold it across the 20-second companion IPC. */
    private suspend fun capturePersonalProofSnapshot(): PersonalProofSnapshot? {
        val pfd = vpnInterface ?: return null
        val network = findActiveUpstreamNetwork() ?: return null
        val physical = physicalNetworkSnapshots?.current(network) ?: return null
        val bridgeThread = tun2socksThread ?: return null
        val location = OlcboxVpnState.activeLocation ?: return null
        val ordinary = personalOrdinaryLocation ?: return null
        val bootstrap = personalActiveBootstrap ?: return null
        val storageId = connectedStorageId ?: return null
        val url = connectedPersonalSubscriptionUrl ?: return null
        val phaseEpoch = OlcboxVpnState.personalPhaseEpoch()
        val stamp = PersonalTunProof.RuntimeStamp(
            generation = generation,
            networkId = physical.networkId,
            snapshotRevision = physical.revision,
            transportEpoch = personalTransportEpoch.get(),
            bridgeEpoch = personalBridgeEpoch.get(),
            proofEpoch = personalProofEpoch.incrementAndGet(),
        )
        val snapshot = PersonalProofSnapshot(pfd, network, physical, bridgeThread, location,
            ordinary, bootstrap, storageId, url, phaseEpoch, stamp)
        return snapshot.takeIf { personalProofCurrent(it) }
    }

    /** Called under tunnelMutex, including immediately before C arm and live C read. */
    private suspend fun personalProofCurrent(snapshot: PersonalProofSnapshot): Boolean {
        if (!personalProofCurrentNow(snapshot)) return false
        val bundle = repository.getBundle()
        coroutineContext.ensureActive()
        return personalProofCurrentNow(snapshot) &&
            selectPersonalSubscriptionUrl(packageName, runtimePolicy, connectionMode, bundle,
                snapshot.storageId) == snapshot.url &&
            SubscriptionTunnelAccess.denial(bundle, snapshot.storageId,
                System.currentTimeMillis(), runtimePolicy) == null &&
            bundle.activeLocationId == snapshot.storageId &&
            bundle.locations.singleOrNull { it.storageId == snapshot.storageId }?.subscriptionUrl == snapshot.url
    }

    private fun nextPersonalObserverEpoch(): Long = processPersonalObserverEpoch.incrementAndGet()

    /** The non-suspending half is repeated inside synchronized(this) against cleanup/Stop. */
    private fun personalProofCurrentNow(
        snapshot: PersonalProofSnapshot, requireProofEpoch: Boolean = true,
    ): Boolean = personalProofCurrentNow(snapshot, requireProofEpoch, null)

    private fun personalProofCurrentNow(
        snapshot: PersonalProofSnapshot, requireProofEpoch: Boolean,
        observation: PersonalUpdateDiagnosticOperation?,
    ): Boolean {
        val physical = physicalNetworkSnapshots?.current(snapshot.network)
        val phase = OlcboxVpnState.personalTunnel.value
        val status = OlcboxVpnState.status.value
        fun observed(value: Boolean, reason: PersonalUpdateDiagnosticFence): Boolean {
            if (!value) observation?.failedFence(reason)
            return value
        }
        return observed(packageName == PERSONAL_TEST_PACKAGE && Build.VERSION.SDK_INT >= 29 &&
            connectionMode == AndroidConnectionMode.Tun && splitTunnelMode == AndroidSplitTunnelMode.AllApps,
            PersonalUpdateDiagnosticFence.MODE_UNSUPPORTED) &&
            observed(!stopCleanupPending, PersonalUpdateDiagnosticFence.STOPPING) &&
            observed(generation == snapshot.stamp.generation, PersonalUpdateDiagnosticFence.OWNER_CHANGED) &&
            observed(!requireProofEpoch || personalProofEpoch.get() == snapshot.stamp.proofEpoch, PersonalUpdateDiagnosticFence.UNKNOWN_TRANSITION) &&
            observed(personalTransportEpoch.get() == snapshot.stamp.transportEpoch &&
                personalBridgeEpoch.get() == snapshot.stamp.bridgeEpoch &&
                vpnInterface === snapshot.pfd && snapshot.pfd.fileDescriptor.valid() &&
                tun2socksThread === snapshot.bridgeThread && snapshot.bridgeThread.isAlive && tun2socksStarted,
                PersonalUpdateDiagnosticFence.TUN_OR_BRIDGE_CHANGED) &&
            observed(!tun2socksStopRequested, PersonalUpdateDiagnosticFence.STOPPING) &&
            observed(tun2socksBackendPort == socksListenPort && activeCorePort == null, PersonalUpdateDiagnosticFence.TUN_OR_BRIDGE_CHANGED) &&
            observed(OlcboxVpnState.activeLocation === snapshot.location && snapshot.location.kind == LocationKind.Olcrtc &&
                personalOrdinaryLocation === snapshot.ordinary && personalActiveBootstrap === snapshot.bootstrap,
                PersonalUpdateDiagnosticFence.OWNER_CHANGED) &&
            observed(connectedStorageId == snapshot.storageId && connectedPersonalSubscriptionUrl == snapshot.url,
                PersonalUpdateDiagnosticFence.SUBSCRIPTION_CHANGED) &&
            observed(currentNetwork == snapshot.network && findActiveUpstreamNetwork() == snapshot.network &&
                physical != null && physical == snapshot.physical, PersonalUpdateDiagnosticFence.PHYSICAL_CHANGED) &&
            observed(physical!!.networkId == snapshot.stamp.networkId &&
                physical.revision == snapshot.stamp.snapshotRevision && physical.isProbeReady &&
                physical.transport == UpstreamTransport.Cellular && !physical.captive &&
                physicalNetworkSnapshots?.networkFor(snapshot.physical) == snapshot.network, PersonalUpdateDiagnosticFence.PHYSICAL_CHANGED) &&
            observed(OlcboxVpnState.personalPhaseEpoch() == snapshot.phaseEpoch, PersonalUpdateDiagnosticFence.PHASE_EPOCH_CHANGED) &&
            observed(phase.activePath == PersonalActivePath.OlcRtc, PersonalUpdateDiagnosticFence.MODE_UNSUPPORTED) &&
            observed(phase.phase == PersonalTunnelPhase.Checking || phase.phase == PersonalTunnelPhase.Working, PersonalUpdateDiagnosticFence.PHASE_NOT_WORKING) &&
            observed(status is VpnStatus.Reconnecting || status is VpnStatus.Connected, PersonalUpdateDiagnosticFence.STATUS_NOT_CONNECTED) &&
            observed(runCatching { olcrtc?.isRunning() == true }.getOrDefault(false), PersonalUpdateDiagnosticFence.NATIVE_NOT_RUNNING)
    }

    /** Control-plane DNS metadata only; never creates/binds a proof traffic socket. */
    private suspend fun resolvePersonalProofIpv4(network: Network): String? = withTimeoutOrNull(5_000L) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                DnsResolver.getInstance().query(
                    network, "rga.apollot.ru", DnsResolver.TYPE_A,
                    DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
                    java.util.concurrent.Executor { command -> command.run() }, cancellation,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            val target = if (rcode == 0) answer.firstOrNull { address ->
                                address is java.net.Inet4Address &&
                                    PersonalTunProof.isPublicProbeIpv4(address.hostAddress.orEmpty())
                            }?.hostAddress else null
                            if (continuation.isActive) continuation.resume(target)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private suspend fun attemptPersonalTunProof(): Boolean {
        val diagnosticProof = PersonalUpdateDiagnosticRecords.proofOwner()?.beginProof()
        val attemptJob = coroutineContext[Job]
        val snapshot = tunnelMutex.withLock {
            capturePersonalProofSnapshot().also { captured ->
                if (captured == null && attemptJob != null && personalProofJob === attemptJob) {
                    synchronized(this) {
                        if (personalProofJob === attemptJob && !stopCleanupPending &&
                            OlcboxVpnState.status.value is VpnStatus.Connected &&
                            OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working &&
                            OlcboxVpnState.personalTunnel.value.activePath == PersonalActivePath.OlcRtc
                        ) {
                            // No fresh runtime/network snapshot can support the prior receipt.
                            // Keep this proof loop alive for the bounded retry.
                            invalidatePersonalProof(cancelJob = false)
                            setStatus(VpnStatus.Reconnecting)
                            updateNotification("Ограниченная сеть · Проверяем")
                        }
                    }
                }
            }
        } ?: run {
            if (packageName == PERSONAL_TEST_PACKAGE) Log.d(TAG, "Personal TUN proof snapshot unavailable")
            return false
        }
        val observerEpoch = nextPersonalObserverEpoch()
        var prepared: PersonalTunProbeClient.Prepared? = null
        var verified: PersonalTunProof.Validated? = null
        var liveReceipt: PersonalTunProbeClient.Receipt? = null
        val result = try {
            if (packageName == PERSONAL_TEST_PACKAGE) Log.d(TAG, "Personal TUN proof DNS_BEGIN")
            val expectedIpv4 = resolvePersonalProofIpv4(snapshot.network)
            coroutineContext.ensureActive()
            val dnsSnapshotCurrent = tunnelMutex.withLock { personalProofCurrent(snapshot) }
            if (packageName == PERSONAL_TEST_PACKAGE) {
                Log.d(TAG, "Personal TUN proof DNS_READY public=${expectedIpv4 != null} fresh=$dnsSnapshotCurrent")
            }
            // DNS timeout/refusal is settled below under the same exact generation/phase fences;
            // a periodic failed refresh cannot leave a stale Working receipt published.
            if (expectedIpv4 == null || !dnsSnapshotCurrent) {
                PersonalTunProbeClient.Result.Rejected(PersonalTunProbeClient.Reason.NETWORK)
            } else PersonalTunProbeClient.probe(
                context = this,
                expectedIpv4 = expectedIpv4,
                onPrepared = { candidate ->
                    tunnelMutex.withLock {
                        if (!personalProofCurrent(snapshot) ||
                            candidate.helperUid <= 0 || candidate.nonce.length != 32 ||
                            candidate.remoteIpv4 != expectedIpv4 ||
                            candidate.localPort !in 1..65535
                        ) false else {
                            val remote = PersonalTunProof.ipv4Number(candidate.remoteIpv4)
                            if (remote == null || !PersonalTunProof.isPublicProbeIpv4(candidate.remoteIpv4)) false else {
                                val armed = runCatching {
                                    armPersonalTunFlowNative(observerEpoch, remote, candidate.localPort, 443)
                                    readPersonalTunFlowNative(observerEpoch).firstOrNull() == observerEpoch
                                }.getOrDefault(false)
                                if (armed) prepared = candidate
                                armed
                            }
                        }
                    }
                },
                onLiveReceipt = { receipt ->
                    tunnelMutex.withLock {
                        val initial = prepared
                        if (initial == null || !personalProofCurrent(snapshot) ||
                            receipt.nonce != initial.nonce || receipt.helperUid != initial.helperUid ||
                            receipt.remoteIpv4 != initial.remoteIpv4 || receipt.localPort != initial.localPort
                        ) false else {
                            val current = snapshot.stamp.copy(
                                generation = generation,
                                networkId = physicalNetworkSnapshots?.current(snapshot.network)?.networkId ?: -1,
                                snapshotRevision = physicalNetworkSnapshots?.current(snapshot.network)?.revision ?: -1,
                                transportEpoch = personalTransportEpoch.get(),
                                bridgeEpoch = personalBridgeEpoch.get(),
                                proofEpoch = personalProofEpoch.get(),
                            )
                            val native = runCatching { readPersonalTunFlowNative(observerEpoch) }.getOrNull()
                            val evidence = PersonalTunProof.validateExactFlow(snapshot.stamp, current,
                                observerEpoch, receipt.localIpv4, TUN_IPV4_ADDRESS, native)
                            verified = evidence
                            liveReceipt = if (evidence != null) receipt else null
                            evidence != null
                        }
                    }
                },
            )
        } finally {
            // C clear is epoch-specific; a canceled old attempt cannot erase a new arm.
            runCatching { clearPersonalTunFlowNative(observerEpoch) }
        }

        var diagnosticEventMs: Long? = null
        val committed = tunnelMutex.withLock {
            val admitted = personalProofCurrent(snapshot)
            if (packageName == PERSONAL_TEST_PACKAGE) {
                val outcome = when (result) {
                    is PersonalTunProbeClient.Result.CompanionReceipt -> "RECEIPT"
                    is PersonalTunProbeClient.Result.Rejected -> result.reason.name
                }
                // Personal-only diagnostics: fixed enums/booleans, never a tuple, nonce or bearer.
                Log.d(TAG, "Personal TUN proof outcome=$outcome liveFlow=${verified != null} fresh=$admitted")
            }
            synchronized(this) {
                val committed = if (!admitted || !personalProofCurrentNow(snapshot) ||
                    result !is PersonalTunProbeClient.Result.CompanionReceipt ||
                    result.value != liveReceipt || verified == null ||
                    verified?.stamp != snapshot.stamp
                ) {
                    if (personalProofCurrentNow(snapshot) &&
                        OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working
                    ) {
                        setStatus(VpnStatus.Reconnecting)
                        updateNotification("Ограниченная сеть · Проверяем")
                    }
                    false
                } else if (OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Checking) {
                    if (!OlcboxVpnState.publishPersonalWorking(snapshot.phaseEpoch, verified!!)) false else {
                        personalWorkingNetwork = snapshot.network
                        personalWorkingSnapshot = snapshot.physical
                        personalRefreshSnapshot = snapshot.copy(phaseEpoch = OlcboxVpnState.personalPhaseEpoch())
                        setStatus(VpnStatus.Connected)
                        updateNotification(connectedNotificationText())
                        true
                    }
                } else {
                    personalWorkingNetwork = snapshot.network
                    personalWorkingSnapshot = snapshot.physical
                    personalRefreshSnapshot = snapshot.copy(phaseEpoch = OlcboxVpnState.personalPhaseEpoch())
                    OlcboxVpnState.channelProxy?.let { publishPersonalRefreshProxy(it) }
                    true // A still-current periodic proof refreshed an existing Working state.
                }
                if (diagnosticProof != null) diagnosticEventMs = PersonalUpdateDiagnosticRecords.eventTime()
                committed
            }
        }
        diagnosticProof?.committed(committed, diagnosticEventMs) // Only publish after both original locks are released.
        return committed
    }

    /**
     * The running core's account of itself, whichever core that is. An olcRTC
     * location runs neither, and saying so is the useful answer there rather than
     * an empty string that reads like a core with nothing to report.
     */
    private fun activeCoreDiagnostics(): String = when {
        OlcboxVpnState.activeLocation?.xrayConfig != null -> xrayCore.redactedDiagnostics()
        xrayCore.isRunning() -> xrayCore.diagnostics()
        singBoxCore.isRunning() -> singBoxCore.diagnostics()
        else -> "no core process is running"
    }

    /** Called under tunnelMutex on every watchdog tick and benign network restore. */
    private suspend fun denyConnectedSubscriptionIfNeeded(): Boolean {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return false
        val id = connectedStorageId ?: return false
        val bundle = repository.getBundle()
        val denial = SubscriptionTunnelAccess.denial(bundle, id, System.currentTimeMillis(), runtimePolicy)
        val generalDrift = generalConsumer?.hasOwnedRuntime() == true &&
            selectGeneralSubscriptionUrl(packageName, connectionMode, splitTunnelMode, bundle, id,
                generalConsumer != null) != connectedGeneralSubscriptionUrl
        val selectedDrift = generalDrift || personalOrdinaryLocation != null &&
            (bundle.activeLocationId != id ||
                bundle.locations.singleOrNull { it.storageId == id }?.subscriptionUrl != connectedPersonalSubscriptionUrl)
        if (denial == null && !selectedDrift) return false
        val reason = denial ?: "Selected subscription changed; reconnect"
        val capturedGeneral = generalConsumer?.retire()
        generalPrefetchJob?.cancel()
        generalHandoffJob?.cancel()
        generalRecoveryJob?.cancel()
        invalidatePersonalProof()
        ++generation
        startupJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        networkLossJob?.cancel()
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Unknown)
        setStatus(VpnStatus.Error(reason))
        updateNotification(reason)
        addLog(reason)
        if (capturedGeneral != null && !stopGeneralForCleanup(capturedGeneral, 20_000)) {
            stopCleanupPending = true
            return true
        }
        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
        return true
    }

    @Synchronized
    private fun cleanup(stopService: Boolean = true) {
        if (stopService) cleanupStopServiceRequested = true
        if (cleanupJob?.isActive == true) return
        val capturedGeneral = generalConsumer?.retire() // Capture before ++generation and cancellation.
        val capturedLease = generalProcessLease?.takeIf { AndroidGeneralProcessCustody.actual.owns(it) }
        capturedLease?.beginCleanup(capturedGeneral, vpnInterface, tun2socksThread,
            listOfNotNull(startupJob, generalPrefetchJob, generalHandoffJob, generalRecoveryJob,
                watchdogJob, networkLossJob, recoveryJob, personalBootstrapPrefetchJob,
                personalBootstrapInspectionJob, personalHandoffJob, personalReturnJob, personalProofJob))
        generalPrefetchJob?.cancel()
        generalHandoffJob?.cancel()
        generalRecoveryJob?.cancel()

        invalidatePersonalProof()
        personalBootstrapPrefetchJob?.cancel()
        personalBootstrapInspectionJob?.cancel()
        personalHandoffJob?.cancel()
        personalReturnJob?.cancel()
        personalOrdinaryLocation = null
        personalActiveBootstrap = null
        connectedPersonalSubscriptionUrl = null

        val status = OlcboxVpnState.status.value
        if (!stopCleanupPending && status is VpnStatus.Disconnected &&
            vpnInterface == null &&
            tun2socksThread == null &&
            socksProxy == null &&
            runCatching { !isActiveTransportRunning() }.getOrDefault(false) &&
            cleanupJob?.isActive != true
            && generalConsumer?.hasOwnedRuntime() != true
        ) {
            capturedLease?.recordTransportStopped()
            capturedLease?.tryRelease()
            if (cleanupStopServiceRequested) stopSelf()
            return
        }
        if (status is VpnStatus.Stopping && cleanupJob?.isActive == true) return

        stopCleanupPending = true
        val cleanupGeneration = ++generation
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        releaseWakeLock()

        unregisterNetworkMonitor()
        lastConnectedOrdinaryNetwork = null
        lastConnectedOrdinaryNetworkId = null
        lastConnectedOrdinaryTransport = null
        lastConnectedOrdinaryDns = null
        ordinaryRoutingFacts = null
        var stopped = false
        val exactCleanup = scope.launch(start = CoroutineStart.LAZY) {
            try {
                tunnelMutex.withLock {
                    if (capturedGeneral != null && !stopGeneralForCleanup(capturedGeneral, 20_000)) {
                        if (generation == cleanupGeneration) {
                            setStatus(VpnStatus.Error("GENERAL stop incomplete; retry Stop"))
                            OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
                        }
                        return@withLock // Unknown owner/PFD stay captured; never close or replace as all-direct.
                    }
                    stopAuthenticatedSocksProxy()
                    updateUnderlyingNetwork(null)
                    unbindProcessFromNetwork()
                    val visibleStopped = try {
                        stopVisibleVpnProcesses()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        addLog("VPN bridge stop failed (${error.javaClass.simpleName})")
                        false
                    }
                    val mobileStopped = stopMobileAndWait()
                    resetRecoveryState()
                    if (generation == cleanupGeneration) {
                        if (visibleStopped && mobileStopped) {
                            capturedLease?.recordTransportStopped()
                            stopCleanupPending = false
                            setStatus(VpnStatus.Disconnected)
                            addLog("${activeModeLabel()} stopped")
                            stopped = true
                        } else {
                            setStatus(VpnStatus.Error("VPN stop incomplete"))
                            updateNotification("VPN stop incomplete")
                            addLog("VPN stop incomplete; retry Stop")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == cleanupGeneration) {
                    setStatus(VpnStatus.Error("VPN stop incomplete"))
                    updateNotification("VPN stop incomplete")
                    addLog("VPN cleanup failed (${error.javaClass.simpleName}); retry Stop")
                }
            }
        }
        // Completion, including owned child jobs, is the linearization point.
        // A late Stop either reaches this callback or sees an inactive job and
        // takes the already-stopped path above. Failed cleanup never stops Self.
        exactCleanup.invokeOnCompletion {
            synchronized(this@OlcboxVpnService) {
                if (cleanupStopServiceRequested && stopped && generation == cleanupGeneration) stopSelf()
            }
        }
        cleanupJob = exactCleanup
        capturedLease?.observeJobs(listOf(exactCleanup)) // Registration precedes execution/completion.
        exactCleanup.start()
    }

    private suspend fun stopVisibleVpnProcesses(): Boolean {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        val tunnelClosed = cleanupVpnInterface()
        tunThread?.interrupt()
        val bridgeStopped = waitForTun2socksStopped(tunThread)
        if (bridgeStopped && tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        unbindProcessFromNetwork()
        return bridgeStopped && tunnelClosed
    }

    private suspend fun waitForTun2socksStopped(thread: Thread?, budget: GeneralCleanupBudget? = null): Boolean {
        if (thread == null) return budget?.remainingMs()?.let { it > 0 } ?: true
        val left = minOf(TUN2SOCKS_STOP_WAIT_MS, budget?.remainingMs() ?: TUN2SOCKS_STOP_WAIT_MS)
        if (left <= 0) return false
        val stopped = withTimeoutOrNull(left) {
            while (thread.isAlive) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } == true && !tun2socksStarted && (budget?.remainingMs()?.let { it > 0 } ?: true)
        if (!stopped) {
            addLog("tun2socks cleanup is still pending")
        }
        return stopped
    }

    private fun failIncompleteTransportStop() {
        setStatus(VpnStatus.Error("Previous VPN stop incomplete"))
        updateNotification("Previous VPN stop incomplete")
        addLog("Previous VPN stop incomplete; retry connection after stopping")
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true,
        stopMobileBeforeTun: Boolean = false
    ): Boolean {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        val earlyMobileStopped = !stopMobileBeforeTun || stopMobile()
        stopTun2socks()
        val tunnelClosed = !closeTun || cleanupVpnInterface()
        tunThread?.interrupt()
        val bridgeStopped = !closeTun || waitForTun2socksStopped(tunThread)
        if (bridgeStopped && tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        val mobileStopped = if (waitForSocksPort) {
            if (stopMobileBeforeTun) {
                waitForSocksPortReleased()
            } else {
                stopMobileAndWait()
            }
        } else if (!stopMobileBeforeTun) {
            stopMobile()
        } else true
        if (closeTun) {
            unbindProcessFromNetwork()
        }
        return earlyMobileStopped && bridgeStopped && mobileStopped && tunnelClosed
    }

    private fun stopTun2socks() {
        personalBridgeEpoch.incrementAndGet()
        personalProofEpoch.incrementAndGet()
        tun2socksBackendPort = null
        if (nativeLibrariesLoaded && tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            val nativeEpoch = tun2socksNativeEpoch
            // A notification is not completion. The existing exact Thread join
            // and GENERAL custody fences still decide whether restart is safe.
            tun2socksStopRequested = runCatching { tun2socksNativeStopper(nativeEpoch) }.getOrDefault(false)
        }
    }

    private fun stopMobile(): Boolean {
        personalTransportEpoch.incrementAndGet()
        personalProofEpoch.incrementAndGet()
        val provider = lastMobileProvider
        val runtime = olcrtc
        val wasRunning = runCatching { runtime?.isRunning() == true }.getOrDefault(true)
        // Logged rather than swallowed. A stop that misses its deadline is the
        // difference between the next room joining and "olcRTC runtime is
        // already active", and for one whole debugging session this line threw
        // that away: the phone reported a start refused by a runtime nobody
        // could see had failed to stop.
        val mobileStopped = runtime == null || runCatching { runtime.stop(MOBILE_STOP_TIMEOUT_MS) }
            .onFailure { addLog("olcRTC stop failed (${it.javaClass.simpleName})") }
            .isSuccess
        // Also tear down any active sing-box / Xray core (no-op if none running).
        val coresStopped = runCatching { stopCoreProcesses() }
            .onFailure { addLog("Core stop failed (${it.javaClass.simpleName})") }
            .isSuccess
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
        }
        return mobileStopped && coresStopped
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private suspend fun stopMobileAndWait(): Boolean {
        generalConsumer?.takeIf { it.hasOwnedRuntime() }?.retire()?.let {
            return stopGeneralForCleanup(it, 20_000)
        }
        val socksPort = socksListenPort
        val stopReturned = stopMobile()
        val corePortsReleased = awaitRetiredCorePorts()
        if (!corePortsReleased) addLog("Previous core listener still active")
        val socksReleased = waitForSocksPortReleased(socksPort)
        val runtimeStopped = runCatching { olcrtc?.isRunning() != true }.getOrDefault(false)
        return stopReturned && corePortsReleased && socksReleased && runtimeStopped
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = socksListenPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return true
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
        return false
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(socksConnectHost(), port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun socksConnectHost(): String {
        if (generalConsumer?.hasOwnedRuntime() == true) return "127.0.0.1"
        if (OlcboxVpnState.activeLocation?.xrayConfig != null) return "127.0.0.1"
        return AndroidSocksProxySettings.connectHost(socksListenHost)
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            noteRtcFailure(
                reason = "RTC failed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_FAILED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(
                reason = "RTC closed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_CLOSED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(
                reason = "RTC network path is closed",
                fullRestart = false,
                threshold = RTC_IO_ERROR_RECOVERY_THRESHOLD
            )
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(
        reason: String,
        fullRestart: Boolean,
        threshold: Int
    ) {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= RTC_FAILURE_WINDOW_MS) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        if (rtcFailureCount >= threshold) {
            requestTransportRecovery(reason, fullRestart)
        }
    }

    private fun isTunTrafficStalled(): Boolean {
        val stats = readTun2SocksStats() ?: return false
        val previous = watchdogTunStats
        watchdogTunStats = stats

        if (previous == null) return false

        val txDelta = stats.txPackets - previous.txPackets
        val rxDelta = stats.rxPackets - previous.rxPackets
        if (txDelta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rxDelta <= 0L && isActiveTransportRunning()) {
            watchdogStalledSamples++
        } else if (rxDelta > 0L || txDelta <= 0L) {
            watchdogStalledSamples = 0
        }

        return watchdogStalledSamples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    private fun readTun2SocksStats(): Tun2SocksStats? {
        if (!nativeLibrariesLoaded || !tun2socksStarted) return null
        return runCatching {
            val values = getTun2socksStatsNative()
            if (values.size < 4) return null
            Tun2SocksStats(
                txPackets = values[0],
                txBytes = values[1],
                rxPackets = values[2],
                rxBytes = values[3]
            )
        }.getOrNull()?.also { stats ->
            // The byte figures were read and thrown away — only the packet
            // deltas fed the stall watchdog. They are the session's traffic, and
            // the screen had nothing to show.
            OlcboxVpnState.setTraffic(bytesIn = stats.rxBytes, bytesOut = stats.txBytes)
        }
    }

    private fun requestTransportRecovery(
        reason: String,
        fullRestart: Boolean,
        delayMs: Long = 0L,
        setReconnectingImmediately: Boolean = true
    ) {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        val recoveryGeneration = generation
        if (delayMs <= 0L &&
            recoveryRequestedForGeneration == recoveryGeneration &&
            recoveryJob?.isActive == true
        ) {
            return
        }

        recoveryJob?.cancel()
        if (personalOrdinaryLocation != null) invalidatePersonalProof()
        if (setReconnectingImmediately && status is VpnStatus.Connected) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Reconnecting...")
        }

        recoveryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (generation != recoveryGeneration) return@launch
            val currentStatus = OlcboxVpnState.status.value
            if (currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Reconnecting) {
                return@launch
            }

            recoveryRequestedForGeneration = recoveryGeneration
            if (setReconnectingImmediately && currentStatus is VpnStatus.Connected) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Reconnecting...")
            }

            addLog("$reason; reconnecting transport")
            recoveryJob = null
            startTunnel(isMigration = true, forceFullRestart = fullRestart)
        }
    }

    private fun personalRestrictedRecoveryDelay(baseMs: Long, transport: UpstreamTransport?): Long =
        PersonalRestrictedRecoveryDelay.apply(
            baseMs,
            packageName == "tech.gatealtas.rga.personal" &&
                runtimePolicy == RuntimeLocationPolicy.ExperimentalDebug &&
                connectionMode == AndroidConnectionMode.Tun &&
                connectedPersonalSubscriptionUrl != null && vpnInterface != null &&
                restrictedNetworkCoordinator != null,
            transport
        )

    private fun refreshWakeLock(force: Boolean = false) {
        val lock = wakeLock ?: return
        val now = System.currentTimeMillis()
        if (!force &&
            lock.isHeld &&
            now - lastWakeLockRefreshAtMs < WAKE_LOCK_REFRESH_INTERVAL_MS
        ) {
            return
        }

        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRefreshAtMs = now
        }.onFailure {
            Log.w(TAG, "Failed to refresh VPN wake lock (${it.javaClass.simpleName})")
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure {
            Log.w(TAG, "Failed to release VPN wake lock (${it.javaClass.simpleName})")
        }
        lastWakeLockRefreshAtMs = 0L
    }

    private fun scheduleTransportRetry(
        requestedGeneration: Long,
        reason: String,
        baseDelayMs: Long = RECONNECT_RETRY_BASE_DELAY_MS
    ) {
        val delayMs = nextReconnectRetryDelay(baseDelayMs)
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            addLog("Retrying transport after $reason in ${delayMs / 1_000}s")
            delay(delayMs)
            if (generation != requestedGeneration) return@launch
            if (OlcboxVpnState.status.value !is VpnStatus.Reconnecting) return@launch

            recoveryJob = null
            startTunnel(isMigration = true)
        }
    }

    private fun nextReconnectRetryDelay(baseDelayMs: Long): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (baseDelayMs * multiplier).coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    private fun resetRecoveryState() {
        recoveryRequestedForGeneration = 0L
        reconnectAttempt = 0
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun shouldRecreateTunnelOnRtcLoss(): Boolean {
        return connectionMode == AndroidConnectionMode.Tun
    }

    private fun cleanupVpnInterface(): Boolean {
        val captured = synchronized(this) {
            val descriptor = vpnInterface
            val owner = tunDnsScope
            if (descriptor == null) return owner == null
            if (owner != null && owner.descriptor !== descriptor) return false
            descriptor to owner
        }
        val (descriptor, owner) = captured
        return runCatching {
            generalProcessLease?.takeIf { AndroidGeneralProcessCustody.actual.owns(it) }?.closeTun(descriptor)
                ?: descriptor.close()
            // Descriptor-close success alone is not a native DNS-owner receipt.
            // A failed request retains this SAME closed parent and owner for retry.
            if (owner != null && !tun2socksDnsScopeCloser(owner.id)) return@runCatching false
            synchronized(this) {
                if (tunDnsScope === owner) tunDnsScope = null
                if (vpnInterface === descriptor) vpnInterface = null
            }
            true
        }.getOrElse {
            addLog("VPN tunnel descriptor close failed")
            false
        }
    }

    private fun canReconnectTransportInPlace(location: LocationConfig? = null): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun ->
                vpnInterface != null && tun2socksThread?.isAlive == true && tun2socksStarted &&
                    TunBridgePortPolicy.canReuseBridge(
                        bridgePort = tun2socksBackendPort,
                        currentCorePort = activeCorePort,
                        targetUsesCore = (location?.kind ?: OlcboxVpnState.activeLocation?.kind) != LocationKind.Olcrtc,
                        proxyPort = socksListenPort
                    )
            AndroidConnectionMode.Proxy -> isActiveTransportRunning()
        }
    }

    private fun isTunBridgeReadyForActiveTransport(): Boolean =
        tun2socksStarted && tun2socksThread?.isAlive == true &&
            TunBridgePortPolicy.matchesActivePort(tun2socksBackendPort, activeCorePort, socksListenPort)

    /**
     * Whether the transport that is supposed to be carrying traffic is still alive.
     *
     * olcRTC runs inside the gomobile library; sing-box and Xray run as child
     * processes. Everything here was written when olcRTC was the only transport and
     * asked the engine's `isRunning()` directly — which is false whenever a core is the
     * active transport, so the watchdog declared every core connection dead fifteen
     * seconds after it came up and restarted it, forever.
     */
    private fun isActiveTransportRunning(): Boolean =
        if (generalConsumer?.hasOwnedRuntime() == true) {
            generalConsumer?.isRunning() == true
        } else if (activeCorePort != null) {
            coreChainAlive(activeCoreChainRequiresBoth, singBoxCore.isRunning(), xrayCore.isRunning())
        } else {
            olcrtc?.isRunning() == true
        }

    private fun activeTransportLabel(): String =
        if (activeCorePort != null) "core transport" else "olcRTC"

    private fun shouldRestartForStartCommand(): Boolean {
        return when (OlcboxVpnState.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting,
            VpnStatus.Stopping -> true
            VpnStatus.Disconnected,
            is VpnStatus.Error -> false
        } ||
            startupJob?.isActive == true ||
            cleanupJob?.isActive == true ||
            vpnInterface != null ||
            tun2socksThread != null ||
            socksProxy != null ||
            isActiveTransportRunning()
    }

    private fun registerNetworkMonitor(registrationGeneration: Long) {
        unregisterNetworkMonitor()
        val snapshots = AndroidPhysicalNetworkSnapshots(registrationGeneration, physicalNetworkLedger)
        val callback = createNetworkCallback(registrationGeneration, snapshots)
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            physicalNetworkSnapshots = snapshots
            networkCallback = callback
            if ((runtimePolicy == RuntimeLocationPolicy.ExperimentalDebug || ensureGeneralConsumer() != null) &&
                connectionMode == AndroidConnectionMode.Tun
            ) {
                restrictedNetworkCoordinator = AndroidRestrictedNetworkCoordinator(
                    snapshots = snapshots,
                    scope = scope,
                    selectedPath = ::selectedOrdinaryPathEvidence,
                    onCandidate = { candidate ->
                        scope.launch {
                            tunnelMutex.withLock { reviewRestrictedCandidate(candidate) }
                        }
                    }
                )
            }
            addLog("Network monitor registered")
        } catch (e: Exception) {
            snapshots.close()
            Log.e(TAG, "Network monitor failed (${e.javaClass.simpleName})")
        }
    }

    private fun unregisterNetworkMonitor() {
        restrictedNetworkCoordinator?.cancel()
        restrictedNetworkCoordinator = null
        physicalNetworkSnapshots?.close()
        physicalNetworkSnapshots = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    /** A SOCKS bulk answer is selected-core evidence; it is not a full-TUN proof. */
    private suspend fun selectedOrdinaryPathEvidence(network: Network): SelectedPathEvidence {
        data class ProbeContext(
            val generation: Long,
            val snapshot: PhysicalNetworkSnapshot,
            val frontPort: Int,
            val backendPort: Int?,
            val hasRoutingFront: Boolean,
            val storageId: String,
            val proxy: SubscriptionFetchProxy,
            val bridgeReady: Boolean
        )
        val context = tunnelMutex.withLock {
            val snapshot = physicalNetworkSnapshots?.current(network) ?: return@withLock null
            val storageId = connectedStorageId ?: return@withLock null
            val frontPort = activeCorePort ?: return@withLock null
            val backendPort = activeCoreBackendPort
            val hasRoutingFront = routingSettings.needsRules
            val probePort = AndroidSelectedPathProbe.transportPort(
                frontPort, backendPort, hasRoutingFront
            ) ?: return@withLock null
            val status = OlcboxVpnState.status.value
            if (connectionMode != AndroidConnectionMode.Tun ||
                (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) ||
                OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc ||
                network != currentNetwork || network != findActiveUpstreamNetwork()
            ) return@withLock null
            val applied = ordinaryRoutingFacts
            val currentAttempt = applied?.generation == generation && applied.network == network &&
                applied.networkId == snapshot.networkId && applied.dnsServers == snapshot.dnsServers
            val connectedHere = lastConnectedOrdinaryNetwork == network &&
                lastConnectedOrdinaryNetworkId == snapshot.networkId &&
                lastConnectedOrdinaryDns == snapshot.dnsServers
            if (!currentAttempt && !connectedHere) return@withLock null
            val (username, password) = upstreamLogin()
            ProbeContext(
                generation, snapshot, frontPort, backendPort, hasRoutingFront, storageId,
                SubscriptionFetchProxy(socksConnectHost(), probePort, username, password),
                vpnInterface != null && isTunBridgeReadyForActiveTransport()
            )
        } ?: return SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Unknown)

        val evidence = selectedPathProbe.collect(
            selectedOnCurrentNetwork = true,
            coreRunning = isActiveTransportRunning(),
            bridgeReady = context.bridgeReady,
            proxy = context.proxy
        )
        return tunnelMutex.withLock {
            if (generation != context.generation ||
                physicalNetworkSnapshots?.current(network) != context.snapshot ||
                currentNetwork != network || activeCorePort != context.frontPort ||
                activeCoreBackendPort != context.backendPort ||
                routingSettings.needsRules != context.hasRoutingFront ||
                connectedStorageId != context.storageId ||
                !isActiveTransportRunning()
            ) SelectedPathEvidence(ProbeOutcome.Unknown, ProbeOutcome.Unknown)
            else evidence
        }
    }

    /** A classifier result is only a trigger; the cached room is not admission. */
    private fun reviewRestrictedCandidate(candidate: RestrictedNetworkCandidate): Boolean {
        if (packageName == "tech.gatealtas.rga" && connectedGeneralSubscriptionUrl != null) {
            if (!isCurrentRestrictedCandidate(candidate) || generalHandoffJob?.isActive == true ||
                generalConsumer?.hasOwnedRuntime() == true) return false
            val network = findActiveUpstreamNetwork() ?: return false
            val snapshot = physicalNetworkSnapshots?.current(network) ?: return false
            generalHandoffJob = scope.launch { tunnelMutex.withLock { handoffToGeneral(network, snapshot, candidate.generation) } }
            return true
        }
        val status = OlcboxVpnState.status.value
        if (packageName != PERSONAL_TEST_PACKAGE ||
            runtimePolicy != RuntimeLocationPolicy.ExperimentalDebug ||
            connectionMode != AndroidConnectionMode.Tun || connectedStorageId == null ||
            connectedPersonalSubscriptionUrl == null || vpnInterface == null ||
            OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc ||
            (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) ||
            candidate.generation != generation
        ) return false
        val snapshots = physicalNetworkSnapshots ?: return false
        val network = findActiveUpstreamNetwork() ?: return false
        val snapshot = snapshots.current(network) ?: return false
        if (snapshot.generation != candidate.generation ||
            snapshot.networkId != candidate.networkId ||
            snapshot.revision != candidate.revision || !snapshot.isProbeReady ||
            snapshot.transport != UpstreamTransport.Cellular || snapshot.captive ||
            currentNetwork != network
        ) return false

        // Keep the selected subscription and the established TUN. Only the
        // personal package can attempt a managed join, and only with a fresh
        // server-side admission checked by the native node.
        restrictedNoticeActive = true
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Ordinary)
        setStatus(VpnStatus.Reconnecting)
        updateNotification(RESTRICTED_ALTERNATE_UNAVAILABLE)
        addLog("Restricted mobile network suspected")
        connectedPersonalSubscriptionUrl?.let { url ->
            personalBootstrapInspectionJob?.cancel()
            val inspectedGeneration = generation
            personalBootstrapInspectionJob = scope.launch(Dispatchers.IO) {
                val cached = runCatching { personalBootstrapReader(url) }.getOrNull()
                if (cached == null) {
                    tunnelMutex.withLock {
                        if (generation == inspectedGeneration && connectedPersonalSubscriptionUrl == url &&
                            isCurrentRestrictedCandidate(candidate)
                        ) {
                            addLog("Restricted mobile network: personal bootstrap unavailable")
                            abortPersonalPreflight()
                        }
                    }
                    return@launch
                }
                tunnelMutex.withLock {
                    if (generation != inspectedGeneration || connectedPersonalSubscriptionUrl != url ||
                        !isCurrentRestrictedCandidate(candidate) ||
                        packageName != PERSONAL_TEST_PACKAGE || vpnInterface == null ||
                        personalHandoffJob?.isActive == true
                    ) return@withLock
                    recoveryJob?.cancel()
                    recoveryJob = null
                    OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.OlcRtc)
                    personalHandoffJob = scope.launch {
                        tunnelMutex.withLock { handoffToPersonal(candidate, cached, url) }
                    }
                }
            }
        }
        return true
    }

    private fun isCurrentRestrictedCandidate(candidate: RestrictedNetworkCandidate): Boolean {
        val network = findActiveUpstreamNetwork() ?: return false
        val snapshot = physicalNetworkSnapshots?.current(network) ?: return false
        return generation == candidate.generation && currentNetwork == network &&
            snapshot.generation == candidate.generation && snapshot.networkId == candidate.networkId &&
            snapshot.revision == candidate.revision && snapshot.isProbeReady &&
            snapshot.transport == UpstreamTransport.Cellular && !snapshot.captive
    }

    internal suspend fun stopBridgeKeepingTun(pfd: ParcelFileDescriptor, budget: GeneralCleanupBudget? = null): Boolean {
        if (vpnInterface !== pfd) return false
        if (budget != null && budget.remainingMs() <= 0) return false
        val oldThread = tun2socksThread
        stopTun2socks()
        oldThread?.interrupt()
        val stopped = waitForTun2socksStopped(oldThread, budget)
        if (stopped && tun2socksThread === oldThread) tun2socksThread = null
        return stopped && vpnInterface === pfd
    }

    internal fun retireGeneralForBridgeExit(exitedThread: Thread, exitedBackendPort: Int?): AndroidGeneralServiceConsumer.CleanupTicket? =
        synchronized(this) {
            val binding = generalActiveBinding ?: return@synchronized null
            val port = generalOptions?.socksPort?.toInt() ?: return@synchronized null
            if (tun2socksThread !== exitedThread || exitedBackendPort != port || vpnInterface !== binding.tun ||
                generalConsumer?.hasOwnedRuntime() != true) return@synchronized null
            generalBridgeEpoch.incrementAndGet() // Invalidate a late body BEFORE queued recovery/cancellation.
            val captured = generalConsumer?.retire() ?: return@synchronized null
            OlcboxVpnState.setGeneralPhase(GeneralTunnelPhase.Unavailable)
            captured
        }

    internal suspend fun stopGeneralForCleanup(ticket: AndroidGeneralServiceConsumer.CleanupTicket, timeoutMs: Long): Boolean =
        withContext(NonCancellable) {
            val pfd = ticket.tun as? ParcelFileDescriptor ?: return@withContext false
            val consumer = generalConsumer ?: return@withContext false
            if (timeoutMs !in 1..30_000 || vpnInterface !== pfd) return@withContext false
            val budget = GeneralCleanupBudget.capture(timeoutMs, SystemClock::elapsedRealtime) ?: return@withContext false
            Log.d("RgaGeneralRuntime", "stopStage=SERVICE_OWNER_NOTIFY")
            if (!consumer.requestStopCaptured(ticket, budget)) return@withContext false
            Log.d("RgaGeneralRuntime", "stopStage=SERVICE_BRIDGE_JOIN")
            if (vpnInterface !== pfd || !stopBridgeKeepingTun(pfd, budget)) return@withContext false
            if (budget.remainingMs() <= 0) return@withContext false
            Log.d("RgaGeneralRuntime", "stopStage=SERVICE_CONSUMER_STOP")
            if (!consumer.stopCaptured(ticket, budget) || budget.remainingMs() <= 0) return@withContext false
            val port = generalOptions?.socksPort?.toInt()
            if (port != null && activeCorePort == port) { activeCorePort = null; activeCoreLogin = null }
            generalActiveBinding = null; generalOptions = null; generalOrdinaryLocation = null
            Log.d("RgaGeneralRuntime", "stopStage=SERVICE_SETTLED")
            true
        }

    private suspend fun startBridgeOnHeldTun(pfd: ParcelFileDescriptor): Boolean {
        if (vpnInterface !== pfd || !startTun2socks(pfd)) return false
        val ready = withTimeoutOrNull(TUN2SOCKS_STOP_WAIT_MS) {
            while (!isTunBridgeReadyForActiveTransport()) delay(SOCKS_RELEASE_POLL_MS)
            true
        } ?: false
        return ready && vpnInterface === pfd
    }

    private fun personalLocation(bootstrap: PersonalBootstrapV1): LocationConfig = LocationConfig(
        name = "Ограниченная сеть",
        id = bootstrap.roomId,
        key = bootstrap.roomKey,
        bypassProvider = bootstrap.provider,
        transport = bootstrap.transport,
        kind = LocationKind.Olcrtc
    )

    private fun freshPersonalClaims(bootstrap: PersonalBootstrapV1): String? = try {
        if (!bootstrap.isUsableAt(System.currentTimeMillis())) null else {
            val installation = AndroidKeystoreInstallationIdentity.forApp(applicationContext).loadOrCreate()
            PersonalJoinProofFactory(installation).create(bootstrap.roomId, bootstrap.country).toJsonString()
        }
    } catch (_: Exception) {
        null
    }

    /** Both bridge generations use duplicates of this same open VPN descriptor. */
    private suspend fun handoffToPersonal(
        candidate: RestrictedNetworkCandidate,
        bootstrap: PersonalBootstrapV1,
        url: String,
        coldTransitionCurrent: (suspend () -> Boolean)? = null
    ): Boolean {
        val pfd = vpnInterface ?: return false
        val ordinary = OlcboxVpnState.activeLocation ?: return false
        val storageId = connectedStorageId ?: return false
        val network = findActiveUpstreamNetwork() ?: return false
        val transitionSnapshot = physicalNetworkSnapshots?.current(network) ?: return false
        val selectedMatches = selectedSubscriptionStillMatches(storageId, url)
        if (!selectedMatches) {
            stopForPersonalSelectionDrift()
            return false
        }
        if (SubscriptionTunnelAccess.denial(repository.getBundle(), storageId,
                System.currentTimeMillis(), runtimePolicy) != null) {
            denyConnectedSubscriptionIfNeeded()
            return false
        }
        if (!isCurrentRestrictedCandidate(candidate) || connectedPersonalSubscriptionUrl != url ||
            ordinary.kind == LocationKind.Olcrtc
        ) {
            abortPersonalPreflight()
            return false
        }
        val claims = freshPersonalClaims(bootstrap)
        val alternate = personalLocation(bootstrap)
        if (claims == null || !alternate.isComplete()) {
            abortPersonalPreflight()
            return false
        }
        val expectedGeneration = generation
        val current: suspend () -> Boolean = {
            generation == expectedGeneration && vpnInterface === pfd &&
                connectedStorageId == storageId && connectedPersonalSubscriptionUrl == url &&
                findActiveUpstreamNetwork() == network && currentNetwork == network &&
                selectedSubscriptionStillMatches(storageId, url)
        }
        if (coldTransitionCurrent != null && !coldTransitionCurrent()) return false
        invalidatePersonalProof()
        watchdogJob?.cancel()
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.OlcRtc)
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Ограниченная сеть · Переключаемся")
        val handoff = PersonalBridgeHandoff(
            isCurrent = current,
            stopCurrentBridge = { stopBridgeKeepingTun(pfd) },
            stopCurrentTransport = { stopMobileAndWait() },
            startTargetTransport = {
                startTransport(alternate, network, expectedGeneration, false, personalClaimsJson = claims,
                    personalAdmissionCurrent = coldTransitionCurrent)
            },
            startTargetBridge = { startBridgeOnHeldTun(pfd) },
            verifyTarget = { isTunBridgeReadyForActiveTransport() && verifyTunnel() != null },
            restoreSource = {
                if (coldTransitionCurrent == null) {
                    stopBridgeKeepingTun(pfd) && stopMobileAndWait() &&
                        startTransport(ordinary, network, expectedGeneration, false) &&
                        startBridgeOnHeldTun(pfd) && verifyTunnel() != null
                } else {
                    stopBridgeKeepingTun(pfd) && coldTransitionCurrent() &&
                        stopMobileAndWait() && coldTransitionCurrent() &&
                        startTransport(ordinary, network, expectedGeneration, false,
                            personalAdmissionCurrent = coldTransitionCurrent) && coldTransitionCurrent() &&
                        startBridgeOnHeldTun(pfd) && coldTransitionCurrent() &&
                        verifyTunnel() != null && coldTransitionCurrent()
                }
            },
            coldTransitionCurrent = coldTransitionCurrent
        )
        val outcome = try {
            handoff.switch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            settlePersonalFailure(pfd, "Restricted handoff failed", RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
            return false
        }
        when (outcome) {
            PersonalBridgeOutcome.TargetReady -> {
                return publishPersonalTransition(expectedGeneration, pfd, network, transitionSnapshot, storageId, url) {
                    personalOrdinaryLocation = ordinary
                    personalActiveBootstrap = bootstrap
                    OlcboxVpnState.activeLocation = alternate
                    // SOCKS and bridge readiness are not independent-UID TUN proof.
                    // Keep the UI in verification until the on-device gate is present.
                    setStatus(VpnStatus.Reconnecting)
                    OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
                    updateNotification("Ограниченная сеть · Проверяем")
                    addLog("Personal transport ready; full-tunnel verification pending")
                    startWatchdog()
                    startPersonalProofLoop()
                }
            }
            PersonalBridgeOutcome.SourceRestored -> {
                return publishPersonalTransition(expectedGeneration, pfd, network, transitionSnapshot, storageId, url) {
                    OlcboxVpnState.activeLocation = ordinary
                    setStatus(VpnStatus.Connected)
                    OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Ordinary)
                    updateNotification(connectedNotificationText())
                    startWatchdog()
                    addLog("Restricted transport unavailable; ordinary tunnel restored")
                }
            }
            PersonalBridgeOutcome.TunHeld -> {
                settlePersonalFailure(pfd, "Restricted handoff incomplete", RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
            }
            PersonalBridgeOutcome.Superseded -> {
                // Cold snapshot/admission drift is terminal, not permission to
                // recover another network or resume a denied subscription.
                if (coldTransitionCurrent != null) return false
                if (generation == expectedGeneration && vpnInterface === pfd) {
                    if (!selectedSubscriptionStillMatches(storageId, url)) {
                        stopForPersonalSelectionDrift()
                        return false
                    }
                    val latest = findActiveUpstreamNetwork()
                    val latestSnapshot = latest?.let { physicalNetworkSnapshots?.current(it) }
                    val recovered = latest != null && recoverOrdinaryOnHeldTun(pfd, ordinary, latest, expectedGeneration)
                    if (recovered && latest != null && latestSnapshot != null) {
                        return publishPersonalTransition(expectedGeneration, pfd, latest, latestSnapshot, storageId, url) {
                            OlcboxVpnState.activeLocation = ordinary
                            setStatus(VpnStatus.Connected)
                            OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Inactive, PersonalActivePath.Unknown)
                            updateNotification(connectedNotificationText())
                            startWatchdog()
                        }
                    } else {
                        settlePersonalFailure(pfd, "Network changed during handoff", RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
                    }
                }
            }
        }
        return false
    }

    private suspend fun selectedSubscriptionStillMatches(storageId: String, url: String): Boolean {
        val bundle = repository.getBundle()
        return bundle.activeLocationId == storageId &&
            bundle.locations.singleOrNull { it.storageId == storageId }?.subscriptionUrl == url
    }

    private suspend fun stopForPersonalSelectionDrift() {
        ++generation
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Unknown)
        setStatus(VpnStatus.Error("Selected subscription changed; reconnect"))
        updateNotification("Selected subscription changed; reconnect")
        addLog("Selected subscription changed; personal tunnel stopped")
        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
    }

    private fun abortPersonalPreflight() {
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Ordinary)
        setStatus(VpnStatus.Reconnecting)
        updateNotification(RESTRICTED_ALTERNATE_UNAVAILABLE)
        requestTransportRecovery("Personal handoff preflight unavailable", fullRestart = false,
            delayMs = NETWORK_STABILITY_GRACE_MS)
    }

    private fun schedulePersonalReturn() {
        if (personalReturnJob?.isActive == true || personalOrdinaryLocation == null) return
        val expectedGeneration = generation
        personalReturnJob = scope.launch {
            delay(NETWORK_STABILITY_GRACE_MS)
            tunnelMutex.withLock {
                if (generation == expectedGeneration && personalOrdinaryLocation != null) {
                    returnFromPersonal(expectedGeneration)
                }
            }
        }
    }

    /** On a usable Wi-Fi path, restore the selected ordinary host without releasing the VPN TUN. */
    private suspend fun returnFromPersonal(expectedGeneration: Long) {
        val pfd = vpnInterface ?: return
        val ordinary = personalOrdinaryLocation ?: return
        val bootstrap = personalActiveBootstrap ?: return
        val alternate = OlcboxVpnState.activeLocation ?: return
        val storageId = connectedStorageId ?: return
        val url = connectedPersonalSubscriptionUrl ?: return
        val network = findActiveUpstreamNetwork() ?: return
        val snapshot = physicalNetworkSnapshots?.current(network) ?: return
        if (!selectedSubscriptionStillMatches(storageId, url)) {
            stopForPersonalSelectionDrift()
            return
        }
        if (SubscriptionTunnelAccess.denial(repository.getBundle(), storageId,
                System.currentTimeMillis(), runtimePolicy) != null) {
            denyConnectedSubscriptionIfNeeded()
            return
        }
        if (snapshot.transport != UpstreamTransport.Wifi || !snapshot.usable || snapshot.captive ||
            alternate.kind != LocationKind.Olcrtc || expectedGeneration != generation
        ) return
        recoveryJob?.cancel()
        recoveryJob = null
        invalidatePersonalProof()
        watchdogJob?.cancel()
        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Switching, PersonalActivePath.Ordinary)
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Восстанавливаем обычное подключение")
        val current: suspend () -> Boolean = {
            generation == expectedGeneration && vpnInterface === pfd &&
                connectedStorageId == storageId && findActiveUpstreamNetwork() == network &&
                currentNetwork == network && selectedSubscriptionStillMatches(storageId, url)
        }
        val rollbackCurrent: suspend () -> Boolean = {
            val bundle = repository.getBundle()
            coroutineContext.ensureActive()
            val admitted = selectPersonalSubscriptionUrl(packageName, runtimePolicy, connectionMode,
                bundle, storageId) == url &&
                SubscriptionTunnelAccess.denial(bundle, storageId,
                    System.currentTimeMillis(), runtimePolicy) == null &&
                bundle.activeLocationId == storageId &&
                bundle.locations.singleOrNull { it.storageId == storageId }?.subscriptionUrl == url
            val networkMatches = generation == expectedGeneration && !stopCleanupPending &&
                vpnInterface === pfd && pfd.fileDescriptor.valid() &&
                connectedStorageId == storageId && connectedPersonalSubscriptionUrl == url &&
                OlcboxVpnState.status.value !is VpnStatus.Stopping &&
                OlcboxVpnState.activeLocation === alternate &&
                personalActiveBootstrap === bootstrap && currentNetwork == network &&
                findActiveUpstreamNetwork() == network &&
                physicalNetworkSnapshots?.networkFor(snapshot) == network
            personalReturnRollbackSnapshotReady(snapshot,
                physicalNetworkSnapshots?.current(network), networkMatches, admitted)
        }
        val handoff = PersonalBridgeHandoff(
            isCurrent = current,
            stopCurrentBridge = { stopBridgeKeepingTun(pfd) },
            stopCurrentTransport = { stopMobileAndWait().also { unbindProcessFromNetwork() } },
            startTargetTransport = { startTransport(ordinary, network, expectedGeneration, false) },
            startTargetBridge = { startBridgeOnHeldTun(pfd) },
            verifyTarget = {
                isTunBridgeReadyForActiveTransport() && verifyTunnel() != null &&
                    markVerifiedOrdinaryNetwork(network, ordinary)
            },
            restoreSource = {
                val claims = if (rollbackCurrent()) freshPersonalClaims(bootstrap) else null
                claims != null && rollbackCurrent() && stopBridgeKeepingTun(pfd) &&
                    rollbackCurrent() && stopMobileAndWait() && rollbackCurrent() &&
                    startTransport(alternate, network, expectedGeneration, false,
                        personalClaimsJson = claims, personalAdmissionCurrent = rollbackCurrent) &&
                    rollbackCurrent() && startBridgeOnHeldTun(pfd) && rollbackCurrent() &&
                    verifyTunnel() != null
            }
        )
        val outcome = try {
            handoff.switch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            settlePersonalFailure(pfd, "Ordinary return failed", "Подключение недоступно", expectedGeneration)
            return
        }
        when (outcome) {
            PersonalBridgeOutcome.TargetReady -> {
                publishPersonalTransition(expectedGeneration, pfd, network, snapshot, storageId, url) {
                    OlcboxVpnState.activeLocation = ordinary
                    personalOrdinaryLocation = null
                    personalActiveBootstrap = null
                    setStatus(VpnStatus.Connected)
                    OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Inactive, PersonalActivePath.Unknown)
                    updateNotification(connectedNotificationText())
                    startWatchdog()
                    addLog("Ordinary tunnel restored on Wi-Fi")
                }
            }
            PersonalBridgeOutcome.SourceRestored -> {
                publishPersonalTransition(expectedGeneration, pfd, network, snapshot, storageId, url) {
                    OlcboxVpnState.activeLocation = alternate
                    setStatus(VpnStatus.Reconnecting)
                    OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
                    updateNotification("Ограниченная сеть · Проверяем")
                    addLog("Ordinary transport unavailable; personal tunnel restored")
                    startWatchdog()
                    startPersonalProofLoop()
                }
            }
            PersonalBridgeOutcome.TunHeld -> {
                settlePersonalFailure(pfd, "Ordinary return incomplete", "Подключение недоступно", expectedGeneration)
            }
            PersonalBridgeOutcome.Superseded -> {
                if (generation == expectedGeneration && vpnInterface === pfd) {
                    if (!selectedSubscriptionStillMatches(storageId, url)) {
                        stopForPersonalSelectionDrift()
                        return
                    }
                    val latest = findActiveUpstreamNetwork()
                    val latestSnapshot = latest?.let { physicalNetworkSnapshots?.current(it) }
                    val latestIsWifi = latest?.let {
                        physicalNetworkSnapshots?.current(it)?.transport == UpstreamTransport.Wifi
                    } == true
                    val recovered = when {
                        latest == null -> false
                        latestIsWifi -> recoverOrdinaryOnHeldTun(pfd, ordinary, latest, expectedGeneration)
                        personalRecoverySnapshot(latest) != null -> recoverPersonalOnHeldTun(pfd, alternate, bootstrap, latest, expectedGeneration)
                        else -> false
                    }
                    if (recovered && latestIsWifi && latest != null && latestSnapshot != null) {
                        publishPersonalTransition(expectedGeneration, pfd, latest, latestSnapshot, storageId, url) {
                            OlcboxVpnState.activeLocation = ordinary
                            personalOrdinaryLocation = null
                            personalActiveBootstrap = null
                            setStatus(VpnStatus.Connected)
                            OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Inactive, PersonalActivePath.Unknown)
                            updateNotification(connectedNotificationText())
                            startWatchdog()
                        }
                    } else if (recovered && latest != null && latestSnapshot != null) {
                        publishPersonalTransition(expectedGeneration, pfd, latest, latestSnapshot, storageId, url) {
                            OlcboxVpnState.activeLocation = alternate
                            setStatus(VpnStatus.Reconnecting)
                            OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
                            updateNotification("Ограниченная сеть · Проверяем")
                            startWatchdog()
                            startPersonalProofLoop()
                        }
                    } else {
                        settlePersonalFailure(pfd, "Network changed during ordinary return", "Подключение недоступно", expectedGeneration)
                    }
                }
            }
        }
    }

    private suspend fun recoverOrdinaryOnHeldTun(
        pfd: ParcelFileDescriptor,
        ordinary: LocationConfig,
        network: Network,
        expectedGeneration: Long
    ): Boolean {
        if (generation != expectedGeneration || vpnInterface !== pfd) return false
        updateUnderlyingNetwork(network)
        if (!stopBridgeKeepingTun(pfd) || !stopMobileAndWait()) return false
        unbindProcessFromNetwork()
        return startTransport(ordinary, network, expectedGeneration, false) &&
            startBridgeOnHeldTun(pfd) && isTunBridgeReadyForActiveTransport() &&
            verifyTunnel() != null && markVerifiedOrdinaryNetwork(network, ordinary) &&
            generation == expectedGeneration && vpnInterface === pfd &&
            findActiveUpstreamNetwork() == network
    }

    /** True means both paths stopped and the existing TUN remains open. False is not a closed-path claim. */
    internal suspend fun holdTunWithoutTransport(pfd: ParcelFileDescriptor): Boolean =
        withContext(NonCancellable) {
            if (vpnInterface !== pfd) return@withContext false
            val bridgeStopped = stopBridgeKeepingTun(pfd)
            val transportStopped = stopMobileAndWait()
            unbindProcessFromNetwork()
            val heldClosed = bridgeStopped && transportStopped &&
                !isActiveTransportRunning() && !isTunBridgeReadyForActiveTransport()
            if (!heldClosed) {
                // Do not advertise a safely held TUN if either native stop timed
                // out. Release the parent descriptor and leave an explicit Error
                // requiring Stop/retry; the stale native worker may still exist.
                cleanupVpnInterface()
            }
            heldClosed
        }

    private var personalFailureTunHolder: suspend (ParcelFileDescriptor) -> Boolean = ::holdTunWithoutTransport

    private suspend fun settlePersonalFailure(
        pfd: ParcelFileDescriptor,
        reason: String,
        unavailableText: String,
        expectedGeneration: Long
    ) {
        val current = synchronized(this) {
            if (generation != expectedGeneration || vpnInterface !== pfd ||
                OlcboxVpnState.status.value is VpnStatus.Stopping ||
                OlcboxVpnState.status.value is VpnStatus.Disconnected
            ) false else {
                // The native worker is already dead. Do not keep its old Connected/Working
                // receipt visible while the TUN holder suspends to close transport threads.
                invalidatePersonalProof()
                setStatus(VpnStatus.Reconnecting)
                // Notification rendering is ancillary; never let it skip the TUN holder.
                runCatching { updateNotification("Ограниченная сеть · Проверяем") }
                true
            }
        }
        if (!current) return
        val heldClosed = personalFailureTunHolder(pfd)
        synchronized(this) {
            if (generation != expectedGeneration || OlcboxVpnState.status.value is VpnStatus.Stopping ||
                OlcboxVpnState.status.value is VpnStatus.Disconnected ||
                (vpnInterface != null && vpnInterface !== pfd) ||
                (heldClosed && (vpnInterface !== pfd || !pfd.fileDescriptor.valid()))
            ) return
            OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Unavailable, PersonalActivePath.Unknown)
            if (heldClosed) {
                setStatus(VpnStatus.Reconnecting)
                runCatching { updateNotification(unavailableText) }
                addLog("$reason; VPN tunnel held closed")
            } else {
                setStatus(VpnStatus.Error("VPN stop incomplete"))
                runCatching { updateNotification("VPN stop incomplete") }
                addLog("$reason; VPN stop incomplete, retry Stop")
            }
        }
    }

    private enum class PersonalTransportRecoveryTrigger { NativeStop, PhysicalLoss }

    /** One loss episode: retain TUN, pace fresh admission, never promote native readiness to Working. */
    private suspend fun recoverStoppedPersonalTransport(
        pfd: ParcelFileDescriptor,
        expectedGeneration: Long,
        trigger: PersonalTransportRecoveryTrigger = PersonalTransportRecoveryTrigger.NativeStop
    ) {
        // The callback holder can make native false while its old watchdog is still alive.
        // That watchdog must join the same ownership/budget, not settle and seed another cycle.
        if (generation == expectedGeneration && vpnInterface === pfd &&
            recoveryRequestedForGeneration == expectedGeneration && recoveryJob?.isActive == true
        ) return
        // Capture the originating room/link before the holder can suspend. A replacement must not
        // borrow this room merely because it reuses the same physical network or open descriptor.
        val ordinary = personalOrdinaryLocation
        val alternate = OlcboxVpnState.activeLocation
        val bootstrap = personalActiveBootstrap
        val storageId = connectedStorageId
        val url = connectedPersonalSubscriptionUrl
        settlePersonalFailure(pfd, "Personal transport recovery required (trigger=${trigger.name})",
            RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
        if (ordinary == null || alternate == null || alternate.kind != LocationKind.Olcrtc || bootstrap == null ||
            storageId == null || url == null) return
        val ownsOriginatingTransport = {
            generation == expectedGeneration && !stopCleanupPending && vpnInterface === pfd &&
                pfd.fileDescriptor.valid() && OlcboxVpnState.status.value !is VpnStatus.Stopping &&
                OlcboxVpnState.status.value !is VpnStatus.Disconnected &&
                personalOrdinaryLocation === ordinary && OlcboxVpnState.activeLocation === alternate &&
                personalActiveBootstrap === bootstrap && connectedStorageId == storageId &&
                connectedPersonalSubscriptionUrl == url && packageName == PERSONAL_TEST_PACKAGE &&
                runtimePolicy == RuntimeLocationPolicy.ExperimentalDebug && connectionMode == AndroidConnectionMode.Tun
        }
        val owned = { ownsOriginatingTransport() && OlcboxVpnState.status.value is VpnStatus.Reconnecting }
        if (!owned()) return
        recoveryJob?.cancel()
        recoveryRequestedForGeneration = expectedGeneration
        recoveryJob = scope.launch {
            var attempts = 0
            var delayMs = RECONNECT_RETRY_BASE_DELAY_MS
            try {
                while (isActive && attempts < 3) {
                    delay(delayMs)
                    val retry = tunnelMutex.withLock {
                        coroutineContext.ensureActive()
                        if (!owned()) return@withLock false
                        if (denyConnectedSubscriptionIfNeeded()) return@withLock false
                        if (!selectedSubscriptionStillMatches(storageId, url)) {
                            stopForPersonalSelectionDrift()
                            return@withLock false
                        }
                        coroutineContext.ensureActive()
                        if (!owned()) return@withLock false
                        val network = findActiveUpstreamNetwork()
                        val snapshots = physicalNetworkSnapshots
                        val snapshot = network?.let { snapshots?.current(it) }?.takeIf {
                            it.generation == expectedGeneration && snapshots?.networkFor(it) == network &&
                                it.usable && !it.captive
                        }
                        if (network != null && snapshot?.transport == UpstreamTransport.Wifi) {
                            // Expired personal room material must not block an ordinary Wi-Fi return.
                            updateUnderlyingNetwork(network)
                            schedulePersonalReturn()
                            return@withLock false
                        }
                        if (!bootstrap.isUsableAt(System.currentTimeMillis())) {
                            settlePersonalFailure(pfd, "Personal transport recovery bootstrap unavailable",
                                RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
                            return@withLock false
                        }
                        if (network == null || snapshot == null) {
                            // Waiting for current physical facts is not a room/admission attempt.
                            delayMs = NETWORK_RETRY_BASE_DELAY_MS
                            updateNotification("Waiting for network...")
                            return@withLock true
                        }
                        updateUnderlyingNetwork(network)
                        if (personalRecoverySnapshot(network) == null) {
                            delayMs = NETWORK_RETRY_BASE_DELAY_MS
                            return@withLock true
                        }
                        attempts++
                        delayMs = (RECONNECT_RETRY_BASE_DELAY_MS * (1L shl attempts))
                            .coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
                        OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
                        updateNotification("Ограниченная сеть · Проверяем")
                        addLog("Retrying personal transport (trigger=${trigger.name}, attempt=$attempts)")
                        val recovered = try {
                            recoverPersonalOnHeldTun(pfd, alternate, bootstrap, network, expectedGeneration)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        coroutineContext.ensureActive()
                        // A bridge start may publish Error after native admission succeeded. Error
                        // blocks retry until settlement, but it does not transfer resource ownership.
                        if (!ownsOriginatingTransport()) return@withLock false
                        if (denyConnectedSubscriptionIfNeeded()) return@withLock false
                        if (!ownsOriginatingTransport()) return@withLock false
                        if (recovered) {
                            val published = publishPersonalTransition(expectedGeneration, pfd, network, snapshot, storageId, url) {
                                reconnectAttempt = 0
                                recoveryRequestedForGeneration = 0L
                                recoveryJob = null
                                setStatus(VpnStatus.Reconnecting)
                                OlcboxVpnState.setPersonalPhase(PersonalTunnelPhase.Checking, PersonalActivePath.OlcRtc)
                                updateNotification("Ограниченная сеть · Проверяем")
                                addLog("Personal transport recovered; full-tunnel verification pending")
                                startWatchdog()
                                startPersonalProofLoop()
                            }
                            // Rejected publication already settles its originating TUN. A current
                            // owner can use the remaining budget instead of abandoning recovery.
                            return@withLock !published && owned()
                        }
                        settlePersonalFailure(pfd, "Personal transport recovery unavailable",
                            RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
                        owned()
                    }
                    if (!retry) return@launch
                }
                tunnelMutex.withLock {
                    if (owned()) {
                        // Exhaustion is honest Unavailable on a held TUN, not endless room joins.
                        updateNotification(RESTRICTED_ALTERNATE_UNAVAILABLE)
                        addLog("Personal transport recovery exhausted; manual reconnect required")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                tunnelMutex.withLock {
                    if (ownsOriginatingTransport()) settlePersonalFailure(pfd, "Personal transport recovery unavailable",
                        RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
                }
            } finally {
                val finishingJob = coroutineContext[Job]
                withContext(NonCancellable) {
                    tunnelMutex.withLock {
                        if (recoveryJob === finishingJob) {
                            recoveryJob = null
                            if (recoveryRequestedForGeneration == expectedGeneration) recoveryRequestedForGeneration = 0L
                        }
                    }
                }
            }
        }
    }

    private fun personalRecoverySnapshot(network: Network): PhysicalNetworkSnapshot? =
        physicalNetworkSnapshots?.current(network)?.takeIf {
            it.transport == UpstreamTransport.Cellular && it.isProbeReady && !it.captive
        }

    /** Admission may suspend; UI publication must still serialize with cleanup's Stop fence. */
    private suspend fun publishPersonalTransition(
        expectedGeneration: Long,
        pfd: ParcelFileDescriptor,
        network: Network,
        snapshot: PhysicalNetworkSnapshot,
        storageId: String,
        url: String,
        publish: () -> Unit
    ): Boolean {
        val bundle = repository.getBundle()
        coroutineContext.ensureActive()
        val admitted = selectPersonalSubscriptionUrl(packageName, runtimePolicy, connectionMode, bundle, storageId) == url &&
            SubscriptionTunnelAccess.denial(bundle, storageId, System.currentTimeMillis(), runtimePolicy) == null
        return commitOrSettlePersonalTransition(expectedGeneration, pfd, admitted, {
            connectedStorageId == storageId && connectedPersonalSubscriptionUrl == url &&
                currentNetwork == network && findActiveUpstreamNetwork() == network &&
                physicalNetworkSnapshots?.current(network) == snapshot &&
                physicalNetworkSnapshots?.networkFor(snapshot) == network && snapshot.usable && !snapshot.captive
        }, publish)
    }

    /** A rejected warm commit is not permission to leave an unmonitored transport running. */
    private suspend fun commitOrSettlePersonalTransition(
        expectedGeneration: Long,
        pfd: ParcelFileDescriptor,
        admitted: Boolean,
        transitionCurrent: () -> Boolean,
        publish: () -> Unit
    ): Boolean {
        coroutineContext.ensureActive()
        val committed = admitted &&
            commitPersonalStateIfCurrent(expectedGeneration, pfd, transitionCurrent, publish)
        if (!committed) {
            // settle rechecks the originating generation/PFD before stopping and
            // fences its post-suspend publication against Stop/new Connect.
            settlePersonalFailure(pfd, "Personal transition publication rejected",
                RESTRICTED_ALTERNATE_UNAVAILABLE, expectedGeneration)
        }
        return committed
    }

    private fun commitPersonalStateIfCurrent(
        expectedGeneration: Long,
        pfd: ParcelFileDescriptor,
        transitionCurrent: () -> Boolean,
        publish: () -> Unit
    ): Boolean = synchronized(this) {
        if (generation != expectedGeneration || vpnInterface !== pfd || !pfd.fileDescriptor.valid() ||
            OlcboxVpnState.status.value is VpnStatus.Stopping ||
            OlcboxVpnState.status.value is VpnStatus.Error ||
            OlcboxVpnState.status.value is VpnStatus.Disconnected || !transitionCurrent()
        ) return@synchronized false
        publish()
        true
    }

    private suspend fun recoverPersonalOnHeldTun(
        pfd: ParcelFileDescriptor,
        alternate: LocationConfig,
        bootstrap: PersonalBootstrapV1,
        network: Network,
        expectedGeneration: Long
    ): Boolean {
        val snapshot = personalRecoverySnapshot(network) ?: return false
        val storageId = connectedStorageId ?: return false
        val url = connectedPersonalSubscriptionUrl ?: return false
        val current: suspend () -> Boolean = {
            val bundle = repository.getBundle()
            coroutineContext.ensureActive()
            generation == expectedGeneration && vpnInterface === pfd && pfd.fileDescriptor.valid() &&
                OlcboxVpnState.status.value !is VpnStatus.Stopping &&
                connectedStorageId == storageId && connectedPersonalSubscriptionUrl == url &&
                currentNetwork == network && findActiveUpstreamNetwork() == network &&
                personalRecoverySnapshot(network) == snapshot && physicalNetworkSnapshots?.networkFor(snapshot) == network &&
                selectPersonalSubscriptionUrl(packageName, runtimePolicy, connectionMode, bundle, storageId) == url &&
                SubscriptionTunnelAccess.denial(bundle, storageId, System.currentTimeMillis(), runtimePolicy) == null
        }
        if (!current()) return false
        val claims = freshPersonalClaims(bootstrap) ?: return false
        if (!current()) return false
        updateUnderlyingNetwork(network)
        if (!stopBridgeKeepingTun(pfd) || !current() || !stopMobileAndWait() || !current()) return false
        return startTransport(alternate, network, expectedGeneration, false, personalClaimsJson = claims,
            personalAdmissionCurrent = current) && current() && startBridgeOnHeldTun(pfd) && current() &&
            isTunBridgeReadyForActiveTransport() && verifyTunnel() != null && current()
    }

    /** Records the exact DNS list passed to the direct-routing front, not a later callback value. */
    private fun captureOrdinaryRoutingFacts(
        upstream: Network,
        networkId: Long?,
        transport: UpstreamTransport?,
        directDnsAddresses: List<String>
    ) {
        ordinaryRoutingFacts = OrdinaryRoutingFacts(generation, upstream, networkId, transport, directDnsAddresses.toList())
    }

    private fun markVerifiedOrdinaryNetwork(upstream: Network, location: LocationConfig): Boolean {
        if (location.kind == LocationKind.Olcrtc) return true
        val applied = ordinaryRoutingFacts?.takeIf { it.generation == generation && it.network == upstream }
        ordinaryRoutingFacts = null
        val latest = physicalNetworkSnapshots?.current(upstream)
        val transport = applied?.transport
            ?: latest?.transport
            ?: upstream.transportOrNull()
        val dns = applied?.dnsServers
            ?: (latest?.dnsServers
            ?.takeIf { it.isNotEmpty() } ?: upstreamDnsAddresses(upstream).takeIf { it.isNotEmpty() })?.toList()
        val latestDns = latest?.dnsServers
        if (findActiveUpstreamNetwork() != upstream ||
            (applied?.networkId != null && latest?.networkId != null && applied.networkId != latest.networkId) ||
            (latest?.transport != null && latest.transport != transport) ||
            (!latestDns.isNullOrEmpty() && latestDns != dns)
        ) return false
        lastConnectedOrdinaryNetwork = upstream
        lastConnectedOrdinaryNetworkId = applied?.networkId ?: latest?.networkId
        lastConnectedOrdinaryTransport = transport
        lastConnectedOrdinaryDns = dns
        schedulePersonalBootstrapPrefetch(upstream)
        scheduleGeneralBootstrapPrefetch(upstream)
        return true
    }

    /** A verified ordinary path may refresh its private room in the background; its failure never blocks VLESS/HY2. */
    private fun schedulePersonalBootstrapPrefetch(upstream: Network) {
        val url = connectedPersonalSubscriptionUrl ?: return
        if (connectionMode != AndroidConnectionMode.Tun || upstream != currentNetwork ||
            personalBootstrapPrefetchJob?.isActive == true
        ) return
        val now = SystemClock.elapsedRealtime()
        if (lastPersonalPrefetchAtElapsedMs != 0L &&
            now - lastPersonalPrefetchAtElapsedMs < PERSONAL_PREFETCH_MIN_INTERVAL_MS
        ) return
        lastPersonalPrefetchAtElapsedMs = now
        val selectedGeneration = generation
        personalBootstrapPrefetchJob = scope.launch(Dispatchers.IO) {
            try {
                if (selectedGeneration != generation || connectedPersonalSubscriptionUrl != url) return@launch
                personalBootstrapPrefetcher(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Private bootstrap is optional until handoff is physically proven; ordinary traffic stays intact.
            }
        }
    }

    private fun retryAfterVerifiedNetworkChange(requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        scheduleTransportRetry(requestedGeneration, "network changed during verification", NETWORK_RETRY_BASE_DELAY_MS)
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        if (connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
        // A running olcRTC re-points its lookups at the new network's resolvers
        // at once; the engine applies SetDNS live and refuses a malformed list.
        val runtime = olcrtc
        if (network != null && runtime?.isRunning() == true) {
            runCatching { runtime.setDNS(upstreamDnsList(network)) }
                .onFailure { addLog("olcRTC resolvers not updated: ${it.message}") }
        }
    }

    /**
     * The engine's resolver list for [network]: its DNS servers as the system
     * lists them, then the public operator. See [UpstreamDns].
     */
    private fun upstreamDnsList(network: Network?): String {
        val servers = network
            ?.let { connectivityManager.getLinkProperties(it)?.dnsServers }
            ?.map { it.hostAddress ?: "" }
            .orEmpty()
        addLog("Resolvers from ${network?.let { getNetName(it) } ?: "no network"}: ${servers.size}")
        return UpstreamDns.list(servers)
    }

    /**
     * What the builders get for [routingMode]: the rule files on disk and the
     * network's resolvers for direct names. Files are rewritten on every start —
     * 59 KB, and the alternative is a version check that can be wrong.
     */
    internal suspend fun routingFor(upstream: Network, kind: LocationKind): Routing {
        // Android itself blocks IPv6 at the VpnService boundary.
        // Global with no rules of the user's writes no rule files and hands the
        // engine no rules.
        val directDnsAddresses = upstreamDnsAddresses(upstream)
        if (kind != LocationKind.Olcrtc) {
            val snapshot = physicalNetworkSnapshots?.current(upstream)
            captureOrdinaryRoutingFacts(
                upstream,
                snapshot?.networkId,
                snapshot?.transport ?: upstream.transportOrNull(),
                directDnsAddresses
            )
        }
        if (!routingSettings.needsRules) return Routing.Global

        val dir = File(filesDir, RULE_SETS_DIR).apply { mkdirs() }
        val routing = routingSettings.toRules(
            ruleSetDir = dir.absolutePath,
            // Use the resolver supplied by the active physical network. This
            // preserves captive portals, private DNS and carrier-specific RU
            // answers. DirectDns.Servers supplies the public fallback only
            // when Android reports no usable resolver.
            directDns = DirectDns.Servers(directDnsAddresses)
        )
        for (file in RuleSets.selected(routing)) File(dir, file.name).writeBytes(RuleSets.bytes(file))
        val own = routing.custom.direct.size + routing.custom.tunnel.size
        addLog("Routing: ${routingMode.hubSummary()}" + if (own > 0) ", $own rules of the user's" else "")
        return routing
    }

    /** The active physical network's resolvers, before the VPN replaces DNS. */
    private fun upstreamDnsAddresses(network: Network?): List<String> =
        network?.let { connectivityManager.getLinkProperties(it)?.dnsServers }
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

    private fun Network.transportOrNull(): UpstreamTransport? {
        val caps = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!caps.isUsableUpstream()) return null
        return caps.upstreamTransport()
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed (${e.javaClass.simpleName})")
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "${applicationInfo.loadLabel(packageManager)} VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    /**
     * The notification in the user's language. Its texts are the English strings the
     * service passes around; their translations are read once when the service is
     * created, and until they are the notification says the English one.
     */
    @Volatile
    private var notificationWords: Map<String, String> = emptyMap()

    private fun localized(text: String): String = notificationWords[text] ?: text

    private fun notificationText(status: String): String =
        if (restrictedNoticeActive && status in RESTRICTED_NOTICE_RECONNECT_TEXTS) {
            RESTRICTED_ALTERNATE_UNAVAILABLE
        } else status

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("${applicationInfo.loadLabel(packageManager)} ${localized(activeModeLabel())}")
            .setContentText(localized(notificationText(status)))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                localized("Stop"),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, OlcboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        OlcboxVpnState.personalUpdateTicket = null
        if (status !is VpnStatus.Connected) {
            personalWorkingNetwork = null
            personalWorkingSnapshot = null
            personalRefreshSnapshot = null
        }
        if (status !is VpnStatus.Connecting && status !is VpnStatus.Reconnecting) {
            restrictedNoticeActive = false
        }
        if (status is VpnStatus.Connected) {
            val (username, password) = upstreamLogin()
            OlcboxVpnState.channelProxy = org.olcbox.app.data.repository.SubscriptionFetchProxy(
                socksConnectHost(), activeCorePort ?: socksListenPort,
                username,
                password
            )
            if (packageName == PERSONAL_TEST_PACKAGE && personalOrdinaryLocation != null) {
                OlcboxVpnState.channelProxy?.let { publishPersonalRefreshProxy(it) }
            }
        } else {
            OlcboxVpnState.channelProxy = null
        }
        // Retire pooled sockets on migration as well as stop: they must not
        // carry a measurement from the previous network into the new session.
        if (status is VpnStatus.Connected && OlcboxVpnState.channelProbe == null) {
            OlcboxVpnState.channelProbe = org.olcbox.app.net.ChannelLatency.Session(OlcboxVpnState.channelProxy)
        } else if (status !is VpnStatus.Connected) {
            OlcboxVpnState.channelProbe?.close()
            OlcboxVpnState.channelProbe = null
        }
        OlcboxVpnState.setStatus(status)
    }

    /** A refresh borrows only an attested live session, never a nullable/direct fallback. */
    private fun publishPersonalRefreshProxy(raw: org.olcbox.app.data.repository.SubscriptionFetchProxy) {
        publishPersonalUpdateTicket(raw)
        val captured = personalRefreshSnapshot
        fun current(): Boolean = captured != null && personalRefreshSnapshot != null &&
            OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Working &&
            OlcboxVpnState.status.value is VpnStatus.Connected &&
            personalProofCurrentNow(captured, requireProofEpoch = false)
        OlcboxVpnState.channelProxy = if (captured == null) {
            org.olcbox.app.data.repository.SubscriptionFetchProxy.blocked()
        } else raw.copy(fence = org.olcbox.app.data.repository.SubscriptionFetchFence(
            allowsUrl = { candidate -> candidate == captured.url },
            current = {
                synchronized(this) { current() }
            },
            commit = { persist ->
                synchronized(this) {
                    if (!current()) false else {
                        // No suspend inside this block: Stop/cleanup cannot occur
                        // between the final guard and the authoritative file commit.
                        persist()
                        true
                    }
                }
            },
        ))
    }

    private fun publishPersonalUpdateTicket(raw: org.olcbox.app.data.repository.SubscriptionFetchProxy) {
        val captured = personalRefreshSnapshot
        fun current(observation: PersonalUpdateDiagnosticOperation? = null): Boolean {
            if (captured == null || personalRefreshSnapshot == null) {
                observation?.failedFence(PersonalUpdateDiagnosticFence.SNAPSHOT_MISSING); return false
            }
            if (OlcboxVpnState.personalTunnel.value.phase != PersonalTunnelPhase.Working) {
                observation?.failedFence(PersonalUpdateDiagnosticFence.PHASE_NOT_WORKING); return false
            }
            if (OlcboxVpnState.status.value !is VpnStatus.Connected) {
                observation?.failedFence(PersonalUpdateDiagnosticFence.STATUS_NOT_CONNECTED); return false
            }
            return personalProofCurrentNow(captured, false, observation)
        }
        OlcboxVpnState.personalUpdateTicket = if (captured == null || raw.port !in 1..65535) null else
            org.olcbox.app.update.personal.PersonalUpdateFetchTicket(
                org.olcbox.app.update.personal.PersonalUpdateSocksEndpoint(raw.host, raw.port, raw.username, raw.password),
                { synchronized(this) { current() } }, { _, action -> synchronized(this) {
                    if (!current()) false else { action(); true }
                } }, observedGuard = { observation -> synchronized(this) { current(observation) } },
                observedFence = { _, action, observation -> synchronized(this) {
                    if (!current(observation)) false else { action(); true }
                } })
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "VPN"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    private fun connectedNotificationText(): String = "${activeModeLabel()} Connected"

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "OlcboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "OlcboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return

                val userBytes = username.toByteArray()
                val passBytes = password.toByteArray()

                backendOut.write(SOCKS_AUTH_VERSION.toInt())
                backendOut.write(userBytes.size)
                backendOut.write(userBytes)
                backendOut.write(passBytes.size)
                backendOut.write(passBytes)
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != 0x00) return // 0x00 - успешно

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "OlcboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()
        /** JNI observer is process-global, including across Service recreation. */
        private val processPersonalObserverEpoch = AtomicLong()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("olcbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries (${e.javaClass.simpleName})")
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = OlcboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = OlcboxVpnActions.ACTION_STOP_VPN

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        /**
         * How long olcRTC may take to come up: the room's auth, the call's own
         * ICE and DTLS, then the handshake. The engine now gives a WB Stream
         * (LiveKit) join 25 s by itself (it was the SDK's 5 s), and a cellular
         * ICE negotiation has taken 21 s, so the 25 s the cores get is too
         * short here. The iOS tunnel waits 35 s for the same start.
         */
        private const val OLCRTC_READY_TIMEOUT_MS = 35_000L
        private const val MOBILE_STOP_TIMEOUT_MS = 5_000L
        private const val PREVIOUS_STOP_WAIT_MS = 12_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 1_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_STALLED_TX_PACKET_DELTA = 8L
        private const val WATCHDOG_STALLED_SAMPLE_LIMIT = 3
        private const val RTC_RECOVERY_GRACE_MS = 2_500L
        private const val RTC_FAILURE_WINDOW_MS = 6_000L
        private const val RTC_FAILED_RECOVERY_THRESHOLD = 1
        private const val RTC_CLOSED_RECOVERY_THRESHOLD = 2
        private const val RTC_IO_ERROR_RECOVERY_THRESHOLD = 3
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 4_000L
        private const val NETWORK_RETRY_BASE_DELAY_MS = 8_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_BACKOFF_POWER = 3
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        private const val TUN_MTU = 1500
        private const val PERSONAL_PREFETCH_MIN_INTERVAL_MS = 60_000L
        private const val PERSONAL_PROOF_RETRY_INTERVAL_MS = 15_000L
        private const val PERSONAL_PROOF_SUCCESS_INTERVAL_MS = 45_000L

        /** Under filesDir, so a cache sweep cannot take the lists out from under a running core. */
        private const val RULE_SETS_DIR = "rulesets"

        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        private const val NOTIFICATION_CHANNEL_ID = "olcbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val RESTRICTED_ALTERNATE_UNAVAILABLE = "Ограниченная сеть · Режим недоступен"
        private const val PERSONAL_TEST_PACKAGE = "tech.gatealtas.rga.personal"
        private val RESTRICTED_NOTICE_RECONNECT_TEXTS = setOf(
            "Protecting your connection", "Connecting...", "Reconnecting...", "Waiting for network...",
            "Waiting for transport...", "Verifying tunnel..."
        )
        private const val TAG = "OlcboxVpnService"

        private fun addLog(msg: String) {
            OlcboxVpnState.addLog(msg)
        }
    }
}

/** A chained transport fails when either required child exits. */
internal fun coreChainAlive(requiresBoth: Boolean, singBoxAlive: Boolean, xrayAlive: Boolean): Boolean =
    if (requiresBoth) singBoxAlive && xrayAlive else singBoxAlive || xrayAlive

/** No stale Wi-Fi fact or revoked selection may revive the personal native join on rollback. */
internal fun personalReturnRollbackSnapshotReady(
    expected: PhysicalNetworkSnapshot,
    current: PhysicalNetworkSnapshot?,
    networkMatches: Boolean,
    admitted: Boolean,
): Boolean = networkMatches && admitted && expected.usable && !expected.captive && current == expected

/** Reserve one bounded assessment window before ordinary recovery replaces its network generation. */
internal object PersonalRestrictedRecoveryDelay {
    private const val ASSESSMENT_WINDOW_MS = 45_000L

    fun apply(baseMs: Long, personalEligible: Boolean, transport: UpstreamTransport?): Long =
        if (personalEligible && transport == UpstreamTransport.Cellular) {
            maxOf(baseMs, ASSESSMENT_WINDOW_MS)
        } else baseMs
}

/** A transport swap never owns or closes the VpnService TUN. The caller keeps its PFD open. */
internal enum class PersonalBridgeOutcome { TargetReady, SourceRestored, TunHeld, Superseded }

internal class PersonalBridgeHandoff(
    private val isCurrent: suspend () -> Boolean,
    private val stopCurrentBridge: suspend () -> Boolean,
    private val stopCurrentTransport: suspend () -> Boolean,
    private val startTargetTransport: suspend () -> Boolean,
    private val startTargetBridge: suspend () -> Boolean,
    private val verifyTarget: suspend () -> Boolean,
    private val restoreSource: suspend () -> Boolean,
    private val coldTransitionCurrent: (suspend () -> Boolean)? = null
) {
    private suspend fun current(): Boolean =
        isCurrent() && (coldTransitionCurrent?.invoke() ?: true)

    suspend fun switch(): PersonalBridgeOutcome {
        if (!current()) return PersonalBridgeOutcome.Superseded
        if (!stopCurrentBridge()) return PersonalBridgeOutcome.TunHeld
        if (!current()) return PersonalBridgeOutcome.Superseded
        if (!stopCurrentTransport()) return rollback()
        if (!current()) return PersonalBridgeOutcome.Superseded
        if (!startTargetTransport()) return rollback()
        if (!current()) return PersonalBridgeOutcome.Superseded
        if (!startTargetBridge()) return rollback()
        if (!current()) return PersonalBridgeOutcome.Superseded
        if (!verifyTarget()) return rollback()
        return if (current()) PersonalBridgeOutcome.TargetReady else PersonalBridgeOutcome.Superseded
    }

    private suspend fun rollback(): PersonalBridgeOutcome {
        if (!current()) return PersonalBridgeOutcome.Superseded
        val restored = restoreSource()
        if (coldTransitionCurrent != null && !current()) return PersonalBridgeOutcome.Superseded
        return if (restored) PersonalBridgeOutcome.SourceRestored else PersonalBridgeOutcome.TunHeld
    }
}

/** Claims are one-shot native input. Never leave an old proof queued after start fails. */
internal object PersonalManagedJoinStarter {
    fun start(runtime: AndroidOlcRtcRuntime, claimsJson: String) {
        require(claimsJson.isNotBlank()) { "Personal join proof unavailable" }
        runtime.setClaimsJSON(claimsJson)
        try {
            runtime.start()
        } catch (error: Exception) {
            runCatching { runtime.setClaimsJSON("") }
            throw error
        }
    }
}

/** Never borrow another subscription's bearer or a manually imported room for the personal mode. */
internal fun selectPersonalSubscriptionUrl(
    packageName: String,
    policy: RuntimeLocationPolicy,
    mode: AndroidConnectionMode,
    bundle: LocationBundleV4,
    selectedStorageId: String?
): String? {
    if (packageName != "tech.gatealtas.rga.personal" ||
        policy != RuntimeLocationPolicy.ExperimentalDebug ||
        mode != AndroidConnectionMode.Tun ||
        selectedStorageId == null || bundle.activeLocationId != selectedStorageId
    ) return null
    val selected = bundle.locations.singleOrNull { it.storageId == selectedStorageId } ?: return null
    if (selected.location.kind != LocationKind.Vless &&
        selected.location.kind != LocationKind.Hysteria2
    ) return null
    return selected.subscriptionUrl?.takeIf { RemnawavePersonalBearer.parse(it) != null }
}

internal fun selectGeneralSubscriptionUrl(packageName: String, mode: AndroidConnectionMode,
    splitMode: AndroidSplitTunnelMode, bundle: LocationBundleV4, selectedStorageId: String?, factoryPresent: Boolean): String? {
    if (packageName != "tech.gatealtas.rga" || mode != AndroidConnectionMode.Tun ||
        splitMode != AndroidSplitTunnelMode.AllApps || !factoryPresent || selectedStorageId == null ||
        bundle.activeLocationId != selectedStorageId) return null
    val selected = bundle.locations.singleOrNull { it.storageId == selectedStorageId } ?: return null
    if (selected.location.kind != LocationKind.Vless && selected.location.kind != LocationKind.Hysteria2) return null
    return selected.subscriptionUrl?.takeIf { RemnawavePersonalBearer.parse(it) != null }
}

/** Each English text the notification can show, and the resource that says it. */
private val NOTIFICATION_TEXTS: Map<String, StringResource> = mapOf(
    "Protecting your connection" to Res.string.notification_protecting,
    "Waiting for network..." to Res.string.notification_waiting_network,
    "Waiting for transport..." to Res.string.notification_waiting_transport,
    "Connecting..." to Res.string.notification_connecting,
    "Reconnecting..." to Res.string.notification_reconnecting,
    "Verifying tunnel..." to Res.string.notification_verifying,
    "VPN Connected" to Res.string.notification_vpn_connected,
    "Proxy Connected" to Res.string.notification_proxy_connected,
    "Connection failed" to Res.string.notification_connection_failed,
    "Tunnel failed" to Res.string.notification_tunnel_failed,
    "VPN tunnel error" to Res.string.notification_tunnel_error,
    "Split tunneling error" to Res.string.notification_split_error,
    "Add a location first" to Res.string.blocked_no_location,
    "Proxy" to Res.string.notification_title_proxy,
    "Stop" to Res.string.notification_stop
)
