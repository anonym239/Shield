package dev.shield.core.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealPolicyTest {
    @Test
    fun chooseAction() {
        assertNull(HealPolicy.actionFor(emptySet()))
        assertEquals(HealAction.UPDATE_LISTS, HealPolicy.actionFor(setOf(TestId.LISTS)))
        assertEquals(HealAction.RESTART_TUNNEL, HealPolicy.actionFor(setOf(TestId.UPSTREAM)))
        assertEquals(HealAction.RESTART_TUNNEL, HealPolicy.actionFor(setOf(TestId.LISTS, TestId.VPN)))
    }

    @Test
    fun noRestartLoop() {
        val p = HealPolicy(maxPerHour = 3)
        assertTrue(p.allow(0)); assertTrue(p.allow(1000)); assertTrue(p.allow(2000))
        assertFalse(p.allow(3000))
        assertTrue(p.allow(3_700_000)) // one hour later
        p.reset()
        assertTrue(p.allow(3_700_001))
    }
}
