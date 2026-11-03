package dev.shield.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.DnsQueryEntity
import dev.shield.data.db.SecurityEventDao
import dev.shield.data.db.SecurityEventEntity
import dev.shield.data.repo.AppRepository
import dev.shield.data.repo.RulesRepository
import dev.shield.data.repo.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ResultFilter { ALL, BLOCKED, ALLOWED }

data class LogFilter(val result: ResultFilter = ResultFilter.ALL, val uid: Int? = null, val search: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogViewModel @Inject constructor(
    dnsDao: DnsQueryDao,
    eventDao: SecurityEventDao,
    val apps: AppRepository,
    private val rules: RulesRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val filter = MutableStateFlow(LogFilter())

    val noHistory: StateFlow<Boolean> = settings.settings.map { it.noHistory }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val queries: StateFlow<List<DnsQueryEntity>> = filter.flatMapLatest { f ->
        dnsDao.observe(
            when (f.result) {
                ResultFilter.ALL -> null
                ResultFilter.BLOCKED -> "BLOCKED"
                ResultFilter.ALLOWED -> "ALLOWED"
            },
            f.uid, f.search.trim(), 500,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val events: StateFlow<List<SecurityEventEntity>> = eventDao.observe(500)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Apps that appear in the current list (for the per-app filter). */
    val appsInLog: StateFlow<List<Pair<Int, String>>> = combine(queries, filter) { q, _ ->
        q.map { it.uid to it.app }.distinct().sortedBy { it.second.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun block(domain: String) = viewModelScope.launch { rules.setCustomRuleReplacing(domain, allow = false) }
    fun allow(domain: String) = viewModelScope.launch { rules.setCustomRuleReplacing(domain, allow = true) }
}
