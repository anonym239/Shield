package dev.shield.core.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistCoalesceTest {
    private val sel = setOf("adguard_dns", "easyprivacy")

    @Test
    fun skipsIdenticalRequestRightAfterADownload() {
        assertTrue(BlocklistRepository.shouldSkip(sel to 1_000L, sel, 5_000L))
    }

    @Test
    fun downloadsWhenNothingRanYet() {
        assertFalse(BlocklistRepository.shouldSkip(null, sel, 5_000L))
    }

    @Test
    fun downloadsWhenSelectionChanged() {
        assertFalse(BlocklistRepository.shouldSkip(sel to 1_000L, sel + "hagezi_pro", 5_000L))
    }

    @Test
    fun downloadsAgainAfterWindow() {
        assertFalse(BlocklistRepository.shouldSkip(sel to 0L, sel, BlocklistRepository.COALESCE_MS))
    }

    @Test
    fun clockGoingBackwardsDoesNotSkip() {
        assertFalse(BlocklistRepository.shouldSkip(sel to 10_000L, sel, 5_000L))
    }
}
