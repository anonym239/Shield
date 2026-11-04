package dev.shield.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shield.core.checks.Overview
import dev.shield.core.checks.SelfTestRunner
import dev.shield.core.checks.StatusRepository
import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.firewall.AppPolicy
import dev.shield.core.vpn.NetworkMonitor
import dev.shield.core.vpn.NetworkState
import dev.shield.core.vpn.TunnelState
import dev.shield.core.vpn.VpnController
import dev.shield.data.Settings
import dev.shield.data.db.AppCount
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.DomainCount
import dev.shield.data.db.HourCount
import dev.shield.data.db.StatsDao
import dev.shield.data.repo.AppRepository
import dev.shield.data.repo.QueryRecorder
import dev.shield.data.repo.RulesRepository
import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardData(
    val blockedToday: Long = 0,
    val queriesToday: Long = 0,
    val phishingToday: Long = 0,
    val firewallBlocked: Int = 0,
    val hours: List<HourCount> = emptyList(),
    val topApps: List<AppCount> = emptyList(),
    val topDomains: List<DomainCount> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    val controller: VpnController,
    val apps: AppRepository,
    private val status: StatusRepository,
    private val selfTest: SelfTestRunner,
    private val settingsRepo: SettingsRepository,
    private val blocklists: BlocklistRepository,
    ai: dev.shield.core.checks.AiAssistant,
    statsDao: StatsDao,
    dnsDao: DnsQueryDao,
    rules: RulesRepository,
    network: NetworkMonitor,
    wgProfiles: dev.shield.core.wireguard.WgProfileRepository,
    profileMeta: dev.shield.data.repo.ProfileMetaStore,
) : ViewModel() {

    val tunnel: StateFlow<TunnelState> = controller.state
    val overview: StateFlow<Overview?> = status.overview
    val network: StateFlow<NetworkState> = network.state
    val selfTestRunning: StateFlow<Boolean> = selfTest.running
    val aiVerdict: StateFlow<dev.shield.core.checks.AiVerdict?> =
        ai.lastVerdict.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Name and country of the selected VPN server (the name the user gave it, never a file name). */
    val activeServer: StateFlow<ActiveServer?> = kotlinx.coroutines.flow.combine(
        settingsRepo.settings, wgProfiles.profiles, profileMeta.all,
    ) { s, ps, metas ->
        ps.firstOrNull { it.id == s.activeProfileId }?.let { ActiveServer(it.name, metas[it.id]?.country) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Re-evaluated every hour so "today" and the 24 h window move on. */
    private val clock = flow {
        while (true) {
            emit(Unit)
            delay(QueryRecorder.HOUR - System.currentTimeMillis() % QueryRecorder.HOUR + 1000)
        }
    }

    val data: StateFlow<DashboardData> = clock.flatMapLatest {
        val today = QueryRecorder.today()
        val nowHour = System.currentTimeMillis() / QueryRecorder.HOUR
        val since = System.currentTimeMillis() - 86_400_000L
        val counts = combine(statsDao.blockedOnDay(today), statsDao.queriesOnDay(today), statsDao.phishingSince(nowHour - 23)) { b, q, p ->
            Triple(b, q, p)
        }
        combine(
            counts,
            statsDao.hours(nowHour - 23),
            statsDao.topApps(today, 5),
            dnsDao.topBlockedDomains(since, 5),
            rules.appRules.map { r -> r.values.count { it.policy == AppPolicy.BLOCK } },
        ) { c, hours, top, domains, fw ->
            DashboardData(c.first, c.second, c.third, fw, fillHours(hours, nowHour), top, domains)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardData())

    private fun fillHours(list: List<HourCount>, nowHour: Long): List<HourCount> {
        val map = list.associateBy { it.hour }
        return (nowHour - 23..nowHour).map { h -> map[h] ?: HourCount(h, 0, 0) }
    }

    fun refresh() = status.refreshDeviceChecks()

    fun setProtection(on: Boolean) = viewModelScope.launch { controller.setProtection(on) }

    fun runSelfTest() = viewModelScope.launch { selfTest.run(full = true) }

    fun setPanic(on: Boolean) = viewModelScope.launch { controller.setPanic(on) }

    fun updateLists() = viewModelScope.launch { blocklists.update(force = true) }
}

data class ActiveServer(val name: String, val country: String?)
