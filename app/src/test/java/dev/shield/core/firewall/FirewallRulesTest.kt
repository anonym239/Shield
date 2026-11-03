package dev.shield.core.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallRulesTest {
    private val wifi = FirewallContext(NetType.WIFI, 12 * 60)
    private val mobile = FirewallContext(NetType.MOBILE, 12 * 60)

    @Test
    fun policies() {
        assertNull(FirewallRules.blockCause(null, wifi))
        assertEquals(BlockCause.RULE_BLOCK, FirewallRules.blockCause(AppRule("a", 1, AppPolicy.BLOCK), wifi))
        assertNull(FirewallRules.blockCause(AppRule("a", 1, AppPolicy.WIFI_ONLY), wifi))
        assertEquals(BlockCause.RULE_WIFI_ONLY, FirewallRules.blockCause(AppRule("a", 1, AppPolicy.WIFI_ONLY), mobile))
        assertEquals(BlockCause.RULE_MOBILE_ONLY, FirewallRules.blockCause(AppRule("a", 1, AppPolicy.MOBILE_ONLY), wifi))
        assertNull(FirewallRules.blockCause(AppRule("a", 1, AppPolicy.MOBILE_ONLY), mobile))
    }

    @Test
    fun nightWindowAcrossMidnight() {
        assertTrue(FirewallRules.inWindow(23 * 60 + 30, 23 * 60, 6 * 60))
        assertTrue(FirewallRules.inWindow(2 * 60, 23 * 60, 6 * 60))
        assertFalse(FirewallRules.inWindow(6 * 60, 23 * 60, 6 * 60))
        assertTrue(FirewallRules.inWindow(13 * 60, 12 * 60, 14 * 60))
        assertFalse(FirewallRules.inWindow(5, 10, 10))
    }

    @Test
    fun nightModeAndDataSaver() {
        val night = FirewallContext(NetType.WIFI, 1 * 60, nightEnabled = true)
        assertEquals(BlockCause.NIGHT_MODE, FirewallRules.blockCause(null, night))
        assertNull(FirewallRules.blockCause(AppRule("a", 1, nightAllowed = true), night))
        val saver = FirewallContext(NetType.MOBILE, 600, dataSaverEnabled = true)
        assertEquals(BlockCause.DATA_SAVER, FirewallRules.blockCause(AppRule("a", 1, dataSaver = true), saver))
        assertNull(FirewallRules.blockCause(AppRule("a", 1, dataSaver = true), saver.copy(netType = NetType.WIFI)))
    }
}
