package dev.shield.core.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistParserTest {
    @Test
    fun parsesHostsFile() {
        val r = BlocklistParser.parse(
            """
            # comment
            127.0.0.1 localhost
            0.0.0.0 ads.example.com
            0.0.0.0	tracker.example.net # inline comment
            ::1 ip6-localhost
            0.0.0.0 not_a..domain
            """.trimIndent(),
        )
        assertEquals(setOf("ads.example.com", "tracker.example.net"), r.blocked)
        assertEquals(1, r.skippedLines)
    }

    @Test
    fun parsesAdguardSyntax() {
        val r = BlocklistParser.parse(
            """
            ! Title: test
            [Adblock Plus 2.0]
            ||doubleclick.net^
            ||Metrics.Example.ORG^${'$'}important
            @@||allowed.example.com^
            ||thirdparty.com^${'$'}third-party
            ||path.com/ads.js
            example.com##.banner
            /regex/
            """.trimIndent(),
        )
        assertEquals(setOf("doubleclick.net", "metrics.example.org"), r.blocked)
        assertEquals(setOf("allowed.example.com"), r.allowed)
        assertEquals(4, r.skippedLines)
    }

    @Test
    fun parsesPlainDomainList() {
        val r = BlocklistParser.parse("a.com\nb.org.\n*.c.net\nlocalhost\n")
        assertTrue(r.blocked.containsAll(listOf("a.com", "b.org", "c.net")))
        assertEquals(3, r.blocked.size)
    }
}

class AggressiveParserTest {
    @Test
    fun thirdPartyRulesOnlyInAggressiveMode() {
        val text = "||tracker.example^\$third-party\n||cdn.example^\$script,third-party\n@@||ok.example^\$third-party\n"
        assertEquals(emptySet<String>(), BlocklistParser.parse(text).blocked)
        val agg = BlocklistParser.parse(text, aggressive = true)
        assertEquals(setOf("tracker.example"), agg.blocked) // $script is still not DNS-applicable
        assertEquals(emptySet<String>(), agg.allowed) // exceptions are never widened
    }
}
