package com.vauth.foxyvpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.widget.RemoteViews
import com.vauth.foxyvpn.MozVpnApp
import com.vauth.foxyvpn.MainActivity
import com.vauth.foxyvpn.R
import com.vauth.foxyvpn.data.SettingsStore
import com.vauth.foxyvpn.data.TokenStore
import com.vauth.foxyvpn.data.model.ConnectionState
import com.vauth.foxyvpn.ui.theme.ThemeMode
import com.vauth.foxyvpn.vpn.FoxyVpnService

class QuickConnectWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, FoxyVpnService.state.value)
        }
    }

    // This receiver must stay exported for the launcher's APPWIDGET_UPDATE broadcasts, so it must NOT
    // act on ACTION_TOGGLE_VPN; taps are delivered to the non-exported WidgetToggleReceiver instead.

    companion object {
        const val ACTION_TOGGLE_VPN = "com.vauth.foxyvpn.action.TOGGLE_VPN"

        fun toggleVpn(context: Context) {
            when (FoxyVpnService.state.value) {
                ConnectionState.CONNECTED, ConnectionState.CONNECTING -> {
                    FoxyVpnService.stop(context)
                }
                ConnectionState.DISCONNECTED -> {
                    val app = context.applicationContext as? MozVpnApp
                    val tokenStore = app?.tokenStore ?: TokenStore(context)
                    if (!tokenStore.hasStoredSession()) {
                        launchMainActivity(context)
                        return
                    }

                    val settingsStore = app?.settingsStore ?: SettingsStore(context)
                    if (settingsStore.proxyOnlyMode) {
                        FoxyVpnService.start(context)
                        return
                    }

                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        launchMainActivity(context)
                    } else {
                        FoxyVpnService.start(context)
                    }
                }
            }
        }

        private fun launchMainActivity(context: Context) {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }

        fun updateAll(context: Context, state: ConnectionState) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, QuickConnectWidgetProvider::class.java)
            val appWidgetIds = runCatching { appWidgetManager.getAppWidgetIds(componentName) }.getOrNull() ?: return
            if (appWidgetIds.isEmpty()) return
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId, state)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: ConnectionState,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_connect)

            val toggleIntent = Intent(context, WidgetToggleReceiver::class.java).apply {
                action = ACTION_TOGGLE_VPN
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

            val settingsStore = (context.applicationContext as? MozVpnApp)?.settingsStore ?: SettingsStore(context)
            val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val isDark = when (settingsStore.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val bgRes = when (state) {
                ConnectionState.CONNECTED, ConnectionState.DISCONNECTED ->
                    if (isDark) R.drawable.widget_bg_disconnected_dark else R.drawable.widget_bg_disconnected_light
                ConnectionState.CONNECTING ->
                    if (isDark) R.drawable.widget_bg_connecting_dark else R.drawable.widget_bg_connecting_light
            }

            val iconColor = when (state) {
                ConnectionState.CONNECTED -> if (isDark) 0xFF7BDF92.toInt() else 0xFF1B6E2F.toInt()
                ConnectionState.CONNECTING -> if (isDark) 0xFFFFC46B.toInt() else 0xFF8A5A00.toInt()
                ConnectionState.DISCONNECTED -> if (isDark) 0xFFE9E0E4.toInt() else 0xFF1E1A1D.toInt()
            }

            views.setImageViewResource(R.id.widget_background, bgRes)
            views.setImageViewResource(R.id.widget_button, android.R.drawable.ic_lock_lock)
            views.setInt(R.id.widget_button, "setColorFilter", iconColor)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
