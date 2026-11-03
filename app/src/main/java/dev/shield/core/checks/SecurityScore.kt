package dev.shield.core.checks

/**
 * A weakness found by one of the checks. The weight is the number of points it costs;
 * the UI maps each finding to a localized recommendation.
 */
enum class Finding(val weight: Int) {
    PROTECTION_OFF(35),
    SELFTEST_FAILED(15),
    NO_SCREEN_LOCK(15),
    OPEN_WIFI_WITHOUT_VPN(12),
    DNS_LEAK(10),
    PATCH_OUTDATED(10),
    LISTS_OUTDATED(8),
    ADB_ENABLED(8),
    RISKY_APPS(6),
    ALWAYS_ON_OFF(5),
    PHISHING_OFF(5),
    DEV_OPTIONS(4),
    UNKNOWN_SOURCES(4),
    KILL_SWITCH_OFF(4),
    CAPTIVE_PORTAL(3),
}

data class ScoreResult(val score: Int, val topFindings: List<Finding>)

/** Score 0–100 from all checks (SPEC §2.3). Never claims "100 % sicher": 100 only means no known findings. */
object SecurityScore {
    fun compute(findings: Collection<Finding>, top: Int = 3): ScoreResult {
        val unique = findings.toSet()
        val score = (100 - unique.sumOf { it.weight }).coerceIn(0, 100)
        val ordered = unique.sortedWith(compareByDescending<Finding> { it.weight }.thenBy { it.ordinal })
        return ScoreResult(score, ordered.take(top))
    }
}
