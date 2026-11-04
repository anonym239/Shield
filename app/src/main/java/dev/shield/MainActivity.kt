package dev.shield

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.data.Settings
import dev.shield.data.repo.SettingsRepository
import dev.shield.ui.nav.Routes
import dev.shield.ui.nav.ShieldNavHost
import dev.shield.ui.theme.ShieldTheme
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    /** A screen another component asked to open (e.g. the VPN screen after a one-tap import). */
    private val openRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRoute.value = routeOf(intent)
        setContent {
            val s by settings.settings.collectAsState(initial = Settings())
            val route by openRoute.collectAsState()
            ShieldTheme(dynamicColor = s.dynamicColor) {
                ShieldNavHost(openRoute = route, onRouteOpened = { openRoute.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { openRoute.value = it }
    }

    private fun routeOf(i: Intent?): String? = i?.getStringExtra(EXTRA_ROUTE)?.takeIf { it in ALLOWED_ROUTES }

    companion object {
        const val EXTRA_ROUTE = "dev.shield.extra.ROUTE"
        /** Only these screens may be opened from outside. */
        private val ALLOWED_ROUTES = setOf(Routes.VPN, Routes.DASHBOARD)
    }
}
