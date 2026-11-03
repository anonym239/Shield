package dev.shield.core.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsCacheTest {
    @Test
    fun respectsTtlAndRewritesId() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        val q = DnsCodec.buildQuery(1, "example.com", 1)
        val answer = DnsCodec.blockedResponse(q, DnsCodec.parseQuery(q)!!, true) // TTL 300
        cache.put("example.com", 1, answer)
        val hit = cache.get("example.com", 1, 0x4242)
        assertNotNull(hit)
        assertEquals(0x4242, DnsCodec.u16(hit!!, 0))
        now = 299_000
        assertNotNull(cache.get("example.com", 1, 1))
        now = 301_000
        assertNull(cache.get("example.com", 1, 1))
    }

    @Test
    fun doesNotCacheServfailAndEvicts() {
        val cache = DnsCache(maxEntries = 2)
        val q = DnsCodec.buildQuery(1, "a.com", 1)
        cache.put("a.com", 1, DnsCodec.servfail(q, DnsCodec.parseQuery(q)!!))
        assertEquals(0, cache.size())
        val ok = DnsCodec.blockedResponse(q, DnsCodec.parseQuery(q)!!, true)
        cache.put("a.com", 1, ok)
        cache.put("b.com", 1, ok)
        cache.put("c.com", 1, ok)
        assertEquals(2, cache.size())
        assertNull(cache.get("a.com", 1, 1))
    }
}
