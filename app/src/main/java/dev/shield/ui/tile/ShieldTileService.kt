package dev.shield.ui.tile

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.MainActivity
import dev.shield.R
import dev.shield.core.vpn.TunnelPhase
import dev.shield.core.vpn.VpnController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick settings tile: protection on/off from the notification shade. */
@AndroidEntryPoint
class ShieldTileService : TileService() {
    @Inject lateinit var controller: VpnController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { controller.state.collect { update() } }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val running = controller.state.value.phase != TunnelPhase.STOPPED
        if (!running && VpnService.prepare(this) != null) {
            // consent dialog needed: open the app
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        scope.launch { controller.setProtection(!running) }
    }

    private fun update() {
        val tile = qsTile ?: return
        val st = controller.state.value
        tile.state = when (st.phase) {
            TunnelPhase.RUNNING, TunnelPhase.STARTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            when {
                st.panic -> R.string.status_panic
                st.phase == TunnelPhase.RUNNING -> R.string.status_protected
                st.phase == TunnelPhase.ERROR -> R.string.status_unprotected
                st.phase == TunnelPhase.STARTING -> R.string.status_starting
                else -> R.string.off
            },
        )
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
