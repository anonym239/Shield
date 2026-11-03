package dev.shield.core.dns

/** A parsed DNS query (only the first question is relevant for filtering). */
data class DnsQuery(
    val id: Int,
    val flags: Int,
    val name: String,
    val type: Int,
    val qclass: Int,
    /** Offset right after the first question. */
    val questionEnd: Int,
)

/** Minimal DNS wire format codec (RFC 1035) for queries and synthetic answers. */
object DnsCodec {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65
    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    private const val HEADER = 12
    private const val BLOCK_TTL = 300

    fun parseQuery(b: ByteArray, length: Int = b.size): DnsQuery? {
        if (length < HEADER + 5) return null
        val flags = u16(b, 2)
        if (flags and 0x8000 != 0) return null // is a response
        if ((flags shr 11) and 0xF != 0) return null // only standard queries
        if (u16(b, 4) < 1) return null
        val sb = StringBuilder()
        var i = HEADER
        while (true) {
            if (i >= length) return null
            val l = b[i].toInt() and 0xFF
            if (l == 0) {
                i++
                break
            }
            if (l and 0xC0 != 0) return null // no compression in queries
            if (i + 1 + l > length || l > 63) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((b[i + k].toInt() and 0xFF).toChar().lowercaseChar())
            i += l + 1
            if (sb.length > 253) return null
        }
        if (i + 4 > length) return null
        return DnsQuery(u16(b, 0), flags, sb.toString(), u16(b, i), u16(b, i + 2), i + 4)
    }

    /** Synthetic answer for a blocked domain: 0.0.0.0 / :: for A/AAAA, else NXDOMAIN. */
    fun blockedResponse(query: ByteArray, q: DnsQuery, zeroIp: Boolean): ByteArray {
        val rdata = when {
            !zeroIp -> null
            q.type == TYPE_A -> ByteArray(4)
            q.type == TYPE_AAAA -> ByteArray(16)
            else -> null
        }
        return if (rdata == null) {
            // A/AAAA in NXDOMAIN mode, or other types: answer NXDOMAIN (HTTPS/SVCB etc. included)
            header(query, q, if (zeroIp && q.type != TYPE_A && q.type != TYPE_AAAA) RCODE_NOERROR else RCODE_NXDOMAIN, 0)
        } else {
            val head = header(query, q, RCODE_NOERROR, 1)
            val ans = ByteArray(12 + rdata.size)
            ans[0] = 0xC0.toByte(); ans[1] = 0x0C // pointer to the question name
            put16(ans, 2, q.type)
            put16(ans, 4, 1)
            put32(ans, 6, BLOCK_TTL)
            put16(ans, 10, rdata.size)
            System.arraycopy(rdata, 0, ans, 12, rdata.size)
            head + ans
        }
    }

    fun nxdomain(query: ByteArray, q: DnsQuery): ByteArray = header(query, q, RCODE_NXDOMAIN, 0)

    fun servfail(query: ByteArray, q: DnsQuery): ByteArray = header(query, q, RCODE_SERVFAIL, 0)

    /** Header + copy of the question. */
    private fun header(query: ByteArray, q: DnsQuery, rcode: Int, answers: Int): ByteArray {
        val out = ByteArray(q.questionEnd)
        System.arraycopy(query, 0, out, 0, q.questionEnd)
        val rd = q.flags and 0x0100
        put16(out, 2, 0x8000 or rd or 0x0080 or rcode) // QR, RD copied, RA, RCODE
        put16(out, 4, 1)
        put16(out, 6, answers)
        put16(out, 8, 0)
        put16(out, 10, 0)
        return out
    }

    fun rcode(response: ByteArray): Int = if (response.size >= 4) response[3].toInt() and 0x0F else -1

