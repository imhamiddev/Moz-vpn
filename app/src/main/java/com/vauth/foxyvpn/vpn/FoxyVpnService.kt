package com.vauth.foxyvpn.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.vauth.foxyvpn.FoxyVpnApp
import com.vauth.foxyvpn.MainActivity
import com.vauth.foxyvpn.data.AppLogger
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.data.GUARDIAN_ENDPOINT_DEFAULT
import com.vauth.foxyvpn.data.GuardianClient
import com.vauth.foxyvpn.data.ProxyPass
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.QuotaExceededError
import com.vauth.foxyvpn.data.RateLimitedError
import com.vauth.foxyvpn.data.RECOMMENDED_COUNTRY_CODE
import com.vauth.foxyvpn.data.ServerListClient
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TokenInvalidError
import com.vauth.foxyvpn.data.TokenStore
import com.vauth.foxyvpn.data.formatBytesPerSecond
import com.vauth.foxyvpn.data.model.ConnectionState
import com.vauth.foxyvpn.data.model.ProxyCandidate
import com.vauth.foxyvpn.data.model.RuntimeAuth
import com.vauth.foxyvpn.vpn.socks.LocalHttpProxyServer
import com.vauth.foxyvpn.vpn.socks.LocalSocks5Server
import com.vauth.foxyvpn.vpn.socks.ProxyCredentials
import com.vauth.foxyvpn.vpn.tun.HevSocks5Tunnel
import com.vauth.foxyvpn.vpn.tun.HevSocks5TunnelConfig
import com.vauth.foxyvpn.vpn.upstream.EdgeAddressResolver
import com.vauth.foxyvpn.vpn.upstream.H2UpstreamSession
import com.vauth.foxyvpn.vpn.upstream.UpstreamProxyConfig
import com.vauth.foxyvpn.vpn.upstream.UpstreamSession
import com.vauth.foxyvpn.widget.QuickConnectWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class VpnLiveStats(
    val txRateBytesPerSec: Long = 0L,
    val rxRateBytesPerSec: Long = 0L,
    val sessionBytes: Long = 0L,
)

private const val TAG = "FoxyVpnService"
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val SERVER_LIST_FETCH_TIMEOUT_MS = 15_000L

private const val UPSTREAM_WATCHDOG_INTERVAL_MS = 2_000L

private const val RECONNECT_FAILURES_BEFORE_WARNING = 5
private const val RECONNECT_BACKOFF_BASE_MS = 3_000L
private const val RECONNECT_BACKOFF_CAP_MS = 60_000L

private const val RECONNECT_FAILURES_BEFORE_EDGE_ROTATION = 2

private const val MAX_ALTERNATE_EDGES = 3

private const val UNHEALTHY_REDIAL_COOLDOWN_MS = 30_000L

private const val PROXY_PASS_RENEWAL_FRACTION = 0.5
private const val PROXY_PASS_RENEWAL_SAFETY_MARGIN_MS = 30_000L

private const val PROXY_PASS_RENEWAL_FLOOR_MS = 15_000L

private const val PROXY_PASS_RENEWAL_CEILING_MS = 30 * 60_000L

private const val PROXY_PASS_RENEWAL_FALLBACK_MS = 4 * 60_000L

private const val PROXY_PASS_RENEWAL_RETRY_MS = 30_000L
private const val PROXY_PASS_REUSE_MARGIN_SECONDS = 120L
private const val LOOPBACK_BIND_ADDRESS = "127.0.0.1"
private const val PENDING_RELEASE_WAIT_MS = 3_000L

private const val POST_TUN_SETTLE_MS = 250L

private const val INITIAL_DIAL_SETTLE_MS = 300L

private const val INITIAL_DIAL_MAX_ATTEMPTS = MAX_ALTERNATE_EDGES + 1
private const val INITIAL_DIAL_BACKOFF_BASE_MS = 2_000L
private const val INITIAL_DIAL_BACKOFF_CAP_MS = 8_000L

private const val SPEED_UPDATE_INTERVAL_MS = 2_000L

// With the screen off nobody sees the speed readout, so sample far less often.
private const val SPEED_UPDATE_INTERVAL_SCREEN_OFF_MS = 10_000L

private const val TUN_ADDRESS_IPV6 = "fd00:1:fd00:1:fd00:1:fd00:1"
private const val TUN_PREFIX_LENGTH_IPV6 = 128

private const val WAKE_LOCK_TAG = "com.vauth.foxyvpn:vpn"
private const val WIFI_LOCK_TAG = "com.vauth.foxyvpn:vpn-wifi"

/** Extra wait requested by the server's Retry-After on a 429, capped so we never stall for long. */
private fun rateLimitHintMs(error: Throwable?): Long {
    val seconds = (error as? RateLimitedError)?.retryAfterSeconds ?: return 0L
    return seconds.coerceIn(1L, 60L) * 1_000L
}

private fun fullJitterBackoffMs(attempt: Int, baseMs: Long, capMs: Long): Long {
    val exponential = baseMs * (1L shl attempt.coerceIn(0, 10))
    val upperBound = exponential.coerceAtMost(capMs)
    return (upperBound.toDouble() * Math.random()).toLong().coerceAtLeast(baseMs / 4)
}

private fun proxyPassRenewalDelayMs(expiresAtEpochSeconds: Long?): Long {
    if (expiresAtEpochSeconds == null) return PROXY_PASS_RENEWAL_FALLBACK_MS
    val remainingMs = expiresAtEpochSeconds * 1_000L - System.currentTimeMillis()
    if (remainingMs <= 0L) return PROXY_PASS_RENEWAL_FLOOR_MS
    val atHalfLife = (remainingMs * PROXY_PASS_RENEWAL_FRACTION).toLong()
    val beforeExpiry = remainingMs - PROXY_PASS_RENEWAL_SAFETY_MARGIN_MS
    return minOf(atHalfLife, beforeExpiry)
        .coerceAtMost(PROXY_PASS_RENEWAL_CEILING_MS)
        .coerceAtLeast(PROXY_PASS_RENEWAL_FLOOR_MS)
}

class FoxyVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val opMutex = Mutex()

    @Volatile private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var socksServer: LocalSocks5Server? = null
    @Volatile private var httpProxyServer: LocalHttpProxyServer? = null
    @Volatile private var upstreamSession: UpstreamSession? = null
    @Volatile private var connectJob: Job? = null
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var speedJob: Job? = null

    @Volatile private var tokenRenewalJob: Job? = null

    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var tunnelInterfaceActive = false

    @Volatile private var boundNetwork: Network? = null

    @Volatile private var reportedUnderlying: Network? = null

    /** True when the network the live upstream session was dialed on disappeared (e.g. Wi-Fi dropped). */
    @Volatile private var boundNetworkLostWhileConnected = false

    @Volatile private var lastUnhealthyRedialAt = 0L

    @Volatile private var statusLabel: String = "Connecting\u2026"

    @Volatile private var foregroundActive = false

    @Volatile private var lastNotificationText: String? = null

    private class SessionResources(
        val tunFd: ParcelFileDescriptor?,
        val socksServer: LocalSocks5Server?,
        val httpProxyServer: LocalHttpProxyServer?,
        val upstreamSession: UpstreamSession?,
    ) {
        val isEmpty: Boolean get() = tunFd == null && socksServer == null && httpProxyServer == null && upstreamSession == null
    }

    // Bumped from the main thread and IO coroutines alike, so increments must be atomic.
    private val generationCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val connectionGeneration: Int get() = generationCounter.get()
    private fun nextGeneration(): Int = generationCounter.incrementAndGet()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            reportUnderlyingNetwork(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            reportUnderlyingNetwork(network, capabilities)
        }

        override fun onLost(network: Network) {
            if (boundNetwork != network) return
            boundNetwork = null
            reportedUnderlying = null
            // The upstream session's socket lives on the network that just vanished, so it is already
            // dead even though keepalives would take a while to notice. Remember that, so that binding
            // the next network (below) retires it right away.
            if (_state.value == ConnectionState.CONNECTED && upstreamSession != null) {
                boundNetworkLostWhileConnected = true
            }

            if (tunnelInterfaceActive) {
                runCatching { setUnderlyingNetworks(null) }
            }

            // Another network (e.g. mobile data) may already be up and will not announce itself again.
            firstUsableNetwork()?.takeIf { it != network }?.let { fallback ->
                getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(fallback)?.let { caps ->
                    reportUnderlyingNetwork(fallback, caps)
                }
            }
        }
    }

    private fun reportUnderlyingNetwork(
        network: Network,
        capabilities: NetworkCapabilities? = null,
    ) {
        val caps = capabilities
            ?: getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(network)

        if (caps == null) return

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

        if (tunnelInterfaceActive && reportedUnderlying != network) {
            reportedUnderlying = network
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
                .onFailure {

                    reportedUnderlying = null
                    AppLogger.w(TAG, "failed to report the underlying network to the platform", it)
                }
        }

        val previous = boundNetwork
        if (previous == network) return

        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && previous != null) return
        boundNetwork = network
        runCatching { ControlPlaneHttp.client.connectionPool.evictAll() }
        // A different network has different reachability: drop cached DoH answers and endpoint backoff.
        EdgeAddressResolver.invalidate()
        // previous == null is normally the very first binding of this connection (nothing to retire),
        // unless the previous network was lost while connected -- then the old session is dead.
        if (previous == null && !boundNetworkLostWhileConnected) return
        boundNetworkLostWhileConnected = false

        if (_state.value != ConnectionState.CONNECTED) return
        val session = upstreamSession ?: return

        AppLogger.i(
            TAG,
            "underlying network changed; closing the upstream session bound to the old one so it is redialed now " +
                "(its socket would otherwise stay ESTABLISHED and look healthy for minutes)",
        )
        runCatching { session.close() }
    }

    private fun onUpstreamSessionUnhealthy() {
        if (_state.value != ConnectionState.CONNECTED) return
        val session = upstreamSession ?: return
        val now = System.currentTimeMillis()
        val sinceLast = now - lastUnhealthyRedialAt
        if (lastUnhealthyRedialAt != 0L && sinceLast < UNHEALTHY_REDIAL_COOLDOWN_MS) {
            AppLogger.d(
                TAG,
                "ignoring an unhealthy-session verdict ${sinceLast}ms after the last rebuild " +
                    "(cooldown ${UNHEALTHY_REDIAL_COOLDOWN_MS}ms)",
            )
            return
        }
        lastUnhealthyRedialAt = now
        AppLogger.w(
            TAG,
            "the local proxy reports the upstream session is failing as a whole rather than for one destination; " +
                "closing it so the watchdog redials",
        )
        runCatching { session.close() }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching {

            val underlyingNetworks = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            getSystemService(ConnectivityManager::class.java)
                ?.registerNetworkCallback(underlyingNetworks, networkCallback)
        }.onFailure { AppLogger.w(TAG, "could not register a network callback; handovers will be slower to detect", it) }

        scope.launch {
            _state.collect { state ->
                QuickConnectWidgetProvider.updateAll(this@FoxyVpnService, state)
            }
        }
    }

    override fun onDestroy() {
        ControlPlaneHttp.socketProtector = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        connectJob?.cancel()
        connectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        nextGeneration()
        // Stopping the native tunnel and closing sockets/Netty can block, so never do it on the main
        // thread (ANR risk). A single worker keeps releases in order; connect() waits for it.
        val doomed = detachResources()
        pendingRelease = runCatching {
            releaseExecutor.submit { releaseResources(doomed, stopNativeTunnel = true) }
        }.getOrElse {
            releaseResources(doomed, stopNativeTunnel = true)
            null
        }
        releaseWakeLocks()
        foregroundActive = false
        lastNotificationText = null
        boundNetwork = null
        reportedUnderlying = null
        boundNetworkLostWhileConnected = false
        scope.cancel()
        _state.value = ConnectionState.DISCONNECTED
        QuickConnectWidgetProvider.updateAll(this, ConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    private fun acquireWakeLocks() {
        if (wifiLock == null) {
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: error("WifiManager unavailable")
                // FULL_HIGH_PERF is deprecated (a no-op on Android 14+) and keeps the radio awake the whole
                // session, which drains the battery. From Android 10 use the low-latency mode instead; the
                // system only honours it while the app is in use, so it costs nothing in the background.
                // Older releases have no alternative, so they keep the previous behaviour.
                @Suppress("DEPRECATION")
                val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifi.createWifiLock(wifiMode, WIFI_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { AppLogger.d(TAG, "Wi-Fi high-performance lock unavailable: ${it.message}") }
        }
    }

    private fun releaseWakeLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AppLogger.d(TAG, "error releasing the Wi-Fi lock: ${it.message}") }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val action = intent?.action ?: ACTION_CONNECT
        if (action == ACTION_CONNECT || action == VpnService.SERVICE_INTERFACE) {

            if (_state.value != ConnectionState.DISCONNECTED) {
                AppLogger.d(TAG, "ignoring duplicate connect request while ${_state.value}")

                enterForeground(statusLabel)
                return START_STICKY
            }
            if (intent == null) {
                AppLogger.i(TAG, "the service was restarted by the system; bringing the tunnel back up")
            }
            _state.value = ConnectionState.CONNECTING
            _lastError.value = null
            statusLabel = "Connecting\u2026"
            enterForeground(statusLabel)

            acquireWakeLocks()
            connectJob = scope.launch { opMutex.withLock { connect() } }

            return START_STICKY
        } else if (action == ACTION_RECONNECT) {
            requestReconnect("location changed by the user")
            return if (_state.value == ConnectionState.DISCONNECTED) START_NOT_STICKY else START_STICKY
        } else if (action == ACTION_DISCONNECT) {
            requestDisconnect("requested by the user")
        }
        return START_NOT_STICKY
    }

    /**
     * Re-dials the tunnel with whatever location is currently saved in [ProxyStateStore].
     * Used when the user picks a different location while already connected/connecting.
     * Does nothing when the tunnel is not active (the saved location is used on next connect).
     */
    private fun requestReconnect(reason: String) {
        if (_state.value == ConnectionState.DISCONNECTED) {
            if (connectJob == null && watchdogJob == null) {
                releaseWakeLocks()
                stopSelf()
            }
            return
        }
        AppLogger.i(TAG, "reconnect: $reason")

        nextGeneration()
        _state.value = ConnectionState.CONNECTING
        _lastError.value = null
        statusLabel = "Connecting\u2026"
        _vpnLiveStats.value = VpnLiveStats()
        _connectedAtEpochMs.value = null
        _pingLatencyMs.value = null
        _vpnEdgeIp.value = null
        _tunnelDegraded.value = false
        _connectedEndpoint.value = null
        connectJob?.cancel()
        watchdogJob?.cancel()
        watchdogJob = null
        val doomed = detachResources()
        updateNotification(statusLabel)

        connectJob = scope.launch {
            opMutex.withLock {
                releaseResources(doomed, stopNativeTunnel = true)
                connect()
            }
        }
    }

    private fun requestDisconnect(reason: String) {
        val idle = _state.value == ConnectionState.DISCONNECTED &&
            connectJob == null &&
            watchdogJob == null
        if (idle) {
            releaseWakeLocks()
            stopSelf()
            return
        }
        AppLogger.i(TAG, "disconnect: $reason")

        val endedGeneration = nextGeneration()
        _state.value = ConnectionState.DISCONNECTED
        _vpnLiveStats.value = VpnLiveStats()
        _connectedAtEpochMs.value = null
        _pingLatencyMs.value = null
        _vpnEdgeIp.value = null
        _tunnelDegraded.value = false
        _connectedEndpoint.value = null
        connectJob?.cancel()
        connectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        exitForeground()

        releaseWakeLocks()

        val doomed = detachResources()

        scope.launch {
            opMutex.withLock {

                val stillOurs = connectionGeneration == endedGeneration
                releaseResources(doomed, stopNativeTunnel = stillOurs)

                if (stillOurs && _state.value == ConnectionState.DISCONNECTED) {
                    stopSelf()
                }
            }
        }
    }

    private fun ensureGenerationCurrent(myGeneration: Int) {
        if (myGeneration != connectionGeneration) {
            throw CancellationException("connect was superseded by a newer request")
        }
    }

    private fun firstUsableNetwork(): Network? {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val networks = runCatching { manager.allNetworks }.getOrDefault(emptyArray())
        var unvalidated: Network? = null
        for (network in networks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network
            if (unvalidated == null) unvalidated = network
        }
        return unvalidated
    }

    private fun hasUsableNetwork(): Boolean {

        getSystemService(ConnectivityManager::class.java) ?: return true
        return firstUsableNetwork() != null
    }

    private fun isFatalUpstreamError(error: Throwable): Boolean =
        error is TokenInvalidError || error is QuotaExceededError

    private fun startProxyPassRenewal(
        myGeneration: Int,
        initialExpiry: Long?,
        mintProxyPass: suspend () -> ProxyPass,
    ) {
        tokenRenewalJob?.cancel()
        tokenRenewalJob = scope.launch {
            var expiry = initialExpiry
            AppLogger.i(
                TAG,
                "proxy pass renewal scheduled in ${proxyPassRenewalDelayMs(expiry) / 1_000}s " +
                    (if (expiry == null) "(pass lifetime unknown; using a fixed interval)" else "(at half of its remaining life)"),
            )
            while (isActive) {
                delay(proxyPassRenewalDelayMs(expiry))
                if (myGeneration != connectionGeneration) return@launch

                val session = upstreamSession
                if (session == null || !session.isConnected) {
                    AppLogger.d(TAG, "skipping proxy pass renewal: no live session (the watchdog's redial mints its own)")
                    continue
                }

                val attempt = runCatching { mintProxyPass() }
                val pass = attempt.getOrNull()
                if (pass == null) {
                    val error = attempt.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error != null && isFatalUpstreamError(error)) {
                        AppLogger.e(
                            TAG,
                            "proxy pass renewal failed for a reason retrying cannot fix; leaving the session to the watchdog",
                            error,
                        )
                        return@launch
                    }
                    AppLogger.w(TAG, "proxy pass renewal failed; retrying shortly: ${error?.message}")

                    expiry = null
                    delay(PROXY_PASS_RENEWAL_RETRY_MS)
                    continue
                }

                if (myGeneration != connectionGeneration) return@launch

                val live = upstreamSession
                if (live == null || !live.isConnected) {

                    AppLogger.d(
                        TAG,
                        "minted a fresh proxy pass but the session went down meanwhile; the watchdog's redial " +
                            "will mint its own",
                    )
                    expiry = pass.expiresAtEpochSeconds
                    continue
                }

                val lifetimeNote = pass.expiresAtEpochSeconds?.let { expiresAt ->
                    val seconds = (expiresAt * 1_000L - System.currentTimeMillis()) / 1_000L
                    " (valid for ${seconds}s)"
                } ?: " (lifetime not stated)"
                live.updateBearerToken(pass.token)
                AppLogger.i(TAG, "proxy pass renewed in place$lifetimeNote; the tunnel was not rebuilt")

                expiry = pass.expiresAtEpochSeconds
            }
        }
    }

    private suspend fun connect() {

        val myGeneration = nextGeneration()

        // A previous instance's teardown (queued from onDestroy) must finish before the native tunnel
        // and local ports are reused.
        runCatching { pendingRelease?.get(PENDING_RELEASE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }

        clearDisconnectedAlert()
        lastUnhealthyRedialAt = 0L

        boundNetwork = null
        boundNetworkLostWhileConnected = false

        EdgeAddressResolver.invalidate()
        runCatching {
            AppLogger.i(TAG, "connect: starting")
            // Reuse the process-wide singletons so UI observers (e.g. selectedProxyFlow) see our updates
            // and we don't rebuild EncryptedSharedPreferences on every connect.
            val app = applicationContext as? FoxyVpnApp
            val tokenStore = app?.tokenStore ?: TokenStore(applicationContext)
            if (tokenStore.loadAuth() == null) error("Not signed in")
            val proxyStateStore = app?.proxyStateStore ?: ProxyStateStore(applicationContext)
            val settingsStore = app?.settingsStore ?: SettingsStore(applicationContext)

            val primaryCandidate = resolveConnectCandidate(proxyStateStore)
            ensureGenerationCurrent(myGeneration)

            var candidates = listOf(primaryCandidate)
            var candidateIndex = 0
            var alternatesDiscovered = false

            fun activeCandidate(): ProxyCandidate = candidates[candidateIndex.coerceIn(0, candidates.lastIndex)]

            suspend fun rotateCandidate(): Boolean {
                if (candidateIndex + 1 >= candidates.size && !alternatesDiscovered) {
                    alternatesDiscovered = true
                    val fresh = discoverAlternateCandidates(primaryCandidate)
                        .filter { discovered -> candidates.none { known -> known.authority == discovered.authority } }
                        .take(MAX_ALTERNATE_EDGES)
                    if (fresh.isNotEmpty()) {
                        candidates = candidates + fresh
                        AppLogger.i(
                            TAG,
                            "found ${fresh.size} alternate edge(s) in ${primaryCandidate.countryCode} to fail over to",
                        )
                    }
                }
                if (candidateIndex + 1 >= candidates.size) return false
                candidateIndex++
                AppLogger.i(
                    TAG,
                    "failing over to edge ${activeCandidate().authority} " +
                        "(${candidateIndex + 1} of ${candidates.size})",
                )
                return true
            }

            val upstreamProxyConfig = if (settingsStore.upstreamProxyEnabled && settingsStore.upstreamProxyHost.isNotBlank()) {
                UpstreamProxyConfig(
                    type = when (settingsStore.upstreamProxyType) {
                        SettingsStore.UpstreamProxyType.SOCKS5 -> UpstreamProxyConfig.Type.SOCKS5
                        SettingsStore.UpstreamProxyType.HTTP -> UpstreamProxyConfig.Type.HTTP
                    },
                    host = settingsStore.upstreamProxyHost,
                    port = settingsStore.upstreamProxyPort,
                    username = settingsStore.upstreamProxyUsername.ifBlank { null },
                    password = settingsStore.upstreamProxyPassword.ifBlank { null },
                )
            } else {
                null
            }
            val chainSuffix = if (upstreamProxyConfig != null) {
                " via ${upstreamProxyConfig.type} proxy ${upstreamProxyConfig.host}:${upstreamProxyConfig.port}"
            } else {
                ""
            }

            val customEdgeAddress = settingsStore.effectiveCustomEdgeAddress
            val edgeSuffix = if (customEdgeAddress != null) {
                " via pinned edge $customEdgeAddress:${primaryCandidate.port}"
            } else {
                ""
            }

            val dohEndpointAddresses = settingsStore.effectiveBootstrapAddresses
            val bootstrapDesc = if (dohEndpointAddresses.isEmpty()) {
                "system DNS"
            } else {
                "${settingsStore.bootstrapDnsProvider.label} (${dohEndpointAddresses.joinToString(", ")})"
            }

            AppLogger.i(
                TAG,
                "connect: target=${primaryCandidate.authority} country=${primaryCandidate.countryCode} bootstrap=$bootstrapDesc$chainSuffix$edgeSuffix",
            )

            val socksPort = settingsStore.socksPort
            val proxyOnlyMode = settingsStore.proxyOnlyMode
            // In TUN mode the only legitimate client is our own hev-socks5-tunnel, which dials
            // 127.0.0.1 -- any other bind address would break the tunnel and expose the proxy to the
            // LAN/hotspot. Honour the user's bind address only in proxy-only mode.
            val socksBindAddress = if (proxyOnlyMode) {
                settingsStore.socksBindAddress
            } else {
                LOOPBACK_BIND_ADDRESS.also {
                    if (settingsStore.socksBindAddress != it) {
                        AppLogger.i(TAG, "TUN mode: local proxies are restricted to loopback (ignoring bind address ${settingsStore.socksBindAddress})")
                    }
                }
            }
            val userProxyCredentials = runCatching {
                ProxyCredentials.fromSettings(settingsStore.localProxyUsername, settingsStore.localProxyPassword)
            }.onFailure { AppLogger.w(TAG, "could not read the local proxy credentials; continuing without", it) }
                .getOrNull()
            // TUN mode: the SOCKS5 server demands a random per-session login that only hev knows, so
            // other apps on the device cannot spend this account's quota through 127.0.0.1.
            // Proxy-only mode: SOCKS5 and HTTP use the optional user-defined login (none by default).
            val socksCredentials = if (proxyOnlyMode) userProxyCredentials else ProxyCredentials.random()
            val httpCredentials = userProxyCredentials
            val httpProxyPort = settingsStore.httpProxyPort
            // Two listeners cannot share a port: if the user picked the same number, keep SOCKS5 only.
            val httpProxyEnabled = settingsStore.httpProxyEnabled && (httpProxyPort != socksPort).also { distinct ->
                if (!distinct && settingsStore.httpProxyEnabled) {
                    AppLogger.w(TAG, "HTTP proxy port equals the SOCKS5 port ($socksPort); HTTP proxy disabled for this session")
                }
            }
            val excludedApps = settingsStore.excludedApps

            val dohUrl = settingsStore.effectiveDohUrl

            fun connectedLabel(): String = if (proxyOnlyMode) {
                if (httpProxyEnabled) {
                    "Proxy active \u2022 SOCKS $socksPort, HTTP $httpProxyPort"
                } else {
                    "Proxy active \u2022 $socksBindAddress:$socksPort"
                }
            } else {
                val target = activeCandidate()
                "Connected \u2022 ${target.countryName.ifBlank { target.countryCode }}"
            }

            if (proxyOnlyMode) {
                ControlPlaneHttp.socketProtector = null
            } else {
                ControlPlaneHttp.socketProtector = { socket -> protect(socket) }
            }

            val mapdnsActive = !proxyOnlyMode && dohUrl == null
            val socks = LocalSocks5Server(
                socksBindAddress,
                socksPort,
                mapdnsActive,
                dohUrl,
                { upstreamSession },
                credentials = socksCredentials,
            ) {
                onUpstreamSessionUnhealthy()
            }
            socks.start()
            socksServer = socks

            if (httpProxyEnabled) {
                val http = LocalHttpProxyServer(
                    socksBindAddress,
                    httpProxyPort,
                    { upstreamSession },
                    credentials = httpCredentials,
                ) {
                    onUpstreamSessionUnhealthy()
                }
                http.start()
                httpProxyServer = http
            }
            ensureGenerationCurrent(myGeneration)

            if (proxyOnlyMode) {

                tunnelInterfaceActive = false
                AppLogger.i(
                    TAG,
                    "connect: proxy-only mode enabled, skipping TUN interface and tun2socks; " +
                        "local proxy at $socksBindAddress:$socksPort",
                )
            } else {
                val fd = establishTun(excludedApps, dohUrl, mtu = settingsStore.mtu) ?: error("Failed to establish TUN interface")
                tunFd = fd
                tunnelInterfaceActive = true

                reportedUnderlying = null
                runCatching {

                    firstUsableNetwork()?.let { underlying -> reportUnderlyingNetwork(underlying) }
                }

                val tunnelConfigPath = HevSocks5TunnelConfig.write(
                    applicationContext,
                    socksPort,
                    useMapDns = mapdnsActive,
                    mtu = settingsStore.mtu,
                    socksUsername = socksCredentials?.username,
                    socksPassword = socksCredentials?.password,
                )
                val tunnelStarted = HevSocks5Tunnel.start(tunnelConfigPath, fd.fd)
                if (!tunnelStarted) error("Failed to start the tun2socks tunnel (hev-socks5-tunnel)")
                AppLogger.i(TAG, "connect: TUN interface up, hev-socks5-tunnel started")
                delay(POST_TUN_SETTLE_MS)
            }
            ensureGenerationCurrent(myGeneration)

            val cachedPass = java.util.concurrent.atomic.AtomicReference<ProxyPass?>(null)

            fun reusableCachedPass(): ProxyPass? {
                val pass = cachedPass.get() ?: return null
                val expiresAt = pass.expiresAtEpochSeconds ?: return null
                val remainingSeconds = expiresAt - System.currentTimeMillis() / 1_000L
                return if (remainingSeconds > PROXY_PASS_REUSE_MARGIN_SECONDS) pass else null
            }

            // forceFresh = true is used by the scheduled renewal; redials may reuse a still-valid pass
            // instead of spending another request against Guardian's rate limit.
            suspend fun mintProxyPass(forceFresh: Boolean = false): ProxyPass {
                if (!forceFresh) {
                    reusableCachedPass()?.let {
                        AppLogger.d(TAG, "reusing the still-valid Guardian proxy pass")
                        return it
                    }
                }
                var auth = tokenStore.loadAuth() ?: throw TokenInvalidError("Not signed in")

                if (!tokenStore.hasValidAccessToken()) {
                    val renewed = renewAccessToken(tokenStore)
                    if (renewed != null) {
                        auth = renewed
                        AppLogger.i(TAG, "the account's access token had expired and was renewed")
                    } else {

                        AppLogger.w(
                            TAG,
                            "the account's access token is past its expiry and could not be renewed automatically; " +
                                "you may need to sign in again",
                        )
                    }
                }
                val guardian = GuardianClient()

                val pass = try {
                    guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                } catch (invalid: TokenInvalidError) {
                    cachedPass.set(null)
                    AppLogger.w(TAG, "proxy pass rejected, activating Guardian entitlement and retrying", invalid)
                    guardian.activateGuardian(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                    guardian.fetchProxyPass(GUARDIAN_ENDPOINT_DEFAULT, auth.accessToken)
                }
                cachedPass.set(pass)
                if (pass.quotaRemaining != null) {
                    settingsStore.cachedQuotaRemaining = pass.quotaRemaining
                }
                if (pass.quotaMax != null) {
                    settingsStore.cachedQuotaMax = pass.quotaMax
                }
                return pass
            }

            var currentPassExpiry: Long? = null

            suspend fun dialUpstream(): Pair<H2UpstreamSession, Long?> {
                val target = activeCandidate()
                val pass = mintProxyPass()
                currentPassExpiry = pass.expiresAtEpochSeconds
                val lifetimeNote = pass.expiresAtEpochSeconds?.let { expiresAt ->
                    val seconds = (expiresAt * 1_000L - System.currentTimeMillis()) / 1_000L
                    " (valid for ${seconds}s)"
                } ?: " (lifetime not stated)"
                AppLogger.i(TAG, "connect: acquired Guardian proxy pass$lifetimeNote")

                val edgeAddress = customEdgeAddress
                    ?: resolveEdgeAddress(target.host, upstreamProxyConfig, dohEndpointAddresses)

                val session = H2UpstreamSession(
                    target.host,
                    target.port,
                    pass.token,
                    upstreamProxyConfig,
                    edgeAddress,
                    tlsSplitMode = settingsStore.tlsSplitMode,
                )
                try {
                    session.connect()
                } catch (failure: Throwable) {

                    runCatching { session.close() }
                    throw failure
                }
                AppLogger.i(TAG, "connect: upstream HTTP/2 tunnel established to ${target.authority}")
                return session to pass.expiresAtEpochSeconds
            }

            var dialAttempt = 0
            var lastDialFailure: Throwable? = null
            while (true) {
                dialAttempt++
                ensureGenerationCurrent(myGeneration)
                val target = activeCandidate()

                val dialResult = try {
                    withTimeout(CONNECT_TIMEOUT_MS) { dialUpstream() }
                } catch (cancellation: CancellationException) {

                    if (cancellation !is TimeoutCancellationException) throw cancellation
                    lastDialFailure = cancellation
                    AppLogger.w(TAG, "dial to ${target.authority} timed out (attempt $dialAttempt)")
                    null
                } catch (error: Throwable) {

                    if (isFatalUpstreamError(error)) throw error
                    lastDialFailure = error
                    AppLogger.w(TAG, "dial to ${target.authority} failed (attempt $dialAttempt): ${error.message}")
                    null
                }

                val session = dialResult?.first

                if (session != null) {
                    if (myGeneration != connectionGeneration) {

                        runCatching { session.close() }
                        ensureGenerationCurrent(myGeneration)
                    }
                    upstreamSession = session
                    delay(INITIAL_DIAL_SETTLE_MS)
                    if (upstreamSession?.isConnected == true) break
                    AppLogger.w(
                        TAG,
                        "upstream tunnel to ${target.authority} died immediately after connecting " +
                            "(attempt $dialAttempt/$INITIAL_DIAL_MAX_ATTEMPTS)",
                    )
                    runCatching { upstreamSession?.close() }
                    upstreamSession = null

                    lastDialFailure = null
                }

                if (dialAttempt >= INITIAL_DIAL_MAX_ATTEMPTS) {

                    // A rate limit says nothing about whether the chosen location is reachable.
                    val (failures, cleared) = if (lastDialFailure is RateLimitedError) {
                        0 to false
                    } else {
                        proxyStateStore.recordFailure()
                    }
                    if (cleared) {
                        AppLogger.w(
                            TAG,
                            "the saved location failed $failures connects in a row; clearing it so the next " +
                                "connect auto-selects a location",
                        )
                    }
                    val failure = lastDialFailure
                    if (failure != null) {
                        error(
                            "Could not reach a VPN server after $dialAttempt attempts across " +
                                "${candidates.size} server(s). Last error: ${friendlyErrorMessage(failure)}",
                        )
                    }
                    error(
                        "The VPN server closed the connection immediately, $dialAttempt times in a row. " +
                            "Try again shortly or pick a different location.",
                    )
                }

                if (lastDialFailure !is RateLimitedError) rotateCandidate()
                delay(
                    maxOf(
                        fullJitterBackoffMs(dialAttempt - 1, INITIAL_DIAL_BACKOFF_BASE_MS, INITIAL_DIAL_BACKOFF_CAP_MS),
                        rateLimitHintMs(lastDialFailure),
                    ),
                )
            }

            ensureGenerationCurrent(myGeneration)

            val establishedCandidate = activeCandidate()

            if (establishedCandidate.authority == primaryCandidate.authority) {
                proxyStateStore.save(primaryCandidate)
            }

            val connectedText = connectedLabel()
            _connectedAtEpochMs.value = System.currentTimeMillis()
            _state.value = ConnectionState.CONNECTED
            statusLabel = connectedText
            updateNotification(connectedText)
            AppLogger.i(TAG, "connect: CONNECTED via ${establishedCandidate.authority}")

            _connectedEndpoint.value = establishedCandidate.host to establishedCandidate.port
            scope.launch {
                val ping = PingUtil.measureTcpLatencyMs(establishedCandidate.host, establishedCandidate.port)
                if (myGeneration == connectionGeneration) {
                    _pingLatencyMs.value = ping
                }
            }

            startProxyPassRenewal(myGeneration, currentPassExpiry) { mintProxyPass(forceFresh = true) }

            if (settingsStore.exitCheckEnabled) {
                scope.launch {
                    val exitCheck = ExitCheck()
                    exitCheck.fetchProxiedIpInfo(socksPort, socksCredentials)
                        .onSuccess { info ->
                            if (myGeneration == connectionGeneration) {
                                _vpnEdgeIp.value = info
                                AppLogger.i(TAG, "exit check: observed ip=${info.ip}, country=${info.countryCode}")
                            }
                        }
                        .onFailure { AppLogger.w(TAG, "exit check failed (non-fatal)", it) }
                }
            }

            if (!proxyOnlyMode) {
                startSpeedUpdates()
            }

            watchdogJob = scope.launch {
                var consecutiveFailures = 0
                var waitingForNetwork = false
                var lastReconnectFailure: Throwable? = null

                suspend fun stopWithFatalError(message: String) {
                    AppLogger.e(TAG, "unrecoverable upstream failure; disconnecting: $message")
                    _lastError.value = message
                    nextGeneration()
                    watchdogJob = null
                    _tunnelDegraded.value = false
                    _state.value = ConnectionState.DISCONNECTED
                    exitForeground()
                    // The ongoing notification disappears and traffic now flows outside the VPN, so make
                    // that impossible to miss.
                    postDisconnectedAlert(message)
                    releaseWakeLocks()
                    val doomed = detachResources()
                    opMutex.withLock { releaseResources(doomed, stopNativeTunnel = true) }
                    stopSelf()
                }

                while (isActive) {
                    delay(UPSTREAM_WATCHDOG_INTERVAL_MS)
                    val current = upstreamSession
                    if (current != null && current.isConnected) {
                        _tunnelDegraded.value = false
                        if (consecutiveFailures > 0 || waitingForNetwork) {
                            AppLogger.i(TAG, "upstream tunnel is healthy again")
                            _lastError.value = null
                            statusLabel = connectedLabel()
                            updateNotification(statusLabel)
                        }
                        consecutiveFailures = 0
                        waitingForNetwork = false
                        continue
                    }

                    _tunnelDegraded.value = true
                    if (!hasUsableNetwork()) {
                        if (!waitingForNetwork) {
                            AppLogger.i(TAG, "no usable network; holding the session and waiting for connectivity")
                            waitingForNetwork = true
                            statusLabel = "Waiting for network\u2026"
                            updateNotification(statusLabel)
                        }
                        consecutiveFailures = 0
                        continue
                    }
                    if (waitingForNetwork) {
                        AppLogger.i(TAG, "network is back; redialing the upstream tunnel")
                        waitingForNetwork = false
                    }

                    AppLogger.w(TAG, "upstream tunnel is down; attempting to reconnect")
                    // Make-before-break: stop routing NEW flows to the old session but let its in-flight
                    // streams finish while the replacement is dialed. It closes itself once idle (or
                    // immediately when it has no streams, e.g. a dead connection).
                    runCatching { current?.disableNewStreamsAndCloseWhenIdle() }
                    statusLabel = "Reconnecting\u2026"
                    updateNotification(statusLabel)

                    val dialResult = try {
                        withTimeout(CONNECT_TIMEOUT_MS) { dialUpstream() }
                    } catch (cancellation: CancellationException) {
                        if (cancellation !is TimeoutCancellationException) throw cancellation
                        consecutiveFailures++
                        lastReconnectFailure = cancellation
                        AppLogger.w(TAG, "upstream reconnect attempt $consecutiveFailures timed out")
                        null
                    } catch (error: Throwable) {
                        if (isFatalUpstreamError(error)) {
                            stopWithFatalError(friendlyErrorMessage(error))
                            return@launch
                        }
                        consecutiveFailures++
                        lastReconnectFailure = error
                        AppLogger.w(TAG, "upstream reconnect attempt $consecutiveFailures failed: ${error.message}")
                        null
                    }
                    val fresh = dialResult?.first

                    if (fresh == null) {

                        if (lastReconnectFailure !is RateLimitedError &&
                            consecutiveFailures % RECONNECT_FAILURES_BEFORE_EDGE_ROTATION == 0
                        ) {
                            rotateCandidate()
                        }

                        if (consecutiveFailures == RECONNECT_FAILURES_BEFORE_WARNING) {
                            _lastError.value =
                                "Still trying to reconnect to the VPN server. Tap Disconnect to stop."
                            AppLogger.e(
                                TAG,
                                "$consecutiveFailures consecutive reconnect failures; continuing to retry with backoff " +
                                    "(capped at ${RECONNECT_BACKOFF_CAP_MS / 1_000}s between attempts)",
                            )
                        }
                        delay(
                            maxOf(
                                fullJitterBackoffMs(consecutiveFailures - 1, RECONNECT_BACKOFF_BASE_MS, RECONNECT_BACKOFF_CAP_MS),
                                rateLimitHintMs(lastReconnectFailure),
                            ),
                        )
                        continue
                    }

                    if (myGeneration != connectionGeneration) {

                        AppLogger.i(TAG, "discarding stale reconnect from a torn-down session generation")
                        runCatching { fresh.close() }
                        return@launch
                    }
                    upstreamSession = fresh
                    consecutiveFailures = 0
                    _lastError.value = null

                    statusLabel = connectedLabel()
                    updateNotification(statusLabel)
                    AppLogger.i(TAG, "upstream tunnel reconnected via ${activeCandidate().authority}")

                    startProxyPassRenewal(myGeneration, currentPassExpiry) { mintProxyPass(forceFresh = true) }
                }
            }
        }.onFailure { failure ->

            if (failure is CancellationException) {
                AppLogger.i(TAG, "connect: aborted because the connection was cancelled")
                releaseResources(detachResources(), stopNativeTunnel = true)
                return@onFailure
            }
            val message = if (failure is TimeoutCancellationException) {
                "Connection timed out. Check your network and try again."
            } else {
                friendlyErrorMessage(failure)
            }
            AppLogger.e(TAG, "connect failed", failure)
            _lastError.value = message
            teardown()
            exitForeground()
            releaseWakeLocks()
            stopSelf()
        }
        if (currentCoroutineContext()[Job] === connectJob) connectJob = null
    }

    private suspend fun resolveConnectCandidate(proxyStateStore: ProxyStateStore): ProxyCandidate {
        proxyStateStore.load()?.let { return it }
        AppLogger.i(TAG, "connect: no server previously selected; auto-selecting recommended location")
        val client = (applicationContext as? FoxyVpnApp)?.serverListClient ?: ServerListClient(applicationContext)
        val countries = runCatching {
            withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) { client.fetchCountries() }
        }.getOrElse {
            client.loadCachedCountries()
        }
        val recommended = ServerListClient.candidatesForCountry(countries, RECOMMENDED_COUNTRY_CODE).randomOrNull()
        val chosen = recommended ?: countries.firstOrNull { it.code.isNotBlank() }?.let { country ->
            ServerListClient.candidatesForCountry(countries, country.code).randomOrNull()
        } ?: error("No server selected and no servers are available. Choose a location first.")
        proxyStateStore.save(chosen)
        AppLogger.i(TAG, "connect: auto-selected ${chosen.authority} country=${chosen.countryCode}")
        return chosen
    }

    private suspend fun discoverAlternateCandidates(primary: ProxyCandidate): List<ProxyCandidate> {
        val attempt = runCatching {
            val client = (applicationContext as? FoxyVpnApp)?.serverListClient ?: ServerListClient(applicationContext)
            val countries = runCatching {
                withTimeout(SERVER_LIST_FETCH_TIMEOUT_MS) { client.fetchCountries() }
            }.getOrElse {
                client.loadCachedCountries()
            }
            val sameCity = ServerListClient.candidatesForCity(countries, primary.countryCode, primary.cityCode)
            val sameCountry = ServerListClient.candidatesForCountry(countries, primary.countryCode)
            (sameCity + sameCountry)
                .distinctBy { it.authority }
                .filter { it.authority != primary.authority }
        }
        val failure = attempt.exceptionOrNull()
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        if (failure != null) {
            AppLogger.w(TAG, "could not fetch alternate edges; staying with ${primary.authority}", failure)
            return emptyList()
        }
        return attempt.getOrDefault(emptyList())
    }

    private suspend fun resolveEdgeAddress(
        host: String,
        upstreamProxy: UpstreamProxyConfig?,
        dohEndpointAddresses: List<String>,
    ): String? {
        if (upstreamProxy != null) return null
        if (dohEndpointAddresses.isEmpty()) {
            AppLogger.d(TAG, "resolving $host via system DNS resolver (direct network)")
            return null
        }
        return EdgeAddressResolver.resolve(host, dohEndpointAddresses)
    }

    private suspend fun renewAccessToken(tokenStore: TokenStore): RuntimeAuth? {
        val attempt = runCatching { FxaAuthRepository(tokenStore).refreshAccessToken() }
        val failure = attempt.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) {
            AppLogger.w(TAG, "renewing the account's access token failed", failure)
            return null
        }
        return attempt.getOrNull()
    }

    private fun friendlyErrorMessage(error: Throwable): String = when (error) {
        is QuotaExceededError -> "Your VPN quota is exhausted. Try again later."
        is TokenInvalidError -> "Your session was rejected. Please sign in again."
        is RateLimitedError -> "The VPN service is rate limiting requests. Try again shortly."
        is TimeoutCancellationException -> "the connection timed out"
        else -> {
            val untrusted = generateSequence(error) { it.cause }.take(8).any {
                it is java.security.cert.CertPathValidatorException || it is java.security.cert.CertificateException
            }
            val base = error.message ?: (error::class.simpleName ?: "Connection failed")
            if (untrusted) {
                "$base (this device does not trust the VPN server's certificate; updating Android or the device's date/time may help)"
            } else {
                base
            }
        }
    }

    private fun establishTun(
        excludedApps: Set<String>,
        dohUrl: String?,
        mtu: Int = HevSocks5TunnelConfig.TUN_MTU,
    ): ParcelFileDescriptor? {
        val dnsServer = HevSocks5TunnelConfig.MAPDNS_ADDRESS
        if (dohUrl != null) {
            AppLogger.i(TAG, "establishTun: dns=DoH ($dohUrl)")
        } else {
            AppLogger.i(TAG, "establishTun: dns=mapdns ($dnsServer)")
        }
        return Builder()
            .setSession("Moz VPN")
            .addAddress(HevSocks5TunnelConfig.TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .apply {
                // Android 13+: keep private/link-local ranges out of the tunnel so printers, NAS, casting
                // and the router stay reachable. They could never be carried by the tunnel anyway (the
                // proxy refuses private destinations), so this only turns a dead end into a working LAN.
                // 100.64.0.0/10 (used for the synthetic DNS addresses) is deliberately NOT excluded.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val lanRanges = listOf(
                        "10.0.0.0" to 8,
                        "172.16.0.0" to 12,
                        "192.168.0.0" to 16,
                        "169.254.0.0" to 16,
                        "fe80::" to 10,
                    )
                    for ((address, prefix) in lanRanges) {
                        runCatching { excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(address), prefix)) }
                            .onFailure { AppLogger.w(TAG, "could not exclude $address/$prefix from the tunnel", it) }
                    }
                }
            }
            .apply {

                val ipv6Address = runCatching {
                    addAddress(TUN_ADDRESS_IPV6, TUN_PREFIX_LENGTH_IPV6)
                }.onFailure {
                    AppLogger.w(
                        TAG,
                        "could not add an IPv6 address to the TUN interface; IPv6 will be captured by the " +
                            "tunnel's default route and dropped rather than sent in the clear",
                        it,
                    )
                }

                runCatching { addRoute("::", 0) }.onFailure {
                    AppLogger.e(
                        TAG,
                        "could not claim the IPv6 default route; IPv6 traffic may bypass the tunnel and leak " +
                            "the device's real address",
                        it,
                    )
                }
                if (ipv6Address.isFailure) {
                    AppLogger.i(TAG, "establishTun: IPv6 is routed into the tunnel and blackholed (no IPv6 address)")
                }
            }
            .addDnsServer(dnsServer)

            .setMtu(mtu)
            .setBlocking(true)
            .apply {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Intentional: false means "inherit meteredness from the underlying network"
                    // (reported via setUnderlyingNetworks). The Q+ default of true would make the VPN
                    // look metered even on Wi-Fi and make apps defer syncs/downloads.
                    runCatching { setMetered(false) }
                        .onFailure { AppLogger.d(TAG, "could not mark the tunnel as unmetered: ${it.message}") }
                }
            }
            .apply {
                runCatching { addDisallowedApplication(packageName) }
                    .onFailure { AppLogger.w(TAG, "failed to exclude own app from the VPN tunnel", it) }
                for (excludedPackage in excludedApps) {
                    runCatching { addDisallowedApplication(excludedPackage) }
                        .onFailure { AppLogger.w(TAG, "failed to exclude $excludedPackage from the VPN tunnel (app may be uninstalled)", it) }
                }
            }
            .establish()
    }

    private fun teardown() {
        nextGeneration()
        watchdogJob?.cancel()
        watchdogJob = null
        releaseResources(detachResources(), stopNativeTunnel = true)
        _state.value = ConnectionState.DISCONNECTED
        _vpnLiveStats.value = VpnLiveStats()
        _connectedAtEpochMs.value = null
        _pingLatencyMs.value = null
        _vpnEdgeIp.value = null
        _tunnelDegraded.value = false
        _connectedEndpoint.value = null
    }

    private fun detachResources(): SessionResources {
        speedJob?.cancel()
        speedJob = null

        tokenRenewalJob?.cancel()
        tokenRenewalJob = null
        val detached = SessionResources(tunFd, socksServer, httpProxyServer, upstreamSession)
        tunFd = null
        tunnelInterfaceActive = false
        socksServer = null
        httpProxyServer = null
        upstreamSession = null
        return detached
    }

    private fun releaseResources(resources: SessionResources, stopNativeTunnel: Boolean) {
        if (resources.isEmpty && !stopNativeTunnel) return
        if (stopNativeTunnel) {
            runCatching { HevSocks5Tunnel.stop() }.onFailure { AppLogger.w(TAG, "error stopping tun2socks tunnel", it) }
        }
        runCatching { resources.socksServer?.stop() }.onFailure { AppLogger.w(TAG, "error stopping local SOCKS5 server", it) }
        runCatching { resources.httpProxyServer?.stop() }.onFailure { AppLogger.w(TAG, "error stopping local HTTP proxy server", it) }
        runCatching { resources.upstreamSession?.close() }.onFailure { AppLogger.w(TAG, "error closing upstream session", it) }
        runCatching { resources.tunFd?.close() }.onFailure { AppLogger.w(TAG, "error closing TUN interface", it) }
    }

    override fun onRevoke() {
        requestDisconnect("VPN permission was revoked by the system")
        super.onRevoke()
    }

    private fun startSpeedUpdates() {
        speedJob?.cancel()
        speedJob = scope.launch {
            var lastStats = HevSocks5Tunnel.stats()
            var lastSampleAt = System.currentTimeMillis()
            val powerManager = getSystemService(android.os.PowerManager::class.java)
            while (isActive) {
                val screenOn = powerManager?.isInteractive != false
                delay(if (screenOn) SPEED_UPDATE_INTERVAL_MS else SPEED_UPDATE_INTERVAL_SCREEN_OFF_MS)
                val stats = HevSocks5Tunnel.stats()
                val now = System.currentTimeMillis()
                val elapsedSeconds = ((now - lastSampleAt).coerceAtLeast(1)).toDouble() / 1_000.0

                val txRate = ((stats[1] - lastStats[1]).coerceAtLeast(0) / elapsedSeconds).toLong()
                val rxRate = ((stats[3] - lastStats[3]).coerceAtLeast(0) / elapsedSeconds).toLong()
                val totalSessionBytes = (stats[1] + stats[3]).coerceAtLeast(0L)
                lastStats = stats
                lastSampleAt = now
                if (_state.value != ConnectionState.CONNECTED) continue
                _vpnLiveStats.value = VpnLiveStats(
                    txRateBytesPerSec = txRate,
                    rxRateBytesPerSec = rxRate,
                    sessionBytes = totalSessionBytes,
                )
                updateNotification(
                    statusLabel,
                    "\u2193 ${formatBytesPerSecond(rxRate)}  \u2191 ${formatBytesPerSecond(txRate)}",
                )
            }
        }
    }

    private fun buildNotification(text: String, subText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this, REQUEST_CODE_OPEN, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val disconnectIntent = PendingIntent.getService(
            this, REQUEST_CODE_DISCONNECT, Intent(this, FoxyVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, FoxyVpnApp.VPN_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Moz VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)

            .setOnlyAlertOnce(true)

            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            .setLocalOnly(true)

            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
        if (subText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$subText"))
        }
        return builder.build()
    }

    private fun enterForeground(text: String) {
        foregroundActive = true
        lastNotificationText = text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun postDisconnectedAlert(reason: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val openIntent = PendingIntent.getActivity(
                this, REQUEST_CODE_OPEN, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val vpnSettingsIntent = PendingIntent.getActivity(
                this, REQUEST_CODE_VPN_SETTINGS, Intent(android.provider.Settings.ACTION_VPN_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = "The VPN disconnected and your traffic is no longer protected."
            val notification = NotificationCompat.Builder(this, FoxyVpnApp.VPN_ALERT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Moz VPN disconnected")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$reason\nTip: turn on \"Block connections without VPN\" in Android's VPN settings to prevent this."))
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .addAction(android.R.drawable.ic_menu_manage, "VPN settings", vpnSettingsIntent)
                .build()
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        }
    }

    private fun clearDisconnectedAlert() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID) }
    }

    private fun exitForeground() {
        foregroundActive = false
        lastNotificationText = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun updateNotification(text: String, subText: String? = null) {
        if (!foregroundActive) return
        val key = if (subText == null) text else "$text\n$subText"
        if (key == lastNotificationText) return
        lastNotificationText = key
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text, subText)) }
    }

    companion object {
        const val ACTION_CONNECT = "com.vauth.foxyvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "com.vauth.foxyvpn.action.DISCONNECT"
        const val ACTION_RECONNECT = "com.vauth.foxyvpn.action.RECONNECT"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val REQUEST_CODE_VPN_SETTINGS = 3
        private const val REQUEST_CODE_OPEN = 0
        private const val REQUEST_CODE_DISCONNECT = 1

        private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val state: StateFlow<ConnectionState> = _state

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError

        private val _tunnelDegraded = MutableStateFlow(false)

        /** True while the VPN is "connected" but its upstream tunnel is down and being re-established. */
        val tunnelDegraded: StateFlow<Boolean> = _tunnelDegraded

        private val _vpnLiveStats = MutableStateFlow(VpnLiveStats())
        val vpnLiveStats: StateFlow<VpnLiveStats> = _vpnLiveStats

        private val _connectedAtEpochMs = MutableStateFlow<Long?>(null)
        val connectedAtEpochMs: StateFlow<Long?> = _connectedAtEpochMs

        private val _pingLatencyMs = MutableStateFlow<Int?>(null)
        val pingLatencyMs: StateFlow<Int?> = _pingLatencyMs

        private val _vpnEdgeIp = MutableStateFlow<IpLocationInfo?>(null)
        val vpnEdgeIp: StateFlow<IpLocationInfo?> = _vpnEdgeIp

        private val _connectedEndpoint = MutableStateFlow<Pair<String, Int>?>(null)
        val connectedEndpoint: StateFlow<Pair<String, Int>?> = _connectedEndpoint

        suspend fun pingNow(): Int? {
            val endpoint = _connectedEndpoint.value ?: return null
            val latency = PingUtil.measureTcpLatencyMs(endpoint.first, endpoint.second)
            if (_state.value == ConnectionState.CONNECTED) {
                _pingLatencyMs.value = latency
            }
            return latency
        }

        /**
         * Starts the VPN service. On Android 12+ the system may refuse a foreground-service start
         * (ForegroundServiceStartNotAllowedException) when the app is not allowed to; that must not
         * crash the caller. Returns whether the start request was accepted.
         */
        fun start(context: Context): Boolean {
            return try {
                context.startForegroundService(Intent(context, FoxyVpnService::class.java).setAction(ACTION_CONNECT))
                true
            } catch (failure: Exception) {
                AppLogger.e(TAG, "could not start the VPN service", failure)
                _lastError.value = "Android did not allow FoxyVPN to start in the background. Open the app and try again."
                false
            }
        }

        private val releaseExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "foxyvpn-release").apply { isDaemon = true }
        }

        @Volatile
        private var pendingRelease: java.util.concurrent.Future<*>? = null

        /** Re-dial using the currently saved location. No-op if the VPN is not connected/connecting. */
        fun reconnect(context: Context) {
            if (_state.value == ConnectionState.DISCONNECTED) return
            val intent = Intent(context, FoxyVpnService::class.java).setAction(ACTION_RECONNECT)
            runCatching { context.startService(intent) }
                .onFailure {
                    AppLogger.w(TAG, "could not deliver the reconnect intent; the service is not running", it)
                }
        }

        fun stop(context: Context) {

            val intent = Intent(context, FoxyVpnService::class.java).setAction(ACTION_DISCONNECT)
            runCatching { context.startService(intent) }
                .onFailure {
                    AppLogger.w(TAG, "could not deliver the disconnect intent; the service is not running", it)
                }
        }
    }
}
