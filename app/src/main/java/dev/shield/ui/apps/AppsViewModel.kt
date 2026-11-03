package dev.shield.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shield.core.checks.AppPermissions
import dev.shield.core.checks.DeviceChecks
import dev.shield.core.firewall.AppPolicy
import dev.shield.core.firewall.AppRule
import dev.shield.data.Settings
import dev.shield.data.db.AppCount
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.DomainCount
import dev.shield.data.db.StatsDao
import dev.shield.data.repo.AppInfo
import dev.shield.data.repo.AppRepository
import dev.shield.data.repo.QueryRecorder
import dev.shield.data.repo.RulesRepository
import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    val apps: AppRepository,
    private val rules: RulesRepository,
    private val checks: DeviceChecks,
    statsDao: StatsDao,
    private val dnsDao: DnsQueryDao,
    settings: SettingsRepository,
) : ViewModel() {
    val installed = MutableStateFlow<List<AppInfo>>(emptyList())
    val loading = MutableStateFlow(true)
    val permissions = MutableStateFlow<List<AppPermissions>?>(null)

    val settings: StateFlow<Settings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val today: StateFlow<Map<Int, AppCount>> = statsDao.appsSince(QueryRecorder.today())
        .map { l -> l.associateBy { it.uid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val ranking: StateFlow<List<AppCount>> = statsDao.topAppsByQueries7d()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rulesByPkg: StateFlow<Map<String, AppRule>> = rules.appRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            installed.value = apps.installedApps()
            loading.value = false
        }
    }

    private fun StatsDao.topAppsByQueries7d(): Flow<List<AppCount>> =
        appsSince(QueryRecorder.dayOf(LocalDate.now().minusDays(6))).map { l -> l.sortedByDescending { it.queries }.take(50) }

    fun loadPermissions() = viewModelScope.launch {
        if (permissions.value == null) permissions.value = checks.permissions()
    }

    fun setPolicy(app: AppInfo, policy: AppPolicy) = viewModelScope.launch {
        val old = rulesByPkg.value[app.packageName] ?: AppRule(app.packageName, app.uid)
        rules.setAppRule(old.copy(uid = app.uid, policy = policy))
    }

    fun setNightAllowed(app: AppInfo, allowed: Boolean) = viewModelScope.launch {
        val old = rulesByPkg.value[app.packageName] ?: AppRule(app.packageName, app.uid)
        rules.setAppRule(old.copy(uid = app.uid, nightAllowed = allowed))
    }

    fun setDataSaver(app: AppInfo, on: Boolean) = viewModelScope.launch {
        val old = rulesByPkg.value[app.packageName] ?: AppRule(app.packageName, app.uid)
        rules.setAppRule(old.copy(uid = app.uid, dataSaver = on))
    }

    fun topDomains(uid: Int): Flow<List<DomainCount>> =
        dnsDao.topDomainsForUid(uid, System.currentTimeMillis() - 7 * 86_400_000L, 20)

    fun topBlocked(uid: Int): Flow<List<DomainCount>> =
        dnsDao.topBlockedForUid(uid, System.currentTimeMillis() - 7 * 86_400_000L, 10)
}
