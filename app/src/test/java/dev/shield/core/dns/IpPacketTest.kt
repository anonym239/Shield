package dev.shield.core.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketTest {
    private val app = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns = byteArrayOf(10, 111, 222.toByte(), 3)

    @Test
    fun ipv4RoundTripAndChecksums() {
        val payload = DnsCodec.buildQuery(7, "example.com", 1)
        val pkt = IpPacket.buildV4(app, dns, 40000, 53, payload)
        assertEquals(0, IpPacket.checksum(pkt, 0, 20, 0)) // header checksum valid
        val parsed = IpPacket.parseUdp(pkt, pkt.size)!!
        assertEquals(4, parsed.ipVersion)
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)

        val reply = IpPacket.buildReply(parsed, byteArrayOf(1, 2, 3))
        val back = IpPacket.parseUdp(reply, reply.size)!!
        assertArrayEquals(dns, back.src)
        assertArrayEquals(app, back.dst)
        assertEquals(53, back.srcPort)
        assertEquals(40000, back.dstPort)
    }

    @Test
    fun ipv6RoundTrip() {
        val a = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val d = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 3 }
        val payload = DnsCodec.buildQuery(9, "example.org", 28)
        val pkt = IpPacket.buildV6(a, d, 5353, 53, payload)
        val parsed = IpPacket.parseUdp(pkt, pkt.size)!!
        assertEquals(6, parsed.ipVersion)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun ignoresTcpAndShortPackets() {
        val pkt = IpPacket.buildV4(app, dns, 1, 53, byteArrayOf(0))
        pkt[9] = 6 // TCP
        assertNull(IpPacket.parseUdp(pkt, pkt.size))
        assertNull(IpPacket.parseUdp(ByteArray(10), 10))
    }
}
