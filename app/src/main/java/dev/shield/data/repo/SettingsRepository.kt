package dev.shield.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.data.BlockResponse
import dev.shield.data.DohUpstream
import dev.shield.data.ProtectionLevel
import dev.shield.data.Settings
import dev.shield.data.TunnelMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

const val LISTS_SCHEMA = 2

/** Adds uBlock Origin's default lists to a stored selection (pure, unit-tested). */
fun migrateListSelection(stored: Set<String>): Set<String> = stored + dev.shield.data.UBLOCK_LISTS

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val protection = booleanPreferencesKey("protection_enabled")
        val mode = stringPreferencesKey("tunnel_mode")
        val level = stringPreferencesKey("protection_level")
        val upstream = stringPreferencesKey("doh_upstream")
        val customUrl = stringPreferencesKey("custom_doh_url")
        val customBootstrap = stringPreferencesKey("custom_doh_bootstrap")
        val blockResponse = stringPreferencesKey("block_response")
        val lists = stringSetPreferencesKey("enabled_lists")
        val autoUpdate = booleanPreferencesKey("lists_auto_update")
        val wifiOnly = booleanPreferencesKey("lists_wifi_only")
        val retQ = intPreferencesKey("retention_queries")
        val retS = intPreferencesKey("retention_stats")
        val retE = intPreferencesKey("retention_events")
        val noHistory = booleanPreferencesKey("no_history")
        val profile = stringPreferencesKey("active_profile")
        val killSwitch = booleanPreferencesKey("kill_switch")
        val autoVpn = booleanPreferencesKey("auto_vpn")
        val homeSsids = stringSetPreferencesKey("home_ssids")
        val vpnOpen = booleanPreferencesKey("vpn_open_wifi")
        val vpnUnknown = booleanPreferencesKey("vpn_unknown_wifi")
        val vpnMobile = booleanPreferencesKey("vpn_mobile")
        val night = booleanPreferencesKey("night_mode")
        val nightStart = intPreferencesKey("night_start")
        val nightEnd = intPreferencesKey("night_end")
        val dataSaver = booleanPreferencesKey("data_saver")
        val weekly = booleanPreferencesKey("weekly_report")
        val homeIp = stringPreferencesKey("home_ip")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val panic = booleanPreferencesKey("panic")
        val quiet = booleanPreferencesKey("quiet_mode")
        val autoHeal = booleanPreferencesKey("auto_heal")
        val ai = booleanPreferencesKey("ai_enabled")
        val aiModel = stringPreferencesKey("ai_model")
        val aiResult = stringPreferencesKey("ai_result_json")
        val listsUpdatedAt = longPreferencesKey("lists_updated_at")
        val selfTest = stringPreferencesKey("self_test_json")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val listsSchema = intPreferencesKey("lists_schema")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val new = transform(prefs.toSettings())
            prefs.write(new)
        }
    }

    val listsUpdatedAt: Flow<Long> = context.dataStore.data.map { it[K.listsUpdatedAt] ?: 0L }
    suspend fun setListsUpdatedAt(time: Long) = context.dataStore.edit { it[K.listsUpdatedAt] = time }

    val selfTestJson: Flow<String?> = context.dataStore.data.map { it[K.selfTest] }
    suspend fun setSelfTestJson(json: String) = context.dataStore.edit { it[K.selfTest] = json }

    val aiResultJson: Flow<String?> = context.dataStore.data.map { it[K.aiResult] }
    suspend fun setAiResultJson(json: String) = context.dataStore.edit { it[K.aiResult] = json }

    /**
     * One-time upgrade of stored list selections: 1.2.0 adds uBlock Origin's lists to every level.
     * Returns true if the selection was changed.
     */
    suspend fun migrateLists(): Boolean {
        var changed = false
        context.dataStore.edit { prefs ->
            if ((prefs[K.listsSchema] ?: 1) < LISTS_SCHEMA) {
                val stored = prefs[K.lists]
                if (stored != null) {
                    val merged = migrateListSelection(stored)
                    changed = merged != stored
                    prefs[K.lists] = merged
                }
                prefs[K.listsSchema] = LISTS_SCHEMA
            }
        }
        return changed
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[K.onboarding] ?: false }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            protectionEnabled = this[K.protection] ?: d.protectionEnabled,
            tunnelMode = enumOr(this[K.mode], d.tunnelMode),
            protectionLevel = enumOr(this[K.level], d.protectionLevel),
            upstream = enumOr(this[K.upstream], d.upstream),
            customDohUrl = this[K.customUrl] ?: d.customDohUrl,
            customDohBootstrap = this[K.customBootstrap] ?: d.customDohBootstrap,
            blockResponse = enumOr(this[K.blockResponse], d.blockResponse),
            enabledLists = this[K.lists] ?: d.enabledLists,
            listsAutoUpdate = this[K.autoUpdate] ?: d.listsAutoUpdate,
            listsWifiOnly = this[K.wifiOnly] ?: d.listsWifiOnly,
            retentionQueriesDays = this[K.retQ] ?: d.retentionQueriesDays,
            retentionStatsDays = this[K.retS] ?: d.retentionStatsDays,
            retentionEventsDays = this[K.retE] ?: d.retentionEventsDays,
            noHistory = this[K.noHistory] ?: d.noHistory,
            activeProfileId = this[K.profile]?.takeIf { it.isNotEmpty() },
            killSwitch = this[K.killSwitch] ?: d.killSwitch,
            autoVpnEnabled = this[K.autoVpn] ?: d.autoVpnEnabled,
            homeSsids = this[K.homeSsids] ?: d.homeSsids,
            vpnOnOpenWifi = this[K.vpnOpen] ?: d.vpnOnOpenWifi,
            vpnOnUnknownWifi = this[K.vpnUnknown] ?: d.vpnOnUnknownWifi,
            vpnOnMobile = this[K.vpnMobile] ?: d.vpnOnMobile,
            nightModeEnabled = this[K.night] ?: d.nightModeEnabled,
            nightStartMinutes = this[K.nightStart] ?: d.nightStartMinutes,
            nightEndMinutes = this[K.nightEnd] ?: d.nightEndMinutes,
            dataSaverEnabled = this[K.dataSaver] ?: d.dataSaverEnabled,
            weeklyReportEnabled = this[K.weekly] ?: d.weeklyReportEnabled,
            homeIp = this[K.homeIp] ?: d.homeIp,
            dynamicColor = this[K.dynamicColor] ?: d.dynamicColor,
            panic = this[K.panic] ?: d.panic,
            quietMode = this[K.quiet] ?: d.quietMode,
            autoHeal = this[K.autoHeal] ?: d.autoHeal,
            aiEnabled = this[K.ai] ?: d.aiEnabled,
            aiModel = this[K.aiModel]?.takeIf { it.isNotBlank() } ?: d.aiModel,
        )
    }

    private fun MutablePreferences.write(s: Settings) {
        this[K.protection] = s.protectionEnabled
        this[K.mode] = s.tunnelMode.name
        this[K.level] = s.protectionLevel.name
        this[K.upstream] = s.upstream.name
        this[K.customUrl] = s.customDohUrl
        this[K.customBootstrap] = s.customDohBootstrap
        this[K.blockResponse] = s.blockResponse.name
        this[K.lists] = s.enabledLists
        this[K.autoUpdate] = s.listsAutoUpdate
        this[K.wifiOnly] = s.listsWifiOnly
        this[K.retQ] = s.retentionQueriesDays
        this[K.retS] = s.retentionStatsDays
        this[K.retE] = s.retentionEventsDays
        this[K.noHistory] = s.noHistory
        this[K.profile] = s.activeProfileId ?: ""
        this[K.killSwitch] = s.killSwitch
        this[K.autoVpn] = s.autoVpnEnabled
        this[K.homeSsids] = s.homeSsids
        this[K.vpnOpen] = s.vpnOnOpenWifi
        this[K.vpnUnknown] = s.vpnOnUnknownWifi
        this[K.vpnMobile] = s.vpnOnMobile
        this[K.night] = s.nightModeEnabled
        this[K.nightStart] = s.nightStartMinutes
        this[K.nightEnd] = s.nightEndMinutes
        this[K.dataSaver] = s.dataSaverEnabled
        this[K.weekly] = s.weeklyReportEnabled
        this[K.homeIp] = s.homeIp
        this[K.dynamicColor] = s.dynamicColor
        this[K.panic] = s.panic
        this[K.quiet] = s.quietMode
        this[K.autoHeal] = s.autoHeal
        this[K.ai] = s.aiEnabled
        this[K.aiModel] = s.aiModel
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default
}
