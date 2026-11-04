package dev.shield.data.repo

import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.filter.FilterEngine
import dev.shield.core.firewall.FirewallEngine
import dev.shield.data.repo.RulesRepository.Companion.toRule
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the filter and firewall engines in sync with the stored rules and settings. */
@Singleton
class RuleSync @Inject constructor(
    private val rules: RulesRepository,
    private val settings: SettingsRepository,
    private val filter: FilterEngine,
    private val firewall: FirewallEngine,
    private val blocklists: BlocklistRepository,
    private val events: EventLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private suspend fun download() {
        try {
            blocklists.update()
        } catch (e: Exception) {
            ShieldLog.e("RuleSync", "list download failed", e)
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            rules.customRules.collect { list -> filter.setCustomRules(list.map { it.toRule() }) }
        }
        scope.launch {
            combine(rules.appRules, settings.settings) { r, s -> r to s }.collect { (r, s) -> firewall.update(r.values, s) }
        }
        scope.launch {
            try {
                if (settings.migrateLists()) {
                    events.info(EventType.SETTINGS_CHANGED, "uBlock-Origin-Listen wurden zum Schutz hinzugefügt")
                }
                blocklists.loadFromDisk()
                val enabled = settings.current().enabledLists
                val missing = blocklists.state.value.lists.values.any { it.id in enabled && it.rules == 0 && it.updatedAt == 0L }
                // downloads never block reacting to setting changes (they serialise themselves)
                if (missing) launch { download() }
            } catch (e: Exception) {
                ShieldLog.e("RuleSync", "initial list load failed", e)
            }
            // reload when the selection of lists changes
            settings.settings.map { it.enabledLists to it.protectionLevel }.distinctUntilChanged().drop(1).collect {
                // apply built-in and already downloaded lists immediately, download the rest in the background
                blocklists.loadFromDisk()
                launch { download() }
            }
        }
    }
}
