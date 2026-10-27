package com.imhamiddev.mozvpn.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.imhamiddev.mozvpn.MozVpnApp
import com.imhamiddev.mozvpn.data.ControlPlaneHttp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.imhamiddev.mozvpn.ui.screens.HomeScreen
import com.imhamiddev.mozvpn.ui.screens.LoginScreen
import com.imhamiddev.mozvpn.ui.screens.LogsScreen
import com.imhamiddev.mozvpn.ui.screens.ServerListScreen
import com.imhamiddev.mozvpn.ui.screens.SettingsScreen
import com.imhamiddev.mozvpn.ui.screens.SplashScreen
import com.imhamiddev.mozvpn.ui.theme.ThemeController
import com.imhamiddev.mozvpn.vpn.MozVpnService

object MozRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
}

@Composable
fun MozNavGraph(
    navController: NavHostController = rememberNavController(),
    app: MozVpnApp,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    NavHost(navController = navController, startDestination = MozRoutes.SPLASH) {
        composable(MozRoutes.SPLASH) {
            SplashScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(MozRoutes.HOME) { popUpTo(MozRoutes.SPLASH) { inclusive = true } }
                },
                onNeedsLogin = {
                    navController.navigate(MozRoutes.LOGIN) { popUpTo(MozRoutes.SPLASH) { inclusive = true } }
                },
            )
        }
        composable(MozRoutes.LOGIN) {
            LoginScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(MozRoutes.HOME) { popUpTo(MozRoutes.LOGIN) { inclusive = true } }
                },
            )
        }
        composable(MozRoutes.HOME) {
            HomeScreen(
                app = app,
                themeController = themeController,
                onRequestConnect = onRequestConnect,
                onDisconnect = onDisconnect,
                onOpenServers = { navController.navigate(MozRoutes.SERVERS) },
                onOpenSettings = { navController.navigate(MozRoutes.SETTINGS) },
            )
        }
        composable(MozRoutes.SERVERS) {
            val context = LocalContext.current
            ServerListScreen(
                serverListClient = app.serverListClient,
                proxyStateStore = app.proxyStateStore,
                onServerSelected = { locationChanged ->
                    if (locationChanged) MozVpnService.reconnect(context)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(MozRoutes.SETTINGS) {
            SettingsScreen(
                settingsStore = app.settingsStore,
                onOpenLogs = { navController.navigate(MozRoutes.LOGS) },
                onSignOut = {
                    // Snapshot the tokens first so they can be revoked on the server after the local wipe.
                    val signedOutAuth = runCatching { app.tokenStore.loadAuth() }.getOrNull()
                    onDisconnect()
                    app.tokenStore.clear()
                    app.authRepository.clearPendingSession()
                    ControlPlaneHttp.cookieJar.clear()
                    app.settingsStore.clearAccountSession()
                    app.proxyStateStore.clear()
                    if (signedOutAuth != null) {
                        app.appScope.launch {
                            runCatching { withTimeout(10_000L) { app.authRepository.revokeRemote(signedOutAuth) } }
                        }
                    }
                    navController.navigate(MozRoutes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(MozRoutes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
