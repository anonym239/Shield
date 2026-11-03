package dev.shield.core.dns

/** LRU cache of upstream answers that respects the TTL. */
class DnsCache(private val maxEntries: Int = 2048, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val response: ByteArray, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    private fun key(name: String, type: Int) = "$type|$name"

    @Synchronized
    fun get(name: String, type: Int, id: Int): ByteArray? {
        val e = map[key(name, type)] ?: return null
        if (e.expiresAt <= clock()) {
            map.remove(key(name, type))
            return null
        }
        return DnsCodec.withId(e.response, id)
    }

    /** Stores a successful answer. TTL is clamped to [30 s, 1 h]; NXDOMAIN 60 s. */
    @Synchronized
    fun put(name: String, type: Int, response: ByteArray) {
        val rcode = DnsCodec.rcode(response)
        val ttl = when (rcode) {
            DnsCodec.RCODE_NOERROR -> (DnsCodec.minTtl(response) ?: 60).coerceIn(30, 3600)
            DnsCodec.RCODE_NXDOMAIN -> 60
            else -> return
        }
        map[key(name, type)] = Entry(response.copyOf(), clock() + ttl * 1000L)
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
