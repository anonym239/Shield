package dev.shield.core.vpn

import android.net.ConnectivityManager
import android.net.VpnService
import dev.shield.core.dns.DnsResolver
import dev.shield.core.firewall.FirewallEngine
import dev.shield.logging.LogLevel
import dev.shield.logging.ShieldLog
import java.net.InetAddress
import java.net.InetSocketAddress

/** Callbacks from the Go core (stage B) into Kotlin. Called on Go threads. */
class GoBridge(
    private val service: VpnService,
    private val cm: ConnectivityManager,
    private val resolver: DnsResolver,
    private val firewall: FirewallEngine,
) : shieldcore.Bridge {

    override fun protect(fd: Int): Boolean = service.protect(fd)

    override fun findUid(proto: Int, srcAddr: String?, srcPort: Int, dstAddr: String?, dstPort: Int): Int =
        try {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByName(srcAddr), srcPort),
                InetSocketAddress(InetAddress.getByName(dstAddr), dstPort),
            )
        } catch (e: Exception) {
            -1
        }

    override fun allowFlow(uid: Int, proto: Int, dstAddr: String?, dstPort: Int): Boolean = firewall.allowFlow(uid)

    override fun filterDns(uid: Int, query: ByteArray?): ByteArray {
        if (query == null) return ByteArray(0)
        return try {
            resolver.filterOnly(uid, query) ?: ByteArray(0)
        } catch (e: Exception) {
            ShieldLog.e("Bridge", "filterDns failed", e)
            ByteArray(0)
        }
    }

    override fun dnsAnswered(uid: Int, query: ByteArray?, answer: ByteArray?, latencyMs: Long) {
        if (query == null || answer == null) return
        try {
            resolver.onUpstreamAnswer(uid, query, answer, latencyMs)
        } catch (e: Exception) {
            ShieldLog.e("Bridge", "dnsAnswered failed", e)
        }
    }

    override fun log(level: Int, msg: String?) {
        ShieldLog.log(LogLevel.fromPriority(level), "Core", msg ?: "", null)
    }
}
