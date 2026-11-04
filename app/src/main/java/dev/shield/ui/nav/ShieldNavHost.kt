@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.shield.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.shield.R
import dev.shield.ui.apps.AppDetailScreen
import dev.shield.ui.apps.AppsScreen
import dev.shield.ui.dashboard.DashboardScreen
import dev.shield.ui.debug.DebugScreen
import dev.shield.ui.log.LogScreen
import dev.shield.ui.settings.BackupScreen
import dev.shield.ui.settings.ChecksScreen
import dev.shield.ui.settings.ExtrasScreen
import dev.shield.ui.settings.PrivacyScreen
import dev.shield.ui.settings.ProtectionScreen
import dev.shield.ui.settings.RulesScreen
import dev.shield.ui.settings.SettingsScreen
import dev.shield.ui.settings.VpnScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val APPS = "apps"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val APP_DETAIL = "app/{pkg}"
    const val PROTECTION = "settings/protection"
    const val RULES = "settings/rules"
    const val VPN = "settings/vpn"
    const val CHECKS = "settings/checks"
    const val PRIVACY = "settings/privacy"
    const val BACKUP = "settings/backup"
    const val EXTRAS = "settings/extras"
    const val DEBUG = "settings/debug"
    const val AI = "settings/ai"
    const val ADFREE = "settings/adfree"
    fun appDetail(pkg: String) = "app/$pkg"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, R.string.tab_overview, Icons.Filled.Shield),
    Tab(Routes.APPS, R.string.tab_apps, Icons.Filled.Apps),
    Tab(Routes.LOG, R.string.tab_log, Icons.Filled.ListAlt),
    Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings),
)

@Composable
fun ShieldNavHost() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    val showBar = tabs.any { t -> dest?.hierarchy?.any { it.route == t.route } == true }
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { t ->
                        val selected = dest?.hierarchy?.any { it.route == t.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(pad)) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenApp = { nav.navigate(Routes.appDetail(it)) },
                    onOpenLog = { nav.navigate(Routes.LOG) },
                    onOpenSettings = { nav.navigate(it) },
                )
            }
            composable(Routes.APPS) { AppsScreen(onOpen = { nav.navigate(Routes.appDetail(it)) }) }
            composable(
                Routes.APP_DETAIL,
                arguments = listOf(navArgument("pkg") { type = NavType.StringType }),
            ) { e ->
                AppDetailScreen(pkg = e.arguments?.getString("pkg").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Routes.LOG) { LogScreen() }
            composable(Routes.SETTINGS) { SettingsScreen(onOpen = { nav.navigate(it) }) }
            composable(Routes.PROTECTION) { ProtectionScreen(onBack = { nav.popBackStack() }, onOpenRules = { nav.navigate(Routes.RULES) }) }
            composable(Routes.RULES) { RulesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.VPN) { VpnScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CHECKS) { ChecksScreen(onBack = { nav.popBackStack() }, onOpenApp = { nav.navigate(Routes.appDetail(it)) }) }
            composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.BACKUP) { BackupScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.EXTRAS) { ExtrasScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DEBUG) { DebugScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ADFREE) { dev.shield.ui.settings.AdFreeScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.AI) { dev.shield.ui.settings.AiScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
