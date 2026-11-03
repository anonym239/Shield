package dev.shield.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {
    private fun engine(): FilterEngine = FilterEngine().apply {
        setLists(
            listOf(
                LoadedList("ads", "Werbung", ListCategory.ADS, BlocklistParser.parse("||ads.com^\n@@||ok.ads.com^\n")),
                LoadedList("phish", "Phishing", ListCategory.PHISHING, BlocklistParser.parse("bad-bank.com")),
            ),
        )
    }

    @Test
    fun blocksDomainAndSubdomains() {
        val e = engine()
        assertTrue(e.decide("ads.com").blocked)
        assertTrue(e.decide("x.y.ads.com.").blocked)
        assertFalse(e.decide("notads.com").blocked)
        assertEquals(ListCategory.PHISHING, e.decide("login.bad-bank.com").category)
    }

    @Test
    fun listExceptionsWin() {
        val d = engine().decide("ok.ads.com")
        assertFalse(d.blocked)
        assertEquals(DecisionReason.LIST_EXCEPTION, d.reason)
    }

    @Test
    fun orderOwnAllowBeforeOwnBlockBeforeLists() {
        val e = engine()
        e.setCustomRules(
            listOf(
                CustomRule("ads.com", allow = true),
                CustomRule("ads.com", allow = false),
                CustomRule("*.google.com", allow = false),
                CustomRule("tracker*.example.org", allow = false),
            ),
        )
        assertEquals(DecisionReason.CUSTOM_ALLOW, e.decide("a.ads.com").reason)
        assertFalse(e.decide("a.ads.com").blocked)
        assertTrue(e.decide("mail.google.com").blocked)
        assertFalse(e.decide("google.com").blocked) // "*." only matches subdomains
        assertTrue(e.decide("tracker42.example.org").blocked)
        assertFalse(e.decide("www.example.org").blocked)
    }

    @Test
    fun customBlockBeatsListException() {
        val e = engine()
        e.setCustomRules(listOf(CustomRule("ok.ads.com", allow = false)))
        assertEquals(DecisionReason.CUSTOM_BLOCK, e.decide("ok.ads.com").reason)
    }

    @Test
    fun selfTestDomainAlwaysBlocked() {
        val e = FilterEngine()
        e.setCustomRules(listOf(CustomRule(FilterEngine.SELFTEST_DOMAIN, allow = true)))
        assertTrue(e.decide(FilterEngine.SELFTEST_DOMAIN).blocked)
    }

    @Test
    fun validatesPatterns() {
        assertTrue(FilterEngine.isValidPattern("*.example.com"))
        assertTrue(FilterEngine.isValidPattern("ads.example.com"))
        assertFalse(FilterEngine.isValidPattern("example"))
        assertFalse(FilterEngine.isValidPattern("exa mple.com"))
        assertFalse(FilterEngine.isValidPattern("..com"))
    }

    @Test
    fun lookupIsFastWithManyRules() {
        val domains = (0 until 200_000).joinToString("\n") { "host$it.tracker$it.com" }
        val e = FilterEngine()
        e.setLists(listOf(LoadedList("big", "Big", ListCategory.ADS, BlocklistParser.parse(domains))))
        assertEquals(200_000, e.ruleCount)
        val start = System.nanoTime()
        repeat(10_000) { e.decide("a.b.c.host$it.tracker$it.com") }
        val perLookupUs = (System.nanoTime() - start) / 10_000 / 1000.0
        assertTrue("lookup took $perLookupUs µs", perLookupUs < 1000)
    }
}
