package dev.shield.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.core.checks.AppPermissions
import dev.shield.core.checks.DeviceChecks
import dev.shield.core.checks.DeviceReport
import dev.shield.core.checks.LeakResult
import dev.shield.core.checks.LeakTest
import dev.shield.core.checks.StatusRepository
import dev.shield.core.checks.WifiReport
import dev.shield.core.extras.HomelabRepository
import dev.shield.core.extras.Reachability
import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.filter.BlocklistState
import dev.shield.core.vpn.NetworkMonitor
import dev.shield.core.vpn.NetworkState
import dev.shield.core.vpn.TunnelState
import dev.shield.core.vpn.VpnController
import dev.shield.core.wireguard.WgProfileRepository
import dev.shield.data.Settings
import dev.shield.data.db.CustomRuleEntity
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.HomelabTileEntity
import dev.shield.data.db.StatsDao
import dev.shield.data.db.WgProfileEntity
import dev.shield.data.repo.AppRepository
import dev.shield.data.repo.BackupRepository
import dev.shield.data.repo.RulesRepository
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val blocklists: BlocklistRepository,
    private val rules: RulesRepository,
    private val profiles: WgProfileRepository,
    private val checks: DeviceChecks,
    private val leakTest: LeakTest,
    private val status: StatusRepository,
    private val backup: BackupRepository,
    private val homelab: HomelabRepository,
    private val dnsDao: DnsQueryDao,
    private val statsDao: StatsDao,
    private val events: EventLogger,
    val apps: AppRepository,
    val controller: VpnController,
    network: NetworkMonitor,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val lists: StateFlow<BlocklistState> = blocklists.state
    val customRules: StateFlow<List<CustomRuleEntity>> = rules.customRules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val profileList: StateFlow<List<WgProfileEntity>> = profiles.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val network: StateFlow<NetworkState> = network.state
    val tunnel: StateFlow<TunnelState> = controller.state
    val tiles: StateFlow<List<HomelabTileEntity>> = homelab.tiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val device = MutableStateFlow<DeviceReport?>(null)
    val wifi = MutableStateFlow<WifiReport?>(null)
    val unusualApps = MutableStateFlow<List<AppPermissions>?>(null)
    val leak = MutableStateFlow<LeakResult?>(null)
    val leakRunning = MutableStateFlow(false)
    val reach = MutableStateFlow<Map<Long, Reachability>>(emptyMap())
    val busy = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch {
        settingsRepo.update(transform)
        events.info(EventType.SETTINGS_CHANGED, "Einstellungen geändert")
    }

    fun updateLists() = viewModelScope.launch {
        val ok = blocklists.update(force = true)
        message(context.getString(if (ok) dev.shield.R.string.lists_updated else dev.shield.R.string.lists_update_failed))
    }

    fun addRule(pattern: String, allow: Boolean) = viewModelScope.launch {
        if (!rules.setCustomRuleReplacing(pattern, allow)) message(context.getString(dev.shield.R.string.rule_invalid))
    }

    fun removeRule(pattern: String) = viewModelScope.launch { rules.removeCustomRule(pattern) }

    fun importProfile(name: String, text: String) = viewModelScope.launch {
        try {
            val p = profiles.import(name, text)
            message(context.getString(dev.shield.R.string.profile_imported, p.name))
        } catch (e: Exception) {
            message(context.getString(dev.shield.R.string.profile_import_failed, e.message ?: ""))
        }
    }

    fun importProfileFile(uri: Uri) = viewModelScope.launch {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()
        val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".conf") ?: ""
        if (text == null) message(context.getString(dev.shield.R.string.profile_import_failed, "")) else importProfile(name, text)
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        if (settings.value.activeProfileId == id) settingsRepo.update { it.copy(activeProfileId = null) }
        profiles.delete(id)
    }

    fun setActiveProfile(id: String?) = update { it.copy(activeProfileId = id) }

    fun refreshChecks() = viewModelScope.launch {
        device.value = checks.device()
        wifi.value = checks.wifi()
        unusualApps.value = checks.permissions().filter { it.unusual }
        status.refreshDeviceChecks()
    }

    fun runLeakTest() = viewModelScope.launch {
        leakRunning.value = true
        try {
            val r = leakTest.run()
            leak.value = r
            status.lastLeak.value = r
        } catch (e: Exception) {
            message(e.message ?: "Leak-Test fehlgeschlagen")
        } finally {
            leakRunning.value = false
        }
    }

    fun exportBackup(uri: Uri, password: String, includeProfiles: Boolean) = viewModelScope.launch {
        busy.value = true
        try {
            backup.export(uri, password.toCharArray(), includeProfiles)
            message(context.getString(dev.shield.R.string.backup_done))
        } catch (e: Exception) {
            message(context.getString(dev.shield.R.string.backup_failed, e.message ?: ""))
        } finally {
            busy.value = false
        }
    }

    fun importBackup(uri: Uri, password: String) = viewModelScope.launch {
        busy.value = true
        try {
            backup.import(uri, password.toCharArray())
            message(context.getString(dev.shield.R.string.restore_done))
        } catch (e: Exception) {
            message(context.getString(dev.shield.R.string.backup_failed, e.message ?: ""))
        } finally {
            busy.value = false
        }
    }

    fun clearHistory() = viewModelScope.launch {
        dnsDao.deleteAll()
        statsDao.deleteAllApp()
        statsDao.deleteAllHour()
        events.info(EventType.RETENTION, "Verlauf gelöscht")
        message(context.getString(dev.shield.R.string.history_cleared))
    }

    fun addTile(name: String, url: String) = viewModelScope.launch {
        val u = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
        if (u.toHttpUrlOk()) homelab.add(name.ifBlank { u }, u) else message(context.getString(dev.shield.R.string.url_invalid))
    }

    fun deleteTile(id: Long) = viewModelScope.launch { homelab.delete(id) }

    fun checkTiles() = viewModelScope.launch { reach.value = homelab.checkAll(tiles.value) }

    fun wake(mac: String, broadcast: String) = viewModelScope.launch {
        val ok = homelab.wake(mac, broadcast)
        message(context.getString(if (ok) dev.shield.R.string.wol_sent else dev.shield.R.string.wol_failed))
    }

    private fun String.toHttpUrlOk(): Boolean = runCatching { java.net.URI(this).host != null }.getOrDefault(false)
}
