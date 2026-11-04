package dev.shield.core.wireguard

import dev.shield.core.vpn.Countries
import dev.shield.core.vpn.VpnController
import dev.shield.data.db.WgProfileEntity
import dev.shield.data.repo.ProfileMetaStore
import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val meta: ProfileMetaStore,
) {
    private val _justImported = MutableStateFlow<String?>(null)

    /** Id of the profile imported last; the VPN screen opens its "Which server is this?" dialog once. */
    val justImported: StateFlow<String?> = _justImported.asStateFlow()

    fun consumeJustImported() {
        _justImported.value = null
    }

    /** Throws [WgConfigException] with a readable message if the text is no valid config. */
    suspend fun importAndActivate(name: String, text: String): QuickSetupResult {
        if (!WgConfigDetector.looksLikeConfig(text)) throw WgConfigException("Keine WireGuard-Konfiguration")
        val cfg = WgConfigParser.parse(text)
        // never show a meaningless file name like "Dokument": prefer Proton's server comment ("# NL-FREE#12")
        val guess = Countries.guess(name, text, cfg.endpointSummary)
        val p = profiles.import(guess.name, text)
        meta.setCountry(p.id, guess.country?.code)
        _justImported.value = p.id
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
