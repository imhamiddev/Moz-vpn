package com.vauth.foxyvpn.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles taps on the home-screen widget. Declared with exported="false" so other apps cannot
 * broadcast ACTION_TOGGLE_VPN to connect/disconnect the VPN; the widget's own PendingIntents still work.
 */
class WidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == QuickConnectWidgetProvider.ACTION_TOGGLE_VPN) {
            QuickConnectWidgetProvider.toggleVpn(context)
        }
    }
}
