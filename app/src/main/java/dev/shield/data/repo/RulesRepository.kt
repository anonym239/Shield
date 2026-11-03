package dev.shield.data.repo

import dev.shield.core.filter.CustomRule
import dev.shield.core.filter.FilterEngine
import dev.shield.core.firewall.AppPolicy
import dev.shield.core.firewall.AppRule
import dev.shield.data.db.AppRuleEntity
import dev.shield.data.db.CustomRuleEntity
import dev.shield.data.db.RuleDao
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Own block/allow rules and per-app firewall rules. */
@Singleton
class RulesRepository @Inject constructor(
    private val dao: RuleDao,
    private val events: EventLogger,
) {
    val customRules: Flow<List<CustomRuleEntity>> = dao.observeCustom()

    val appRules: Flow<Map<String, AppRule>> = dao.observeAppRules().map { list ->
        list.associate { it.packageName to it.toRule() }
    }

    /** Adds or replaces a rule. Returns false if the pattern is invalid. */
    suspend fun setCustomRule(pattern: String, allow: Boolean): Boolean {
        val p = pattern.trim().lowercase().trimEnd('.')
        if (!FilterEngine.isValidPattern(p)) return false
        dao.upsertCustom(CustomRuleEntity(pattern = p, action = if (allow) "ALLOW" else "BLOCK", createdAt = System.currentTimeMillis()))
        events.info(EventType.CUSTOM_RULE_CHANGED, "${if (allow) "Erlaubt" else "Blockiert"}: $p")
        return true
    }

    suspend fun setCustomRuleReplacing(pattern: String, allow: Boolean): Boolean {
        dao.deleteCustom(pattern.trim().lowercase().trimEnd('.'))
        return setCustomRule(pattern, allow)
    }

    suspend fun removeCustomRule(pattern: String) {
        dao.deleteCustom(pattern)
        events.info(EventType.CUSTOM_RULE_CHANGED, "Regel entfernt: $pattern")
    }

    suspend fun setAppRule(rule: AppRule) {
        dao.upsertAppRule(
            AppRuleEntity(rule.packageName, rule.uid, rule.policy.name, rule.nightAllowed, rule.dataSaver),
        )
        events.info(EventType.APP_RULE_CHANGED, "${rule.packageName}: ${rule.policy.name}")
    }

    companion object {
        fun CustomRuleEntity.toRule() = CustomRule(pattern, action == "ALLOW")
        fun AppRuleEntity.toRule() = AppRule(
            packageName, uid,
            AppPolicy.entries.firstOrNull { it.name == policy } ?: AppPolicy.ALLOW,
            nightAllowed, dataSaver,
        )
    }
}
