package dev.shield.core.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun buildsMagicPacket() {
        val mac = WakeOnLan.parseMac("AA:bb-cc:dd:ee:01")!!
        val p = WakeOnLan.magicPacket(mac)
        assertEquals(102, p.size)
        assertEquals(0xFF.toByte(), p[5])
        assertEquals(0xAA.toByte(), p[6])
        assertEquals(0x01.toByte(), p[101])
    }

    @Test
    fun rejectsInvalidMac() {
        assertNull(WakeOnLan.parseMac("zz:bb:cc:dd:ee:ff"))
        assertNull(WakeOnLan.parseMac("aa:bb"))
    }
}
