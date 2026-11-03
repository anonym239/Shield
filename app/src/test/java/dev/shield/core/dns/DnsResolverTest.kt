package dev.shield.core.dns

import dev.shield.core.filter.BlocklistParser
import dev.shield.core.filter.CustomRule
import dev.shield.core.filter.FilterEngine
import dev.shield.core.filter.ListCategory
import dev.shield.core.filter.LoadedList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class DnsResolverTest {
    data class Rec(val uid: Int, val domain: String, val result: QueryResult, val rule: String?)

    private val records = mutableListOf<Rec>()
    private val filter = FilterEngine().apply {
        setLists(listOf(LoadedList("t", "TestListe", ListCategory.ADS, BlocklistParser.parse("||ads.com^"))))
    }
    private var blockedUid = -99
    private val resolver = DnsResolver(
        filter, DnsCache(),
        { uid, d, _, r, rule, _, _ -> records += Rec(uid, d, r, rule) },
        { uid -> if (uid == blockedUid) "Firewall" else null },
        { true },
    )

    private class FakeUpstream(var fail: Boolean = false) : DnsUpstream {
        var calls = 0
        override suspend fun resolve(query: ByteArray): ByteArray {
            calls++
            if (fail) throw IOException("down")
            val q = DnsCodec.parseQuery(query)!!
            // reuse the codec to build a valid answer with id 0 (resolver must fix the id)
            return DnsCodec.withId(DnsCodec.blockedResponse(query, q, true), 0)
        }
    }

    @Test
    fun blocksListedDomainWithoutUpstream() = runBlocking {
        val up = FakeUpstream()
        val q = DnsCodec.buildQuery(5, "x.ads.com", 1)
        val r = resolver.resolve(10123, q, up)!!
        assertEquals(0, up.calls)
        assertEquals(5, DnsCodec.u16(r, 0))
        assertEquals(QueryResult.BLOCKED, records.single().result)
        assertEquals("TestListe", records.single().rule)
    }

    @Test
    fun firewallBlocksAllDnsOfApp() = runBlocking {
        blockedUid = 10200
        val r = resolver.resolve(10200, DnsCodec.buildQuery(1, "good.org", 1), FakeUpstream())!!
        assertEquals(DnsCodec.RCODE_NXDOMAIN, DnsCodec.rcode(r))
        assertEquals("Firewall", records.single().rule)
    }

    @Test
    fun forwardsAndCaches() = runBlocking {
        val up = FakeUpstream()
        val r1 = resolver.resolve(1, DnsCodec.buildQuery(77, "good.org", 1), up)!!
        assertEquals(77, DnsCodec.u16(r1, 0))
        val r2 = resolver.resolve(1, DnsCodec.buildQuery(78, "good.org", 1), up)!!
        assertEquals(78, DnsCodec.u16(r2, 0))
        assertEquals(1, up.calls)
        assertEquals(QueryResult.ALLOWED, records[1].result)
    }

    @Test
    fun upstreamFailureGivesServfail() = runBlocking {
        val r = resolver.resolve(1, DnsCodec.buildQuery(3, "fail.org", 1), FakeUpstream(fail = true))!!
        assertEquals(DnsCodec.RCODE_SERVFAIL, DnsCodec.rcode(r))
        assertEquals(QueryResult.ERROR, records.single().result)
    }

    @Test
    fun stageBFilterOnly() {
        assertNull(resolver.filterOnly(1, DnsCodec.buildQuery(1, "good.org", 1)))
        assertNotNull(resolver.filterOnly(1, DnsCodec.buildQuery(1, "ads.com", 1)))
        filter.setCustomRules(listOf(CustomRule("ads.com", allow = true)))
        assertNull(resolver.filterOnly(1, DnsCodec.buildQuery(1, "ads.com", 1)))
    }
}

/** CNAME cloaking: first-party looking name -> CNAME to a blocked tracker. */
class CnameCloakingTest {
    private fun cnameAnswer(query: ByteArray, target: String): ByteArray {
        val q = DnsCodec.parseQuery(query)!!
        val head = query.copyOf(q.questionEnd)
        DnsCodec.put16(head, 2, 0x8180)
        DnsCodec.put16(head, 6, 1)
        val labels = target.split('.')
        val rdata = ByteArray(labels.sumOf { it.length + 1 } + 1)
        var i = 0
        labels.forEach { l ->
            rdata[i++] = l.length.toByte()
            l.forEach { c -> rdata[i++] = c.code.toByte() }
        }
        val rr = ByteArray(12)
        rr[0] = 0xC0.toByte(); rr[1] = 0x0C
        DnsCodec.put16(rr, 2, DnsCodec.TYPE_CNAME)
        DnsCodec.put16(rr, 4, 1)
        rr[9] = 60
        DnsCodec.put16(rr, 10, rdata.size)
        return head + rr + rdata
    }

    @Test
    fun readsCnameTargets() {
        val q = DnsCodec.buildQuery(1, "metrics.shop.de", 1)
        assertEquals(listOf("tracker.ads.com"), DnsCodec.cnameTargets(cnameAnswer(q, "tracker.ads.com")))
        assertEquals(emptyList<String>(), DnsCodec.cnameTargets(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun blocksCloakedTracker() = runBlocking {
        val records = mutableListOf<String?>()
        val filter = FilterEngine().apply {
            setLists(listOf(LoadedList("t", "Liste", ListCategory.TRACKING, BlocklistParser.parse("||ads.com^"))))
        }
        val resolver = DnsResolver(filter, DnsCache(), { _, _, _, _, rule, _, _ -> records += rule }, { null }, { true })
        val q = DnsCodec.buildQuery(9, "metrics.shop.de", 1)
        val up = object : DnsUpstream {
            override suspend fun resolve(query: ByteArray) = cnameAnswer(query, "x.ads.com")
        }
        val r = resolver.resolve(1, q, up)!!
        assertEquals(1, DnsCodec.u16(r, 6))
        assertArrayEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))
        assertTrue(records.single()!!.startsWith("CNAME"))
        // stage B path returns a replacement
        assertNotNull(resolver.onUpstreamAnswer(1, q, cnameAnswer(q, "x.ads.com"), 5))
        assertNull(resolver.onUpstreamAnswer(1, q, cnameAnswer(q, "cdn.ok.net"), 5))
    }
}
