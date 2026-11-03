package dev.shield.core.checks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusTest {
    private val ok = StatusInput(
        tunnelRunning = true, tunnelError = false, panic = false, lastSelfTestPassed = true,
        listsLoaded = true, listsAgeHours = 3, wgWanted = false, wgConnected = false, openWifi = false,
    )

    @Test
    fun greenWhenEverythingRuns() {
        assertEquals(StatusLevel.PROTECTED, ProtectionStatus.evaluate(ok).level)
    }

    @Test
    fun redWhenTunnelOffOrSelfTestFailed() {
        assertEquals(StatusLevel.UNPROTECTED, ProtectionStatus.evaluate(ok.copy(tunnelRunning = false)).level)
        val r = ProtectionStatus.evaluate(ok.copy(lastSelfTestPassed = false))
        assertEquals(StatusLevel.UNPROTECTED, r.level)
        assertEquals(listOf(StatusReason.SELFTEST_FAILED), r.reasons)
    }

    @Test
    fun yellowForMissingPieces() {
        assertEquals(StatusLevel.LIMITED, ProtectionStatus.evaluate(ok.copy(listsAgeHours = 50)).level)
        assertEquals(StatusLevel.LIMITED, ProtectionStatus.evaluate(ok.copy(wgWanted = true)).level)
        assertEquals(StatusLevel.LIMITED, ProtectionStatus.evaluate(ok.copy(openWifi = true)).level)
        assertEquals(StatusLevel.PROTECTED, ProtectionStatus.evaluate(ok.copy(openWifi = true, wgWanted = true, wgConnected = true)).level)
    }

    @Test
    fun findingsFeedTheScore() {
        val f = ProtectionStatus.findings(FindingInput(ok.copy(tunnelRunning = false), screenLock = false, adb = true))
        assertTrue(f.containsAll(listOf(Finding.PROTECTION_OFF, Finding.NO_SCREEN_LOCK, Finding.ADB_ENABLED)))
        assertEquals(100, SecurityScore.compute(ProtectionStatus.findings(FindingInput(ok))).score)
    }

    @Test
    fun permissionRules() {
        assertTrue(PermissionRules.unusualReason(setOf(SensitivePermission.ACCESSIBILITY, SensitivePermission.SMS), false) != null)
        assertEquals(null, PermissionRules.unusualReason(setOf(SensitivePermission.ACCESSIBILITY), true))
        assertEquals(null, PermissionRules.unusualReason(setOf(SensitivePermission.CAMERA), false))
    }
}
