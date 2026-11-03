package dev.shield

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.data.Settings
import dev.shield.data.repo.SettingsRepository
import dev.shield.ui.nav.ShieldNavHost
import dev.shield.ui.theme.ShieldTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val s by settings.settings.collectAsState(initial = Settings())
            ShieldTheme(dynamicColor = s.dynamicColor) {
                ShieldNavHost()
            }
        }
    }
}
