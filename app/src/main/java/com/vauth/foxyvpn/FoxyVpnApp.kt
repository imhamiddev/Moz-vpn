package com.vauth.foxyvpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.vauth.foxyvpn.data.ControlPlaneHttp
import com.vauth.foxyvpn.data.CrashReporter
import com.vauth.foxyvpn.data.FxaAuthRepository
import com.vauth.foxyvpn.data.GuardianClient
import com.vauth.foxyvpn.data.ProxyStateStore
import com.vauth.foxyvpn.data.ServerListClient
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TokenStore
import com.vauth.foxyvpn.vpn.FoxyVpnService
import com.vauth.foxyvpn.vpn.upstream.NettyLoggingBridge
import com.vauth.foxyvpn.vpn.upstream.UpstreamTrust
import com.vauth.foxyvpn.widget.QuickConnectWidgetProvider
import org.conscrypt.Conscrypt
import java.security.Security

class FoxyVpnApp : Application() {

    /** Process-lifetime scope for fire-and-forget work that must outlive a screen (e.g. sign-out revocation). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var tokenStore: TokenStore
    lateinit var proxyStateStore: ProxyStateStore
    lateinit var settingsStore: SettingsStore
    lateinit var guardianClient: GuardianClient
    lateinit var serverListClient: ServerListClient
    lateinit var authRepository: FxaAuthRepository

    override fun onCreate() {
        super.onCreate()

        CrashReporter.install(this)
        CrashReporter.replayLastCrashIfAny(this)

        NettyLoggingBridge.install()
        installConscrypt()
        UpstreamTrust.init(this)
        tokenStore = TokenStore(this)
        proxyStateStore = ProxyStateStore(this)
        settingsStore = SettingsStore(this)
        ControlPlaneHttp.tlsSplitModeProvider = { settingsStore.tlsSplitMode }
        guardianClient = GuardianClient()
        serverListClient = ServerListClient(this)
        authRepository = FxaAuthRepository(tokenStore)
        createNotificationChannel()
    }

    private fun installConscrypt() {
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }.onFailure {
            Log.w("FoxyVpnApp", "failed to install Conscrypt security provider", it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            VPN_NOTIFICATION_CHANNEL_ID,
            "VPN status",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Separate, louder channel for "the VPN dropped" alerts (the status channel is deliberately quiet).
        val alertChannel = NotificationChannel(
            VPN_ALERT_NOTIFICATION_CHANNEL_ID,
            "VPN alerts",
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(alertChannel)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        QuickConnectWidgetProvider.updateAll(this, FoxyVpnService.state.value)
    }

    companion object {
        const val VPN_NOTIFICATION_CHANNEL_ID = "mozvpn_status"
        const val VPN_ALERT_NOTIFICATION_CHANNEL_ID = "mozvpn_alerts"
    }
}
