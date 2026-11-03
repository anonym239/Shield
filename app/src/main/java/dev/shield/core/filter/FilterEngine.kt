package dev.shield.core.filter

/** Category of a blocklist; phishing is shown as its own category in the dashboard. */
enum class ListCategory { ADS, TRACKING, MALWARE, PHISHING, CUSTOM }

/** Why a domain was allowed or blocked. */
enum class DecisionReason { CUSTOM_ALLOW, CUSTOM_BLOCK, LIST_EXCEPTION, LIST, SELFTEST, FIREWALL, NONE }

data class FilterDecision(
    val blocked: Boolean,
    val reason: DecisionReason,
    /** Human readable rule / list name. */
    val rule: String? = null,
    val category: ListCategory? = null,
)

/** A loaded subscription list. */
data class LoadedList(val id: String, val name: String, val category: ListCategory, val parsed: ParsedList)

/** User rule: "example.com" (domain + subdomains), "*.example.com" (only subdomains), "ads*.example.com" (glob). */
data class CustomRule(val pattern: String, val allow: Boolean)

/**
 * Filter engine (SPEC §5.4). Order: own allow list > own block list > list exceptions > lists > allow.
 * Lookup is a hash lookup per domain suffix: O(labels), far below 1 ms.
 * Thread-safe: the engine swaps immutable snapshots.
 */
class FilterEngine {

    private class Snapshot(
        val listNames: Array<String>,
        val listCategories: Array<ListCategory>,
        /** domain -> list index */
        val blocked: HashMap<String, Int>,
        val exceptions: HashMap<String, Int>,
        val customAllow: HashMap<String, String>,
        val customAllowSubOnly: HashMap<String, String>,
        val customBlock: HashMap<String, String>,
        val customBlockSubOnly: HashMap<String, String>,
        val customAllowGlobs: List<Pair<Regex, String>>,
        val customBlockGlobs: List<Pair<Regex, String>>,
        val ruleCount: Int,
        val listCounts: Map<String, Int>,
    )

    @Volatile
    private var lists: Snapshot = build(emptyList(), emptyList())
    private var currentLists: List<LoadedList> = emptyList()
    private var currentRules: List<CustomRule> = emptyList()

    val ruleCount: Int get() = lists.ruleCount
    val listCounts: Map<String, Int> get() = lists.listCounts

    @Synchronized
    fun setLists(loaded: List<LoadedList>) {
        currentLists = loaded
        lists = build(loaded, currentRules)
    }

    @Synchronized
    fun setCustomRules(rules: List<CustomRule>) {
        currentRules = rules
        lists = build(currentLists, rules)
    }

    fun decide(domainInput: String): FilterDecision {
        val domain = domainInput.trimEnd('.').lowercase()
        if (domain == SELFTEST_DOMAIN || domain.endsWith(".$SELFTEST_DOMAIN")) {
            return FilterDecision(true, DecisionReason.SELFTEST, "Selbsttest")
        }
        val s = lists
        // 1. own allow list
        matchCustom(domain, s.customAllow, s.customAllowSubOnly, s.customAllowGlobs)?.let {
            return FilterDecision(false, DecisionReason.CUSTOM_ALLOW, it, ListCategory.CUSTOM)
        }
        // 2. own block list
        matchCustom(domain, s.customBlock, s.customBlockSubOnly, s.customBlockGlobs)?.let {
            return FilterDecision(true, DecisionReason.CUSTOM_BLOCK, it, ListCategory.CUSTOM)
        }
        // 3. exceptions of subscribed lists (@@||domain^)
        var exceptionList = -1
        Domains.forEachSuffix(domain) { suffix ->
            val idx = s.exceptions[suffix]
            if (idx != null) exceptionList = idx
            idx != null
        }
        if (exceptionList >= 0) {
            return FilterDecision(false, DecisionReason.LIST_EXCEPTION, s.listNames[exceptionList])
        }
        // 4. subscribed lists
        var listIdx = -1
        Domains.forEachSuffix(domain) { suffix ->
            val idx = s.blocked[suffix]
            if (idx != null) listIdx = idx
            idx != null
        }
        if (listIdx >= 0) {
            return FilterDecision(true, DecisionReason.LIST, s.listNames[listIdx], s.listCategories[listIdx])
        }
        // 5. allow
        return FilterDecision(false, DecisionReason.NONE)
    }

    private fun matchCustom(
        domain: String,
        full: HashMap<String, String>,
        subOnly: HashMap<String, String>,
        globs: List<Pair<Regex, String>>,
    ): String? {
        var hit: String? = null
        Domains.forEachSuffix(domain) { suffix ->
            hit = full[suffix] ?: if (suffix != domain) subOnly[suffix] else null
            hit != null
        }
        if (hit != null) return hit
        return globs.firstOrNull { it.first.matches(domain) }?.second
    }

    private fun build(loaded: List<LoadedList>, rules: List<CustomRule>): Snapshot {
        val names = loaded.map { it.name }.toTypedArray()
        val cats = loaded.map { it.category }.toTypedArray()
        val total = loaded.sumOf { it.parsed.blocked.size }
        val blocked = HashMap<String, Int>(maxOf(16, (total / 0.75f).toInt() + 1))
        val exceptions = HashMap<String, Int>()
        val counts = LinkedHashMap<String, Int>()
        loaded.forEachIndexed { idx, l ->
            l.parsed.blocked.forEach { d -> blocked.putIfAbsent(d, idx) }
            l.parsed.allowed.forEach { d -> exceptions.putIfAbsent(d, idx) }
            counts[l.id] = l.parsed.blocked.size
        }
        val allow = HashMap<String, String>()
        val allowSub = HashMap<String, String>()
        val block = HashMap<String, String>()
        val blockSub = HashMap<String, String>()
        val allowGlobs = ArrayList<Pair<Regex, String>>()
        val blockGlobs = ArrayList<Pair<Regex, String>>()
        rules.forEach { r ->
            val p = r.pattern.trim().lowercase().trimEnd('.')
            val label = "Eigene Regel: $p"
            val (fullMap, subMap, globList) = if (r.allow) Triple(allow, allowSub, allowGlobs) else Triple(block, blockSub, blockGlobs)
            when {
                p.startsWith("*.") && !p.substring(2).contains('*') -> subMap[p.substring(2)] = label
                p.contains('*') -> globList.add(globToRegex(p) to label)
                else -> fullMap[p] = label
            }
        }
        return Snapshot(
            names, cats, blocked, exceptions, allow, allowSub, block, blockSub, allowGlobs, blockGlobs,
            blocked.size + rules.size, counts,
        )
    }

    companion object {
        /** Internal test domain that is always blocked (self-test point 3). */
        const val SELFTEST_DOMAIN = "selftest.shield.invalid"

        fun isValidPattern(p: String): Boolean {
            val t = p.trim().lowercase().trimEnd('.')
            if (t.isEmpty() || t.length > 253) return false
            return Regex("^[a-z0-9*_.-]+$").matches(t) && t.contains('.') && !t.startsWith('.') && !t.contains("..")
        }

        internal fun globToRegex(glob: String): Regex =
            Regex(glob.split('*').joinToString("[a-z0-9_.-]*") { Regex.escape(it) })
    }
}
