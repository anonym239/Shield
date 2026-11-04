package dev.shield.core.checks

enum class TestId { TUNNEL, SYSTEM_DNS, TEST_DOMAIN, UPSTREAM, LISTS, VPN }

enum class HealAction { RESTART_TUNNEL, UPDATE_LISTS }

/** Pure decision logic for self-healing (unit tested). */
class HealPolicy(private val maxPerHour: Int = MAX_PER_HOUR) {
    private val attempts = ArrayDeque<Long>()

    @Synchronized
    fun allow(now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first() > HOUR) attempts.removeFirst()
        if (attempts.size >= maxPerHour) return false
        attempts.addLast(now)
        return true
    }

    @Synchronized
    fun reset() = attempts.clear()

    companion object {
        const val MAX_PER_HOUR = 3
        private const val HOUR = 3_600_000L

        /** Lists only → reload lists; anything about tunnel/DNS/upstream/VPN → reconnect. */
        fun actionFor(failed: Set<TestId>): HealAction? = when {
            failed.isEmpty() -> null
            failed == setOf(TestId.LISTS) -> HealAction.UPDATE_LISTS
            else -> HealAction.RESTART_TUNNEL
        }
    }
}
