package dev.shield.core.vpn

import android.os.ParcelFileDescriptor
import dev.shield.core.dns.DnsResolver
import dev.shield.core.dns.DnsUpstream
import dev.shield.core.dns.IpPacket
import dev.shield.core.dns.UdpPacket
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Tunnel stage A: only the virtual DNS addresses are routed into the TUN. Every UDP/53 packet
 * is parsed, filtered and answered (blocked locally or resolved via DoH).
 */
class DnsOnlyTunnel(
    private val pfd: ParcelFileDescriptor,
    private val resolver: DnsResolver,
    private val upstream: DnsUpstream,
    private val uidOf: (UdpPacket) -> Int,
    private val onFatal: (Throwable) -> Unit,
) : RunningTunnel {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(16))
    private val out = FileOutputStream(pfd.fileDescriptor)
    private val writeLock = Any()

    @Volatile
    private var closed = false

    private val reader = Thread({ loop() }, "shield-tun-a").apply { isDaemon = true }

    fun start(): DnsOnlyTunnel {
        reader.start()
        return this
    }

    private fun loop() {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n <= 0) {
                    if (n < 0) break
                    continue
                }
                val pkt = IpPacket.parseUdp(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                scope.launch { handle(pkt) }
            }
        } catch (e: IOException) {
            if (!closed) {
                ShieldLog.e("TunA", "TUN read failed", e)
                onFatal(e)
            }
        }
    }

    private suspend fun handle(pkt: UdpPacket) {
        try {
            val uid = uidOf(pkt)
            val answer = resolver.resolve(uid, pkt.payload, upstream) ?: return
            val reply = IpPacket.buildReply(pkt, answer)
            synchronized(writeLock) {
                if (!closed) out.write(reply)
            }
        } catch (e: Exception) {
            if (!closed) ShieldLog.w("TunA", "DNS handling failed", e)
        }
    }

    override fun close() {
        closed = true
        scope.cancel()
        runCatching { pfd.close() }
    }
}

/** Discards every packet (panic mode: the tunnel stays up, all traffic is dropped). */
class BlackholeTunnel(private val pfd: ParcelFileDescriptor) : RunningTunnel {
    @Volatile
    private var closed = false

    private val reader = Thread({
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!closed && input.read(buf) >= 0) Unit
        } catch (_: IOException) {
        }
    }, "shield-tun-panic").apply { isDaemon = true }

    fun start(): BlackholeTunnel {
        reader.start()
        return this
    }

    override fun close() {
        closed = true
        runCatching { pfd.close() }
    }
}

interface RunningTunnel {
    fun close()
}
