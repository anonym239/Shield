package dev.shield.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionLevelTest {
    @Test
    fun levelsAreSupersets() {
        assertTrue(ProtectionLevel.STRICT.lists.containsAll(ProtectionLevel.STANDARD.lists))
        assertTrue(ProtectionLevel.MAXIMUM.lists.containsAll(ProtectionLevel.STANDARD.lists))
        assertTrue("builtin_telemetry" in ProtectionLevel.STRICT.lists)
    }

    @Test
    fun maximumForcesFullTunnelAndKillSwitch() {
        val s = ProtectionLevel.MAXIMUM.applyTo(Settings(killSwitch = false))
        assertEquals(TunnelMode.FULL, s.effectiveTunnelMode)
        assertTrue(s.killSwitch)
        assertEquals(ProtectionLevel.MAXIMUM.lists, s.enabledLists)
        // back to standard keeps the user's tunnel choice
        val back = ProtectionLevel.STANDARD.applyTo(s.copy(tunnelMode = TunnelMode.DNS_ONLY))
        assertEquals(TunnelMode.DNS_ONLY, back.effectiveTunnelMode)
    }

    @Test
    fun everyLevelContainsUblockOriginDefaults() {
        ProtectionLevel.entries.forEach { assertTrue(it.name, it.lists.containsAll(UBLOCK_LISTS)) }
        assertTrue(Settings().enabledLists.containsAll(UBLOCK_LISTS))
    }

    @Test
    fun ublockListsExistAsSources() {
        val ids = dev.shield.core.filter.BlocklistRepository.SOURCES.map { it.id }.toSet()
        assertTrue(ids.containsAll(UBLOCK_LISTS))
        ProtectionLevel.entries.forEach { assertTrue(it.name, ids.containsAll(it.lists)) }
    }

    @Test
    fun migrationAddsUblockListsAndKeepsUserChoice() {
        val stored = setOf("adguard_dns", "hagezi_pro")
        val m = dev.shield.data.repo.migrateListSelection(stored)
        assertTrue(m.containsAll(stored))
        assertTrue(m.containsAll(UBLOCK_LISTS))
    }
}
