package dev.shield.core.wireguard

import dev.shield.core.vpn.VpnController
import dev.shield.data.db.WgProfileEntity
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import javax.inject.Inject
import javax.inject.Singleton

/** Result of the one-tap setup. */
data class QuickSetupResult(val profile: WgProfileEntity, val protectionStarted: Boolean, val needsVpnPermission: Boolean)

/**
 * One-tap VPN setup: import a WireGuard config (file, share, clipboard), make it the active profile and turn
 * protection on. The VPN service reconfigures itself on the settings change, and the automatic self-test
 * runs after the tunnel is up.
 */
@Singleton
class VpnQuickSetup @Inject constructor(
    private val profiles: WgProfileRepository,
    private val settings: SettingsRepository,
    private val controller: VpnController,
    private val events: EventLogger,
) {
    /** Throws [WgConfigException] with a readable message if the text is no valid config. */
    suspend fun importAndActivate(name: String, text: String): QuickSetupResult {
        if (!WgConfigDetector.looksLikeConfig(text)) throw WgConfigException("Keine WireGuard-Konfiguration")
        val p = profiles.import(name, text)
        settings.update { it.copy(activeProfileId = p.id, panic = false) }
        events.info(EventType.SETTINGS_CHANGED, "VPN-Profil aktiviert: ${p.name}")
        val s = settings.current()
        return when {
            s.protectionEnabled -> QuickSetupResult(p, protectionStarted = true, needsVpnPermission = false)
            controller.permissionIntent() == null -> {
                controller.setProtection(true)
                QuickSetupResult(p, protectionStarted = true, needsVpnPermission = false)
            }
            else -> QuickSetupResult(p, protectionStarted = false, needsVpnPermission = true)
        }
    }
}
