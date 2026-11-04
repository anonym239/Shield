package dev.shield.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** uBlock Origin list syntax as far as a DNS filter can apply it. */
class UblockSyntaxTest {
    private val sample = """
        ! Title: uBlock filters
        !#if env_mobile
        ||ads.example^
        !#endif
        ||popads.example^${'$'}doc
        ||tracker.example^${'$'}3p
        ||cdn.example^${'$'}script,3p
        ||site.example/ads/*
        example.org##.ad-banner
        example.org##+js(set, adsEnabled, false)
        @@||unbreak.example^
        @@||page.example^${'$'}doc
        @@||frame.example^${'$'}ghide
        -ad-banner.
        ads.js
        /banner/*${'$'}image
    """.trimIndent()

    @Test
    fun takesOnlyDomainRules() {
        val r = BlocklistParser.parse(sample, aggressive = false, abp = true)
        assertEquals(setOf("ads.example", "popads.example"), r.blocked)
        assertEquals(setOf("unbreak.example"), r.allowed)
    }

    @Test
    fun aggressiveAddsThirdPartyDomains() {
        val r = BlocklistParser.parse(sample, aggressive = true, abp = true)
        assertTrue("tracker.example" in r.blocked)
        assertFalse("cdn.example" in r.blocked) // $script cannot be applied by DNS
    }

    @Test
    fun docExceptionNeverWhitelistsADomain() {
        assertFalse("page.example" in BlocklistParser.parse(sample, abp = true).allowed)
    }

    @Test
    fun bareTokensAreNotHostsInAdblockLists() {
        val r = BlocklistParser.parse("ads.js\nbanner.example.com\n", abp = true)
        assertTrue(r.blocked.isEmpty())
        // ...but still in plain domain lists
        assertEquals(setOf("ads.js", "banner.example.com"), BlocklistParser.parse("ads.js\nbanner.example.com\n").blocked)
    }

    @Test
    fun findsIncludes() {
        val lines = sequenceOf("! x", "!#include filters-2024.txt", "!#include filters-2024.txt", "  !#include part.txt  ")
        assertEquals(listOf("filters-2024.txt", "part.txt"), ListIncludes.find(lines))
    }

    @Test
    fun resolvesOnlyFilesNextToTheList() {
        val base = "https://ublockorigin.github.io/uAssets/filters/filters.txt"
        assertEquals("https://ublockorigin.github.io/uAssets/filters/filters-2024.txt", ListIncludes.resolve(base, "filters-2024.txt"))
        assertNull(ListIncludes.resolve(base, "../secret.txt"))
        assertNull(ListIncludes.resolve(base, "https://evil.example/x.txt"))
        assertNull(ListIncludes.resolve(base, "//evil.example/x.txt"))
        assertNull(ListIncludes.resolve(base, "/etc/passwd"))
        assertNull(ListIncludes.resolve("http://insecure.example/list.txt", "a.txt"))
        assertNull(ListIncludes.resolve(base, "a b.txt"))
    }
}
