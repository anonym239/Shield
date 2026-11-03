package dev.shield.core.checks

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityScoreTest {
    @Test
    fun noFindingsIs100() {
        assertEquals(100, SecurityScore.compute(emptyList()).score)
    }

    @Test
    fun subtractsWeightsOncePerFinding() {
        val r = SecurityScore.compute(listOf(Finding.ADB_ENABLED, Finding.ADB_ENABLED, Finding.DEV_OPTIONS))
        assertEquals(100 - 8 - 4, r.score)
    }

    @Test
    fun clampsAtZeroAndOrdersTopThree() {
        val r = SecurityScore.compute(Finding.entries)
        assertEquals(0, r.score)
        assertEquals(listOf(Finding.PROTECTION_OFF, Finding.SELFTEST_FAILED, Finding.NO_SCREEN_LOCK), r.topFindings)
    }
}