    /** Minimum TTL of the answer records, or null if none / unparsable. */
    fun minTtl(r: ByteArray): Int? {
        if (r.size < HEADER) return null
        val qd = u16(r, 4)
        val an = u16(r, 6)
        if (an == 0) {
            return null
        }
        var i = HEADER
        repeat(qd) {
            i = skipName(r, i) ?: return null
            i += 4
        }
        var min = Int.MAX_VALUE
        repeat(an) {
            i = skipName(r, i) ?: return null
            if (i + 10 > r.size) return null
            val ttl = ((r[i + 4].toLong() and 0xFF) shl 24 or ((r[i + 5].toLong() and 0xFF) shl 16) or
                ((r[i + 6].toLong() and 0xFF) shl 8) or (r[i + 7].toLong() and 0xFF)).toInt()
            val rdl = u16(r, i + 8)
            min = minOf(min, ttl.coerceAtLeast(0))
            i += 10 + rdl
            if (i > r.size) return null
        }
        return if (min == Int.MAX_VALUE) null else min
    }

    /**
     * Names that the answer section points to via CNAME (for CNAME-cloaking detection).
     * Handles name compression; returns an empty list on malformed input.
     */
    fun cnameTargets(r: ByteArray): List<String> {
        if (r.size < HEADER) return emptyList()
        val qd = u16(r, 4)
        val an = u16(r, 6)
        var i = HEADER
        repeat(qd) {
            i = skipName(r, i) ?: return emptyList()
            i += 4
        }
        val out = ArrayList<String>()
        repeat(an) {
            i = skipName(r, i) ?: return out
            if (i + 10 > r.size) return out
            val type = u16(r, i)
            val rdl = u16(r, i + 8)
            val rdStart = i + 10
            if (rdStart + rdl > r.size) return out
            if (type == TYPE_CNAME) readName(r, rdStart)?.let { out.add(it) }
            i = rdStart + rdl
        }
        return out
    }

    /** Reads a (possibly compressed) name; null if malformed or looping. */
    fun readName(b: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        var jumps = 0
        while (true) {
            if (i >= b.size) return null
            val l = b[i].toInt() and 0xFF
            when {
                l == 0 -> return sb.toString().lowercase()
                l and 0xC0 == 0xC0 -> {
                    if (i + 1 >= b.size || ++jumps > 16) return null
                    i = ((l and 0x3F) shl 8) or (b[i + 1].toInt() and 0xFF)
                }
                l and 0xC0 != 0 -> return null
                else -> {
                    if (i + 1 + l > b.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (k in 1..l) sb.append((b[i + k].toInt() and 0xFF).toChar())
                    if (sb.length > 253) return null
                    i += l + 1
                }
            }
        }
    }

    private fun skipName(b: ByteArray, start: Int): Int? {
        var i = start
        while (i < b.size) {
            val l = b[i].toInt() and 0xFF
            when {
                l == 0 -> return i + 1
                l and 0xC0 == 0xC0 -> return i + 2
                else -> i += l + 1
            }
        }
        return null
    }

    /** Builds a query packet (used by the self-test). */
    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val size = HEADER + labels.sumOf { it.length + 1 } + 1 + 4
        val b = ByteArray(size)
        put16(b, 0, id)
        put16(b, 2, 0x0100)
        put16(b, 4, 1)
        var i = HEADER
        labels.forEach { l ->
            b[i++] = l.length.toByte()
            l.forEach { c -> b[i++] = c.code.toByte() }
        }
        b[i++] = 0
        put16(b, i, type)
        put16(b, i + 2, 1)
        return b
    }

    fun withId(response: ByteArray, id: Int): ByteArray {
        val c = response.copyOf()
        put16(c, 0, id)
        return c
    }

    fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte()
    }
    private fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte(); b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte()
    }

    fun typeName(type: Int): String = when (type) {
        1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 6 -> "SOA"; 12 -> "PTR"; 15 -> "MX"; 16 -> "TXT"
        28 -> "AAAA"; 33 -> "SRV"; 64 -> "SVCB"; 65 -> "HTTPS"
        else -> "TYPE$type"
    }
}
