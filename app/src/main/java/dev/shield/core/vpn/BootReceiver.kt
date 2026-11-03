package dev.shield.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restarts the protection after boot / app update if it was on. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var controller: VpnController

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (settings.current().protectionEnabled && VpnService.prepare(context) == null) controller.start()
            } finally {
                pending.finish()
            }
        }
    }
}
