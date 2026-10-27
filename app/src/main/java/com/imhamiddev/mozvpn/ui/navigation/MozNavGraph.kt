package com.imhamiddev.mozvpn.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.imhamiddev.mozvpn.MozVpnApp
import com.imhamiddev.mozvpn.data.ControlPlaneHttp
import com.imhamiddev.mozvpn.ui.components.MozBottomBar
import com.imhamiddev.mozvpn.ui.components.MozTab
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.imhamiddev.mozvpn.ui.screens.HomeScreen
import com.imhamiddev.mozvpn.ui.screens.LoginScreen
import com.imhamiddev.mozvpn.ui.screens.LogsScreen
import com.imhamiddev.mozvpn.ui.screens.ServerListScreen
import com.imhamiddev.mozvpn.ui.screens.SettingsScreen
import com.imhamiddev.mozvpn.ui.screens.SplitTunnelScreen
import com.imhamiddev.mozvpn.ui.screens.SplashScreen
import com.imhamiddev.mozvpn.ui.theme.ThemeController
import com.imhamiddev.mozvpn.vpn.MozVpnService

object MozRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val APPS = "apps"
    const val SETTINGS = "settings"
    const val LOGS = "logs"

    /** Routes that are shown with the bottom navigation bar. */
    val TABS = setOf(HOME, SERVERS, APPS, SETTINGS)
}

private val MozTabs = listOf(
    MozTab(MozRoutes.HOME, "Home", Icons.Rounded.Shield),
    MozTab(MozRoutes.SERVERS, "Servers", Icons.Rounded.Public),
    MozTab(MozRoutes.APPS, "Apps", Icons.Rounded.Apps),
    MozTab(MozRoutes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

/** Switches between top-level tabs while keeping a single Home entry at the bottom of the stack. */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(MozRoutes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MozNavGraph(
    navController: NavHostController = rememberNavController(),
    app: MozVpnApp,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in MozRoutes.TABS

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MozBottomBar(
                    tabs = MozTabs,
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        if (route != currentRoute) navController.navigateToTab(route)
                    },
                )
            }
        },
    ) { innerPadding ->
    NavHost(
        navController = navController,
        startDestination = MozRoutes.SPLASH,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
    ) {
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
                onOpenServers = { navController.navigateToTab(MozRoutes.SERVERS) },
                onOpenSettings = { navController.navigateToTab(MozRoutes.SETTINGS) },
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
        composable(MozRoutes.APPS) {
            SplitTunnelScreen(settingsStore = app.settingsStore)
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
}
