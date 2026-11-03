package dev.shield.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Entry point for UI, widget and tile to control the VPN service. */
@Singleton
class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val events: EventLogger,
) {
    internal val mutableState = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    @Volatile
    internal var service: ShieldVpnService? = null

    /** Intent for the system VPN consent dialog, or null if already granted. */
    fun permissionIntent(): Intent? = VpnService.prepare(context)

    suspend fun setProtection(on: Boolean) {
        settings.update { it.copy(protectionEnabled = on) }
        if (on) {
            if (permissionIntent() != null) {
                ShieldLog.w("Vpn", "VPN permission missing")
                return
            }
            events.info(EventType.PROTECTION_ON, "Schutz eingeschaltet")
            start()
        } else {
            events.warn(EventType.PROTECTION_OFF, "Schutz ausgeschaltet")
            stop()
        }
    }

    fun start() {
        try {
            context.startForegroundService(Intent(context, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START))
        } catch (e: Exception) {
            ShieldLog.e("Vpn", "cannot start service", e)
            events.critical(EventType.TUNNEL_ERROR, "Dienst konnte nicht starten: ${e.message}")
        }
    }

    fun stop() {
        val s = service
        if (s != null) {
            s.stopByUser()
        } else {
            mutableState.value = TunnelState()
        }
    }

    fun restart() {
        val s = service
        if (s != null) s.requestReconfigure(force = true) else start()
    }

    suspend fun setPanic(on: Boolean) {
        settings.update { it.copy(panic = on) }
        if (on) {
            events.critical(EventType.PANIC_ON, "Panik-Knopf: alle Verbindungen getrennt")
        } else {
            events.info(EventType.PANIC_OFF, "Panik-Modus beendet")
        }
        service?.requestReconfigure(force = false)
    }

    /** Self-test 4: query the encrypted upstream through the running tunnel; returns latency in ms. */
    suspend fun probeUpstream(): Result<Long> =
        service?.probeUpstream() ?: Result.failure(IllegalStateException("Tunnel läuft nicht"))

    fun tunnelInterfaceUp(): Boolean = service?.tunnelUp() == true
}
