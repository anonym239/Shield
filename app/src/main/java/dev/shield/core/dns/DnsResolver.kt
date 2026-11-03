package dev.shield.core.dns

import dev.shield.core.filter.DecisionReason
import dev.shield.core.filter.FilterEngine
import dev.shield.core.filter.ListCategory

enum class QueryResult { ALLOWED, BLOCKED, ERROR }

/** Encrypted upstream (DoH). */
interface DnsUpstream {
    suspend fun resolve(query: ByteArray): ByteArray
}

/** Receives every handled query (protocol, statistics). */
fun interface QuerySink {
    fun record(
        uid: Int,
        domain: String,
        type: Int,
        result: QueryResult,
        rule: String?,
        category: ListCategory?,
        latencyMs: Int,
    )
}

/** Firewall hook: returns a reason text if all DNS of this uid must be blocked. */
fun interface DnsAppPolicy {
    fun blockReason(uid: Int): String?
}

/**
 * DNS handling shared by tunnel stage A (Kotlin packet loop) and stage B (Go core callback).
 * Pure Kotlin, unit tested.
 */
class DnsResolver(
    private val filter: FilterEngine,
    private val cache: DnsCache,
    private val sink: QuerySink,
    private val appPolicy: DnsAppPolicy,
    private val zeroIp: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Stage A: full resolution. Returns the DNS answer or null if the packet is not a query. */
    suspend fun resolve(uid: Int, query: ByteArray, upstream: DnsUpstream): ByteArray? {
        val q = DnsCodec.parseQuery(query) ?: return null
        filterParsed(uid, query, q)?.let { return it }
        val start = clock()
        return try {
            val answer = upstream.resolve(query)
            val fixed = DnsCodec.withId(answer, q.id)
            onUpstream(uid, query, q, fixed, (clock() - start).toInt())
        } catch (e: Exception) {
            sink.record(uid, q.name, q.type, QueryResult.ERROR, e.javaClass.simpleName, null, (clock() - start).toInt())
            DnsCodec.servfail(query, q)
        }
    }

    /**
     * Stage B: returns a final answer (blocked / cached) or null when the core has to forward
     * the query to the encrypted upstream. A malformed query gets an empty array (dropped).
     */
    fun filterOnly(uid: Int, query: ByteArray): ByteArray? {
        val q = DnsCodec.parseQuery(query) ?: return null
        return filterParsed(uid, query, q)
    }

    /**
     * Stage B: an upstream answer arrived (latency < 0 = failed). Returns the answer to deliver
     * (a blocked answer if CNAME cloaking was detected) or null to keep the original.
     */
    fun onUpstreamAnswer(uid: Int, query: ByteArray, answer: ByteArray, latencyMs: Long): ByteArray? {
        val q = DnsCodec.parseQuery(query) ?: return null
        if (latencyMs < 0) {
            sink.record(uid, q.name, q.type, QueryResult.ERROR, "Upstream", null, 0)
            return null
        }
        val result = onUpstream(uid, query, q, answer, latencyMs.toInt())
        return if (result === answer) null else result
    }

    private fun filterParsed(uid: Int, query: ByteArray, q: DnsQuery): ByteArray? {
        appPolicy.blockReason(uid)?.let { reason ->
            sink.record(uid, q.name, q.type, QueryResult.BLOCKED, reason, null, 0)
            return DnsCodec.nxdomain(query, q)
        }
        val decision = filter.decide(q.name)
        if (decision.blocked) {
            sink.record(uid, q.name, q.type, QueryResult.BLOCKED, decision.rule, decision.category, 0)
            return DnsCodec.blockedResponse(query, q, zeroIp())
        }
        cache.get(q.name, q.type, q.id)?.let { cached ->
            sink.record(uid, q.name, q.type, QueryResult.ALLOWED, ruleText(decision.reason, decision.rule, true), null, 0)
            return cached
        }
        return null
    }

    /** Returns the answer to deliver: the upstream answer, or a blocked answer for CNAME cloaking. */
    private fun onUpstream(uid: Int, query: ByteArray, q: DnsQuery, answer: ByteArray, latency: Int): ByteArray {
        // CNAME cloaking: a harmless looking name that points to a blocked tracker domain.
        for (target in DnsCodec.cnameTargets(answer)) {
            val d = filter.decide(target)
            if (d.blocked) {
                val blocked = DnsCodec.blockedResponse(query, q, zeroIp())
                sink.record(uid, q.name, q.type, QueryResult.BLOCKED, "CNAME → $target (${d.rule})", d.category, latency)
                return blocked
            }
        }
        cache.put(q.name, q.type, answer)
        val rcode = DnsCodec.rcode(answer)
        val result = if (rcode == DnsCodec.RCODE_SERVFAIL) QueryResult.ERROR else QueryResult.ALLOWED
        val decision = filter.decide(q.name)
        sink.record(uid, q.name, q.type, result, ruleText(decision.reason, decision.rule, false), null, latency)
        return answer
    }

    private fun ruleText(reason: DecisionReason, rule: String?, cached: Boolean): String? {
        val base = when (reason) {
            DecisionReason.CUSTOM_ALLOW, DecisionReason.LIST_EXCEPTION -> rule
            else -> null
        }
        return if (cached) listOfNotNull(base, "Cache").joinToString(" · ") else base
    }
}
