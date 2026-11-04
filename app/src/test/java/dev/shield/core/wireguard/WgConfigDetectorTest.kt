package dev.shield.core.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WgConfigDetectorTest {
    private val conf = """
        [Interface]
        # Key for Shield
        PrivateKey = aGVsbG8taGVsbG8taGVsbG8taGVsbG8taGVsbG8tMTI=
        Address = 10.2.0.2/32
        DNS = 10.2.0.1

        [Peer]
        # NL-FREE#12
        PublicKey = d29ybGQtd29ybGQtd29ybGQtd29ybGQtd29ybGQtMTI=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 185.107.56.1:51820
    """.trimIndent()

    @Test
    fun recognisesProtonConfig() = assertTrue(WgConfigDetector.looksLikeConfig(conf))

    @Test
    fun ignoresOtherText() {
        assertFalse(WgConfigDetector.looksLikeConfig(null))
        assertFalse(WgConfigDetector.looksLikeConfig("Hallo Opa, PrivateKey vergessen?"))
        assertFalse(WgConfigDetector.looksLikeConfig("[Interface]\nPrivateKey = x\n")) // no peer
        assertFalse(WgConfigDetector.looksLikeConfig(conf + " ".repeat(WgConfigDetector.MAX_SIZE)))
    }

    @Test
    fun namesFromFiles() {
        assertEquals("wg-NL-FREE-12", WgConfigDetector.nameFromFile("wg-NL-FREE-12.conf"))
        assertEquals("Proton DE#7", WgConfigDetector.nameFromFile("Proton%20DE%237.conf"))
        assertEquals("", WgConfigDetector.nameFromFile("1234"))
        assertEquals("", WgConfigDetector.nameFromFile(null))
        assertEquals("a b", WgConfigDetector.nameFromFile("/x/a<b.conf"))
    }
}
