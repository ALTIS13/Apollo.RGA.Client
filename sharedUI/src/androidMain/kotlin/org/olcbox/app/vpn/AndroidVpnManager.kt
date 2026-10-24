package org.olcbox.app.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.net.LinkParser
import org.olcbox.app.net.AndroidCoreProcess
import org.olcbox.app.net.TransportProbe
import java.util.concurrent.atomic.AtomicInteger
import org.olcbox.app.net.LocationKind
import org.olcbox.app.net.PathLatency
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_DYNAMIC_THEME
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME_INITIALIZED
import org.olcbox.app.vpn.data.vpnPrefDataStore
import org.olcbox.app.vpn.service.OlcboxVpnActions
import org.olcbox.app.vpn.service.OlcboxVpnState
import java.io.File
import java.security.SecureRandom

class AndroidVpnManager(private val context: Context) : VpnManager {
    private val appContext = context.applicationContext
    private val olcRtcConnectionChecker by lazy { OlcRtcConnectionChecker(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _connectionMode = MutableStateFlow(AndroidConnectionMode.Tun)
    private var personalUpdateModeEpoch = 0L
    private val personalUpdateNetworkRegistration = Any()
    // Owned by this manager, not Service routing. All callback facts use the State monitor.
    private var personalUpdateNetworkClosed = false
    private var personalUpdateNetworkRevision = 0L
    private var personalUpdateDefaultNetwork: android.net.Network? = null
    private var personalUpdateDefaultCapabilities: org.olcbox.app.update.personal.PersonalUpdateOrdinaryCapabilities? = null
    // Raw copy is diagnostic-only; it never decides whether authority stays current.
    private var personalUpdateLastRawCapabilities: android.net.NetworkCapabilities? = null
    private var personalUpdateDefaultProperties: android.net.LinkProperties? = null
    private var personalUpdateNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val _proxySettings = MutableStateFlow(AndroidSocksProxySettings())
    private val _splitTunnelSettings = MutableStateFlow(AndroidSplitTunnelSettings())
    private val _dynamicThemeEnabled = MutableStateFlow(true)
    private val _installedApps = MutableStateFlow<List<AndroidInstalledApp>>(emptyList())
    private val locationsDataSource = LocationsDataSourceImpl(appContext)
    private val deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)

    override val logs: StateFlow<List<String>> = OlcboxVpnState.logs
    override val status: StateFlow<VpnStatus> = OlcboxVpnState.status
    override val isConnected: StateFlow<Boolean> = OlcboxVpnState.isConnected
    override val olcConnection: StateFlow<OlcConnectionProgress> =
        if (appContext.packageName == "tech.gatealtas.rga") OlcboxVpnState.olcConnection
        else MutableStateFlow(OlcConnectionProgress())
    override val personalTunnel: StateFlow<PersonalTunnelUiState> =
        if (appContext.packageName == "tech.gatealtas.rga.personal") {
            OlcboxVpnState.personalTunnel
        } else {
            MutableStateFlow(PersonalTunnelUiState())
        }
    override val connectedSince: StateFlow<Long?> = OlcboxVpnState.connectedSince
    override val traffic: StateFlow<TrafficCounters?> = OlcboxVpnState.traffic
    val connectionMode: StateFlow<AndroidConnectionMode> = _connectionMode.asStateFlow()
    val proxySettings: StateFlow<AndroidSocksProxySettings> = _proxySettings.asStateFlow()
    val splitTunnelSettings: StateFlow<AndroidSplitTunnelSettings> = _splitTunnelSettings.asStateFlow()
    val dynamicThemeEnabled: StateFlow<Boolean> = _dynamicThemeEnabled.asStateFlow()
    val installedApps: StateFlow<List<AndroidInstalledApp>> = _installedApps.asStateFlow()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { closePersonalUpdateNetworkMonitor() }
        if (appContext.packageName in setOf("tech.gatealtas.rga.personal", "tech.gatealtas.rga") &&
            context is androidx.lifecycle.LifecycleOwner) {
            context.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    closePersonalUpdateNetworkMonitor()
                    owner.lifecycle.removeObserver(this)
                }
            })
        }
        scope.launch {
            ensureProxySettings()
            appContext.vpnPrefDataStore.data
                .map { preferences ->
                    val mode = AndroidConnectionMode.fromValue(preferences[KEY_ANDROID_CONNECTION_MODE])
                    val proxy = AndroidSocksProxySettings(
                        host = AndroidSocksProxySettings.sanitizeHost(
                            preferences[KEY_ANDROID_SOCKS_HOST]
                        ),
                        port = AndroidSocksProxySettings.sanitizePort(
                            preferences[KEY_ANDROID_SOCKS_PORT]
                        ),
                        username = preferences[KEY_ANDROID_SOCKS_USERNAME].orEmpty(),
                        password = preferences[KEY_ANDROID_SOCKS_PASSWORD].orEmpty()
                    )
                    val splitTunnel = AndroidSplitTunnelSettings(
                        mode = AndroidSplitTunnelMode.fromValue(
                            preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE]
                        ),
                        proxyPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS].orEmpty(),
                        bypassPackages = preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS].orEmpty()
                    )
                    AndroidAppPreferences(
                        mode = mode,
                        proxy = proxy,
                        splitTunnel = splitTunnel,
                        dynamicThemeEnabled = preferences[KEY_ANDROID_DYNAMIC_THEME] != false
                    )
                }
                .collect { settings ->
                    setConnectionModeForUpdate(settings.mode)
                    _proxySettings.value = settings.proxy
                    _splitTunnelSettings.value = settings.splitTunnel
                    _dynamicThemeEnabled.value = settings.dynamicThemeEnabled
                }
        }
        refreshInstalledApps()
    }

    override fun needsPermission(): Boolean = needsPermission(_connectionMode.value)

    fun needsPermission(mode: AndroidConnectionMode): Boolean {
        return mode == AndroidConnectionMode.Tun && VpnService.prepare(context) != null
    }

    fun selectConnectionMode(mode: AndroidConnectionMode) {
        setConnectionModeForUpdate(mode)
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_CONNECTION_MODE] = mode.value
            }
        }
    }

    private fun setConnectionModeForUpdate(mode: AndroidConnectionMode) = synchronized(OlcboxVpnState) {
        if (_connectionMode.value != mode) personalUpdateModeEpoch++
        _connectionMode.value = mode
    }

    fun setDynamicThemeEnabled(enabled: Boolean) {
        _dynamicThemeEnabled.value = enabled
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_DYNAMIC_THEME] = enabled
            }
        }
    }

    fun updateProxySettings(
        host: String,
        username: String,
        password: String,
        port: Int = _proxySettings.value.port
    ) {
        val sanitizedHost = AndroidSocksProxySettings.sanitizeHost(host)
        val sanitizedUsername = username.trim().take(MAX_SOCKS_USERNAME_LENGTH)
            .ifBlank { generateProxyUsername() }
        val sanitized = password.trim().take(MAX_SOCKS_PASSWORD_LENGTH)
            .ifBlank { generateProxyPassword() }
        val sanitizedPort = AndroidSocksProxySettings.sanitizePort(port)
        _proxySettings.value = _proxySettings.value.copy(
            host = sanitizedHost,
            port = sanitizedPort,
            username = sanitizedUsername,
            password = sanitized
        )
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SOCKS_HOST] = sanitizedHost
                preferences[KEY_ANDROID_SOCKS_PORT] = sanitizedPort
                preferences[KEY_ANDROID_SOCKS_USERNAME] = sanitizedUsername
                preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = sanitized
            }
        }
    }

    fun updateProxyPassword(password: String) {
        updateProxySettings(
            host = _proxySettings.value.host,
            username = _proxySettings.value.username,
            password = password
        )
    }

    fun regenerateProxyPassword() {
        updateProxyPassword(generateProxyPassword())
    }

    fun refreshInstalledApps() {
        scope.launch {
            _installedApps.value = loadInstalledApps()
        }
    }

    fun selectSplitTunnelMode(mode: AndroidSplitTunnelMode) {
        _splitTunnelSettings.value = _splitTunnelSettings.value.copy(mode = mode)
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_MODE] = mode.value
            }
        }
    }

    fun toggleSplitTunnelApp(list: AndroidSplitTunnelList, packageName: String) {
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> {
                val packages = current.proxyPackages.toggle(packageName)
                current.copy(proxyPackages = packages)
            }

            AndroidSplitTunnelList.Bypass -> {
                val packages = current.bypassPackages.toggle(packageName)
                current.copy(bypassPackages = packages)
            }
        }

        updateSplitTunnelSettings(next)
    }

    fun setSplitTunnelApps(list: AndroidSplitTunnelList, packages: Set<String>) {
        val normalizedPackages = packages
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val current = _splitTunnelSettings.value
        val next = when (list) {
            AndroidSplitTunnelList.Proxy -> current.copy(proxyPackages = normalizedPackages)
            AndroidSplitTunnelList.Bypass -> current.copy(bypassPackages = normalizedPackages)
        }

        updateSplitTunnelSettings(next)
    }

    override fun startVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_START_VPN
            putExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE, _connectionMode.value.value)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST, _proxySettings.value.host)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT, _proxySettings.value.port)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME, _proxySettings.value.username)
            putExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD, _proxySettings.value.password)
            putExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE, _splitTunnelSettings.value.mode.value)
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS,
                ArrayList(_splitTunnelSettings.value.proxyPackages)
            )
            putStringArrayListExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS,
                ArrayList(_splitTunnelSettings.value.bypassPackages)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stopVpn() {
        val intent = Intent().apply {
            setClassName(context.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    /**
     * olcRTC is addressed by a room on somebody else's SFU and has no host to
     * reach, so its own prober is the only measurement. Everything else names a
     * server in its link, and [PathLatency] can measure the route to it.
     *
     * Until this existed the base implementation answered for olcRTC alone, and
     * a subscription of Reality and Hysteria2 met "Nothing here can be measured"
     * — true of the old code and of nothing else.
     */
    override fun canPing(locationConfig: LocationConfig): Boolean {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return false
        if (status.value is VpnStatus.Connected && config == OlcboxVpnState.activeLocation) return true
        // Disconnected olcRTC rooms are deliberately not probed.
        //
        // There is no host to probe: a room is a meeting, not an address, so the only
        // way to time one is `mobile.Ping`, which JOINS the room as a real client,
        // waits for the session to become ready and tears it down. That is a genuine
        // peer occupying a node whose whole capacity is single digits, for a number
        // that is time-to-join rather than latency and is not comparable with the ICMP
        // figures on the rows beside it. A button that says "measure" and quietly
        // connects is worth less than no button.
        if (config.kind == LocationKind.Olcrtc) return false
        return serverEndpoint(config) != null
    }

    private fun serverEndpoint(config: LocationConfig): Pair<String, Int>? =
        config.rawLink
            ?.let { LinkParser.parse(it) }
            ?.takeIf { it.host.isNotBlank() }
            ?.let { it.host to it.port }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        // Other entries retain their address probes. Only the active entry
        // measures HTTP through the existing tunnel; never join a spare room.
        if (status.value is VpnStatus.Connected && locationConfig.normalized() == OlcboxVpnState.activeLocation) {
            return measureCurrentChannel()
        }
        val config = locationConfig.normalized()
        if (config.kind != LocationKind.Olcrtc) {
            val (host, port) = serverEndpoint(config) ?: return null
            return withContext(Dispatchers.IO) { PathLatency.measure(host, port) }
        }
        return olcRtcConnectionChecker.ping(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    override suspend fun measureCurrentChannel(): Long? {
        if (status.value !is VpnStatus.Connected) return null
        val session = OlcboxVpnState.channelProbe ?: return null
        val measured = session.measure()
        return measured.takeIf { status.value is VpnStatus.Connected && OlcboxVpnState.channelProbe === session }
    }

    override val canProbeTransports: Boolean get() = true

    // Smart connect: the location's core, alone, in a work dir of its own so the
    // service's core is never touched (AndroidCoreProcess keys its dir by label).
    override suspend fun probeTransport(locationConfig: LocationConfig): Boolean? =
        TransportProbe.passes(locationConfig) { spec, config ->
            val label = "probe-${probeSerial.incrementAndGet()}"
            val core = AndroidCoreProcess(
                context = appContext,
                soName = if (TransportProbe.usesXray(spec)) "libxraycore.so" else "libsingboxcore.so",
                label = label,
                argv = { bin, file -> listOf(bin, "run", "-c", file) },
            )
            core.start(config)
            object : TransportProbe.Core {
                override fun isRunning(): Boolean = core.isRunning()
                override suspend fun stop() {
                    core.stop()
                    File(appContext.cacheDir, "olcbox-$label").deleteRecursively()
                }
            }
        }

    private val probeSerial = AtomicInteger()

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return olcRtcConnectionChecker.check(
            locationConfig = locationConfig,
            deviceId = deviceIdentityProvider.hwid()
        )
    }

    override fun subscriptionFetchProxy(): SubscriptionFetchProxy? {
        val packageName = appContext.packageName
        var generalSelected = false
        var generalCapture: org.olcbox.app.vpn.service.GeneralControlCapture? = null
        val selected = synchronized(OlcboxVpnState) {
            // Phase, capture and fallback are one snapshot. Missing capture never means direct.
            if (packageName == "tech.gatealtas.rga" &&
                OlcboxVpnState.generalTunnel.value.phase != org.olcbox.app.vpn.service.GeneralTunnelPhase.Inactive
            ) {
                generalSelected = true
                generalCapture = OlcboxVpnState.generalControlCapture
                null
            } else {
                val personal = personalTunnel.value
                val currentStatus = status.value
                if (packageName == "tech.gatealtas.rga.personal" && personal.phase != PersonalTunnelPhase.Inactive) {
                    OlcboxVpnState.channelProxy.takeIf {
                        currentStatus is VpnStatus.Connected && personal.phase == PersonalTunnelPhase.Working &&
                            personal.activePath == PersonalActivePath.OlcRtc && it?.fence != null
                    } ?: SubscriptionFetchProxy.blocked()
                } else {
                    val proxy = OlcboxVpnState.channelProxy.takeIf { currentStatus is VpnStatus.Connected }
                    // Before GENERAL publishes its phase, a handoff/recovery is already in flight.
                    // Ordinary HTTP is allowed without a tunnel only after actual Disconnected.
                    if (packageName == "tech.gatealtas.rga" && currentStatus !is VpnStatus.Disconnected)
                        proxy ?: SubscriptionFetchProxy.blocked() else proxy
                }
            }
        }
        // Capture validation may acquire Service; never call it while holding State.
        return if (generalSelected) generalCapture?.subscriptionProxy() ?: SubscriptionFetchProxy.blocked() else selected
    }

    /** Select references under State only; never acquire a Service guard while holding it. */
    fun personalUpdateFetchRoute(): org.olcbox.app.update.personal.PersonalUpdateFetchRoute =
        updateFetchRoute(org.olcbox.app.update.personal.OwnServerUpdateProfile.Personal)

    /** Public own-server updates borrow only the existing ordinary Network authority. No Olc grant. */
    fun publicOwnServerUpdateFetchRoute(profile: org.olcbox.app.update.personal.OwnServerUpdateProfile.PublicOwnServer):
        org.olcbox.app.update.personal.PersonalUpdateFetchRoute = updateFetchRoute(profile)

    private fun updateFetchRoute(profile: org.olcbox.app.update.personal.OwnServerUpdateProfile):
        org.olcbox.app.update.personal.PersonalUpdateFetchRoute {
        if (profile is org.olcbox.app.update.personal.OwnServerUpdateProfile.PublicOwnServer && appContext.packageName == profile.packageName) {
            val general = synchronized(OlcboxVpnState) {
                OlcboxVpnState.generalTunnel.value.phase != org.olcbox.app.vpn.service.GeneralTunnelPhase.Inactive
            }
            if (general) {
                val capture = synchronized(OlcboxVpnState) { OlcboxVpnState.generalControlCapture }
                return if (Build.VERSION.SDK_INT in 31..37 && scope.isActive &&
                    appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
                    capture?.current() == true) org.olcbox.app.update.personal.PersonalUpdateFetchRoute.GeneralSelected(
                    org.olcbox.app.update.personal.GeneralUpdateFetchTicket(capture,profile))
                else org.olcbox.app.update.personal.PersonalUpdateFetchRoute.Blocked(org.olcbox.app.update.personal.PersonalUpdateRouteBlock.NOT_READY)
            }
        }
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        // Registration is outside State. Callback/unregister locks never acquire a Service monitor.
        val needsOrdinaryMonitor = synchronized(OlcboxVpnState) {
            appContext.packageName == profile.packageName && OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive
        }
        val ordinaryMonitorReady = needsOrdinaryMonitor && ensurePersonalUpdateNetworkMonitor(cm)
        val network = cm.activeNetwork
        val capabilities = org.olcbox.app.update.personal.personalUpdateOrdinaryCapabilities(network?.let(cm::getNetworkCapabilities))
        val properties = network?.let(cm::getLinkProperties)?.let(::copyPersonalUpdateLinkProperties)
        return synchronized(OlcboxVpnState) {
            val blocked = org.olcbox.app.update.personal.PersonalUpdateFetchRoute.Blocked(
                org.olcbox.app.update.personal.PersonalUpdateRouteBlock.NOT_READY)
            if (appContext.packageName != profile.packageName || profile is org.olcbox.app.update.personal.OwnServerUpdateProfile.PublicOwnServer &&
                (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
                    Build.VERSION.SDK_INT !in 31..37)) return@synchronized blocked
            val personal = OlcboxVpnState.personalTunnel.value
            val currentStatus = status.value
            if (personal.phase != PersonalTunnelPhase.Inactive) {
                if (profile is org.olcbox.app.update.personal.OwnServerUpdateProfile.PublicOwnServer) return@synchronized blocked
                val proxy = OlcboxVpnState.channelProxy
                val ticket = OlcboxVpnState.personalUpdateTicket
                return@synchronized if (currentStatus is VpnStatus.Connected &&
                    personal.phase == PersonalTunnelPhase.Working && personal.activePath == PersonalActivePath.OlcRtc &&
                    proxy?.fence != null && ticket != null) org.olcbox.app.update.personal.PersonalUpdateFetchRoute.Selected(ticket) else blocked
            }
            if ((currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Disconnected) ||
                !ordinaryMonitorReady || !scope.isActive || personalUpdateNetworkClosed ||
                network == null || capabilities == null || properties == null ||
                personalUpdateDefaultNetwork != network || personalUpdateDefaultCapabilities != capabilities ||
                personalUpdateDefaultProperties != properties) return@synchronized blocked
            if (currentStatus is VpnStatus.Connected && (OlcboxVpnState.activeLocation?.kind == LocationKind.Olcrtc ||
                OlcboxVpnState.channelProxy == null || OlcboxVpnState.channelProxy?.fence != null ||
                OlcboxVpnState.personalUpdateTicket != null) || currentStatus is VpnStatus.Disconnected &&
                (OlcboxVpnState.channelProxy != null || OlcboxVpnState.personalUpdateTicket != null)) return@synchronized blocked
            val epoch = OlcboxVpnState.personalPhaseEpoch()
            val channel = OlcboxVpnState.channelProxy
            val location = OlcboxVpnState.activeLocation
            val mode = connectionMode.value
            val modeEpoch = personalUpdateModeEpoch
            val networkRevision = personalUpdateNetworkRevision
            val since = OlcboxVpnState.connectedSince.value
            var retired = false
            fun current(): Boolean {
                if (retired) return false
                val unchanged = OlcboxVpnState.personalTunnel.value.phase == PersonalTunnelPhase.Inactive &&
                scope.isActive && !personalUpdateNetworkClosed && personalUpdateNetworkCallback != null &&
                personalUpdateNetworkRevision == networkRevision && personalUpdateDefaultNetwork == network &&
                OlcboxVpnState.personalPhaseEpoch() == epoch && status.value == currentStatus &&
                OlcboxVpnState.channelProxy === channel && OlcboxVpnState.activeLocation === location &&
                connectionMode.value == mode && personalUpdateModeEpoch == modeEpoch && OlcboxVpnState.connectedSince.value == since &&
                cm.activeNetwork == network && org.olcbox.app.update.personal.personalUpdateOrdinaryCapabilities(cm.getNetworkCapabilities(network)) == capabilities &&
                cm.getLinkProperties(network) == properties
                if (!unchanged) retired = true
                return unchanged
            }
            org.olcbox.app.update.personal.PersonalUpdateFetchRoute.Ordinary(network,
                { synchronized(OlcboxVpnState) { current() } }, { action -> synchronized(OlcboxVpnState) {
                    if (!current()) false else { action(); true }
                } }, profile = profile)
        }
    }

    private fun ensurePersonalUpdateNetworkMonitor(cm: android.net.ConnectivityManager): Boolean =
        synchronized(personalUpdateNetworkRegistration) registration@ {
            if (Build.VERSION.SDK_INT !in 31..37 || !scope.isActive) return@registration false
            val existing = synchronized(OlcboxVpnState) {
                when { personalUpdateNetworkClosed -> -1; personalUpdateNetworkCallback != null -> 1; else -> 0 }
            }
            if (existing < 0) return@registration false
            if (existing > 0) return@registration true
            val initial = cm.activeNetwork
            val rawCaps = initial?.let(cm::getNetworkCapabilities)?.let { android.net.NetworkCapabilities(it) }
            val caps = org.olcbox.app.update.personal.personalUpdateOrdinaryCapabilities(rawCaps)
            val link = initial?.let(cm::getLinkProperties)?.let(::copyPersonalUpdateLinkProperties)
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = synchronized(OlcboxVpnState) {
                    if (personalUpdateNetworkCallback !== this || personalUpdateNetworkClosed) return@synchronized
                    if (personalUpdateDefaultNetwork != network) {
                        personalUpdateNetworkRevision++
                        personalUpdateDefaultNetwork = network
                        personalUpdateDefaultCapabilities = null; personalUpdateLastRawCapabilities = null; personalUpdateDefaultProperties = null
                    }
                }
                override fun onLost(network: android.net.Network) = synchronized(OlcboxVpnState) {
                    if (personalUpdateNetworkCallback !== this || personalUpdateNetworkClosed || personalUpdateDefaultNetwork != network) return@synchronized
                    personalUpdateNetworkRevision++
                    personalUpdateDefaultNetwork = null
                    personalUpdateDefaultCapabilities = null; personalUpdateLastRawCapabilities = null; personalUpdateDefaultProperties = null
                }
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) = synchronized(OlcboxVpnState) {
                    if (personalUpdateNetworkCallback !== this || personalUpdateNetworkClosed || personalUpdateDefaultNetwork != network) return@synchronized
                    val copied = org.olcbox.app.update.personal.personalUpdateOrdinaryCapabilities(capabilities)
                    if (personalUpdateDefaultCapabilities != copied) {
                        personalUpdateNetworkRevision++; personalUpdateDefaultCapabilities = copied
                    } else if (appContext.packageName == "tech.gatealtas.rga.personal" &&
                        appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        personalUpdateLastRawCapabilities?.let { previous ->
                            val signal = previous.signalStrength != capabilities.signalStrength
                            val bandwidth = previous.linkUpstreamBandwidthKbps != capabilities.linkUpstreamBandwidthKbps ||
                                previous.linkDownstreamBandwidthKbps != capabilities.linkDownstreamBandwidthKbps
                            val transportInfo = previous.transportInfo != capabilities.transportInfo
                            if (signal || bandwidth || transportInfo) android.util.Log.d("RgaPersonalUpdate",
                                "ORDINARY_TELEMETRY_IGNORED signal=$signal bandwidth=$bandwidth transportInfo=$transportInfo")
                        }
                    }
                    personalUpdateLastRawCapabilities = android.net.NetworkCapabilities(capabilities)
                }
                override fun onLinkPropertiesChanged(network: android.net.Network, properties: android.net.LinkProperties) = synchronized(OlcboxVpnState) {
                    if (personalUpdateNetworkCallback !== this || personalUpdateNetworkClosed || personalUpdateDefaultNetwork != network) return@synchronized
                    val copied = copyPersonalUpdateLinkProperties(properties)
                    if (personalUpdateDefaultProperties != copied) {
                        personalUpdateNetworkRevision++; personalUpdateDefaultProperties = copied
                    }
                }
            }
            val installed = synchronized(OlcboxVpnState) {
                if (personalUpdateNetworkClosed || !scope.isActive) false else {
                    personalUpdateNetworkRevision++
                    personalUpdateDefaultNetwork = initial
                    personalUpdateDefaultCapabilities = caps; personalUpdateLastRawCapabilities = rawCaps; personalUpdateDefaultProperties = link
                    personalUpdateNetworkCallback = callback
                    true
                }
            }
            if (!installed) return@registration false
            try { cm.registerDefaultNetworkCallback(callback); true }
            catch (_: Exception) {
                synchronized(OlcboxVpnState) {
                    if (personalUpdateNetworkCallback === callback) {
                        personalUpdateNetworkCallback = null; personalUpdateNetworkRevision++
                    }
                }
                false
            }
        }

    private fun closePersonalUpdateNetworkMonitor() = synchronized(personalUpdateNetworkRegistration) {
        val callback = synchronized(OlcboxVpnState) {
            personalUpdateNetworkClosed = true; personalUpdateNetworkRevision++
            personalUpdateNetworkCallback.also {
                personalUpdateNetworkCallback = null; personalUpdateDefaultNetwork = null
                personalUpdateDefaultCapabilities = null; personalUpdateLastRawCapabilities = null; personalUpdateDefaultProperties = null
            }
        }
        callback?.let {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        Unit
    }

    private fun copyPersonalUpdateLinkProperties(original: android.net.LinkProperties): android.net.LinkProperties {
        val parcel = android.os.Parcel.obtain()
        return try {
            original.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            android.net.LinkProperties.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    override suspend fun diagnosticsLog(): String = withContext(Dispatchers.IO) { buildString {
        val verbose = locationsDataSource.loadLocationBundle()?.routing?.verboseDebugLogs == true
        // Xray output can contain arbitrary provider credentials, not merely
        // patterns LogScrubber recognizes. Withhold it globally, including
        // stale files after disconnect or switching to another profile.
        // Preserve the existing bounded sing-box diagnostics path.
        val maxLines = if (verbose) MAX_EXPORTED_CORE_LOG_LINES else MAX_EXPORTED_STANDARD_LOG_LINES
        appendLine("Xray raw diagnostics withheld")
        listOf("singbox").forEach { core ->
            val log = File(File(appContext.cacheDir, "olcbox-$core"), "$core.log")
            val lines = runCatching {
                org.olcbox.app.net.AndroidCoreProcess.readLogTail(log, maxLines)
            }.getOrElse { error -> listOf("could not read ${log.name}: ${error.message}") }
            if (lines.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("--- $core core ---")
                lines.forEach(::appendLine)
            }
        }
    }.trimEnd() }


    private suspend fun ensureProxySettings() {
        appContext.vpnPrefDataStore.edit { preferences ->
            val username = preferences[KEY_ANDROID_SOCKS_USERNAME]
            val usernameInitialized = preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] == true
            if (username.isNullOrBlank() || (!usernameInitialized && username == LEGACY_DEFAULT_USERNAME)) {
                preferences[KEY_ANDROID_SOCKS_USERNAME] = generateProxyUsername()
            }
            preferences[KEY_ANDROID_SOCKS_USERNAME_INITIALIZED] = true
            if (preferences[KEY_ANDROID_SOCKS_PASSWORD].isNullOrBlank()) {
                preferences[KEY_ANDROID_SOCKS_PASSWORD] = generateProxyPassword()
            }
            preferences[KEY_ANDROID_SOCKS_HOST] = AndroidSocksProxySettings.sanitizeHost(
                preferences[KEY_ANDROID_SOCKS_HOST]
            )
            preferences[KEY_ANDROID_SOCKS_PORT] = AndroidSocksProxySettings.sanitizePort(
                preferences[KEY_ANDROID_SOCKS_PORT]
            )
        }
    }

    private suspend fun loadInstalledApps(): List<AndroidInstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }
        val launcherApps = resolveInfos
            .mapNotNull { it.activityInfo?.applicationInfo }

        val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        (launcherApps + installedApps)
            .filter { it.packageName != appContext.packageName }
            .distinctBy { it.packageName }
            .map { appInfo ->
                AndroidInstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    isSystem = appInfo.isSystemApp()
                )
            }
            .sortedWith(compareBy<AndroidInstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return flags and systemFlags != 0
    }

    private fun generateProxyPassword(): String {
        return buildString(PROXY_PASSWORD_LENGTH) {
            repeat(PROXY_PASSWORD_LENGTH) {
                append(PROXY_PASSWORD_ALPHABET[random.nextInt(PROXY_PASSWORD_ALPHABET.length)])
            }
        }
    }

    private fun generateProxyUsername(): String {
        return buildString(PROXY_USERNAME_PREFIX.length + PROXY_USERNAME_RANDOM_LENGTH) {
            append(PROXY_USERNAME_PREFIX)
            repeat(PROXY_USERNAME_RANDOM_LENGTH) {
                append(PROXY_USERNAME_ALPHABET[random.nextInt(PROXY_USERNAME_ALPHABET.length)])
            }
        }
    }

    private fun Set<String>.toggle(value: String): Set<String> {
        return if (value in this) this - value else this + value
    }

    private fun updateSplitTunnelSettings(settings: AndroidSplitTunnelSettings) {
        _splitTunnelSettings.value = settings
        scope.launch {
            appContext.vpnPrefDataStore.edit { preferences ->
                preferences[KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS] = settings.proxyPackages
                preferences[KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS] = settings.bypassPackages
            }
        }
    }

    private data class AndroidAppPreferences(
        val mode: AndroidConnectionMode,
        val proxy: AndroidSocksProxySettings,
        val splitTunnel: AndroidSplitTunnelSettings,
        val dynamicThemeEnabled: Boolean
    )

    private companion object {
        const val LEGACY_DEFAULT_USERNAME = "olcbox"
        const val PROXY_USERNAME_PREFIX = "olcbox"
        const val PROXY_USERNAME_RANDOM_LENGTH = 8
        const val MAX_SOCKS_USERNAME_LENGTH = 64
        const val PROXY_PASSWORD_LENGTH = 24
        const val MAX_SOCKS_PASSWORD_LENGTH = 64
        const val PROXY_USERNAME_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val PROXY_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val DEFAULT_LOCATION_PING_PARALLELISM = 4
        const val MAX_EXPORTED_CORE_LOG_LINES = 2_000
        const val MAX_EXPORTED_STANDARD_LOG_LINES = 200
        val random = SecureRandom()
    }
}
