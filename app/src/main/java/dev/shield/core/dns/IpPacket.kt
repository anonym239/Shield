package dev.shield.core.dns

import java.net.InetAddress

/** A UDP datagram read from the TUN interface (IPv4 or IPv6). */
class UdpPacket(
    val ipVersion: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
) {
    val srcAddress: InetAddress get() = InetAddress.getByAddress(src)
    val dstAddress: InetAddress get() = InetAddress.getByAddress(dst)
}

/** Parses and builds IPv4/IPv6 UDP packets for tunnel stage A. */
object IpPacket {
    private const val PROTO_UDP = 17

    fun parseUdp(b: ByteArray, length: Int): UdpPacket? {
        if (length < 1) return null
        return when ((b[0].toInt() shr 4) and 0xF) {
            4 -> parseV4(b, length)
            6 -> parseV6(b, length)
            else -> null
        }
    }

    private fun parseV4(b: ByteArray, length: Int): UdpPacket? {
        if (length < 28) return null
        val ihl = (b[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if (b[9].toInt() and 0xFF != PROTO_UDP) return null
        val flagsFrag = DnsCodec.u16(b, 6)
        if (flagsFrag and 0x1FFF != 0 || flagsFrag and 0x2000 != 0) return null // fragments unsupported
        val total = minOf(DnsCodec.u16(b, 2), length)
        val udpLen = DnsCodec.u16(b, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > total) return null
        return UdpPacket(
            4, b.copyOfRange(12, 16), b.copyOfRange(16, 20),
            DnsCodec.u16(b, ihl), DnsCodec.u16(b, ihl + 2),
            b.copyOfRange(ihl + 8, ihl + udpLen),
        )
    }

    private fun parseV6(b: ByteArray, length: Int): UdpPacket? {
        if (length < 48) return null
        if (b[6].toInt() and 0xFF != PROTO_UDP) return null // extension headers unsupported
        val payloadLen = DnsCodec.u16(b, 4)
        if (40 + payloadLen > length) return null
        val udpLen = DnsCodec.u16(b, 44)
        if (udpLen < 8 || udpLen > payloadLen) return null
        return UdpPacket(
            6, b.copyOfRange(8, 24), b.copyOfRange(24, 40),
            DnsCodec.u16(b, 40), DnsCodec.u16(b, 42),
            b.copyOfRange(48, 40 + udpLen),
        )
    }

    /** Builds the reply packet: addresses and ports swapped, checksums computed. */
    fun buildReply(req: UdpPacket, payload: ByteArray): ByteArray =
        if (req.ipVersion == 4) buildV4(req.dst, req.src, req.dstPort, req.srcPort, payload)
        else buildV6(req.dst, req.src, req.dstPort, req.srcPort, payload)

    fun buildV4(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        DnsCodec.put16(p, 2, total)
        DnsCodec.put16(p, 6, 0x4000) // don't fragment
        p[8] = 64
        p[9] = PROTO_UDP.toByte()
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        DnsCodec.put16(p, 10, checksum(p, 0, 20, 0))
        DnsCodec.put16(p, 20, sport)
        DnsCodec.put16(p, 22, dport)
        DnsCodec.put16(p, 24, udpLen)
        System.arraycopy(payload, 0, p, 28, payload.size)
        val pseudo = pseudoSum(src, dst, udpLen)
        var c = checksum(p, 20, udpLen, pseudo)
        if (c == 0) c = 0xFFFF
        DnsCodec.put16(p, 26, c)
        return p
    }

    fun buildV6(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val p = ByteArray(40 + udpLen)
        p[0] = 0x60
        DnsCodec.put16(p, 4, udpLen)
        p[6] = PROTO_UDP.toByte()
        p[7] = 64
        System.arraycopy(src, 0, p, 8, 16)
        System.arraycopy(dst, 0, p, 24, 16)
        DnsCodec.put16(p, 40, sport)
        DnsCodec.put16(p, 42, dport)
        DnsCodec.put16(p, 44, udpLen)
        System.arraycopy(payload, 0, p, 48, payload.size)
        val pseudo = pseudoSum(src, dst, udpLen)
        var c = checksum(p, 40, udpLen, pseudo)
        if (c == 0) c = 0xFFFF
        DnsCodec.put16(p, 46, c)
        return p
    }

    private fun pseudoSum(src: ByteArray, dst: ByteArray, udpLen: Int): Long {
        var sum = 0L
        var i = 0
        while (i < src.size) { sum += DnsCodec.u16(src, i); sum += DnsCodec.u16(dst, i); i += 2 }
        sum += PROTO_UDP
        sum += udpLen
        return sum
    }

    /** Internet checksum (RFC 1071). */
    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += DnsCodec.u16(b, i); i += 2 }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
