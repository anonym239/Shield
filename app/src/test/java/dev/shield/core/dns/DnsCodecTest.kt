package dev.shield.core.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsCodecTest {
    private val query = DnsCodec.buildQuery(0x1234, "Ads.Example.com", DnsCodec.TYPE_A)

    @Test
    fun parsesQuery() {
        val q = DnsCodec.parseQuery(query)!!
        assertEquals(0x1234, q.id)
        assertEquals("ads.example.com", q.name)
        assertEquals(DnsCodec.TYPE_A, q.type)
        assertEquals(query.size, q.questionEnd)
    }

    @Test
    fun rejectsResponsesAndGarbage() {
        val resp = query.copyOf().also { it[2] = 0x81.toByte() }
        assertNull(DnsCodec.parseQuery(resp))
        assertNull(DnsCodec.parseQuery(byteArrayOf(1, 2, 3)))
        val truncated = query.copyOf(query.size - 3)
        assertNull(DnsCodec.parseQuery(truncated))
        // label length pointing beyond the end
        val bad = query.copyOf().also { it[12] = 60 }
        assertNull(DnsCodec.parseQuery(bad))
    }

    @Test
    fun blockedAnswerForA() {
        val q = DnsCodec.parseQuery(query)!!
        val r = DnsCodec.blockedResponse(query, q, zeroIp = true)
        assertEquals(0x12, r[0].toInt() and 0xFF)
        assertEquals(0x34, r[1].toInt() and 0xFF)
        assertEquals(0x80, r[2].toInt() and 0x80) // QR
        assertEquals(DnsCodec.RCODE_NOERROR, DnsCodec.rcode(r))
        assertEquals(1, DnsCodec.u16(r, 6)) // one answer
        assertArrayEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))
        assertEquals(300, DnsCodec.minTtl(r))
    }

    @Test
    fun blockedAnswerForAaaaAndNxMode() {
        val q6 = DnsCodec.buildQuery(1, "tracker.net", DnsCodec.TYPE_AAAA)
        val r6 = DnsCodec.blockedResponse(q6, DnsCodec.parseQuery(q6)!!, zeroIp = true)
        assertArrayEquals(ByteArray(16), r6.copyOfRange(r6.size - 16, r6.size))
        val q = DnsCodec.parseQuery(query)!!
        val nx = DnsCodec.blockedResponse(query, q, zeroIp = false)
        assertEquals(DnsCodec.RCODE_NXDOMAIN, DnsCodec.rcode(nx))
        assertEquals(0, DnsCodec.u16(nx, 6))
    }

    @Test
    fun httpsRecordGetsNoData() {
        val qh = DnsCodec.buildQuery(1, "tracker.net", DnsCodec.TYPE_HTTPS)
        val r = DnsCodec.blockedResponse(qh, DnsCodec.parseQuery(qh)!!, zeroIp = true)
        assertEquals(DnsCodec.RCODE_NOERROR, DnsCodec.rcode(r))
        assertEquals(0, DnsCodec.u16(r, 6))
    }

    @Test
    fun servfailAndTtl() {
        val q = DnsCodec.parseQuery(query)!!
        assertEquals(DnsCodec.RCODE_SERVFAIL, DnsCodec.rcode(DnsCodec.servfail(query, q)))
        assertNull(DnsCodec.minTtl(DnsCodec.nxdomain(query, q)))
        assertNotNull(DnsCodec.minTtl(DnsCodec.blockedResponse(query, q, true)))
    }

    @Test
    fun withIdReplacesTransactionId() {
        val r = DnsCodec.withId(query, 0xBEEF)
        assertEquals(0xBEEF, DnsCodec.u16(r, 0))
        assertEquals(0x1234, DnsCodec.u16(query, 0))
    }
}
