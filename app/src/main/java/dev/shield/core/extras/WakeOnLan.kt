package dev.shield.core.extras

/** Wake-on-LAN magic packet (6 x 0xFF + 16 x MAC). */
object WakeOnLan {
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.trim().replace(":", "").replace("-", "").replace(".", "")
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        val p = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) p[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(mac, 0, p, 6 + r * 6, 6)
        return p
    }
}
