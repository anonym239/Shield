package dev.shield.core.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WgConfigParserTest {
    // Test keys (not used anywhere)
    private val priv = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val pub = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="

    private val conf = """
        [Interface]
        # Proton NL
        PrivateKey = $priv
        Address = 10.2.0.2/32, fd00::2/128
        DNS = 10.2.0.1
        MTU = 1420

        [Peer]
        PublicKey = $pub
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 185.159.157.1:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun parsesProtonStyleConfig() {
        val c = WgConfigParser.parse(conf)
        assertEquals(listOf("10.2.0.2/32", "fd00::2/128"), c.iface.addresses)
        assertEquals(1420, c.iface.mtu)
        assertEquals("185.159.157.1:51820", c.endpointSummary)
        assertEquals(25, c.peers.single().keepalive)
    }

    @Test
    fun buildsUapiWithHexKeys() {
        val uapi = WgConfigParser.parse(conf).toUapi()
        assertTrue(uapi.contains("private_key=c809f3e5317e95"))
        assertTrue(uapi.contains("allowed_ip=0.0.0.0/0\n"))
        assertTrue(uapi.contains("allowed_ip=::/0\n"))
        assertTrue(uapi.contains("endpoint=185.159.157.1:51820"))
        assertTrue(uapi.contains("persistent_keepalive_interval=25"))
    }

    @Test
    fun usesResolvedEndpoint() {
        val c = WgConfigParser.parse(conf.replace("185.159.157.1:51820", "vpn.example.org:51820"))
        assertEquals("vpn.example.org", c.endpointHost())
        val uapi = c.toUapi(mapOf("vpn.example.org:51820" to "203.0.113.5:51820"))
        assertTrue(uapi.contains("endpoint=203.0.113.5:51820"))
    }

    @Test
    fun hostPortHelpers() {
        assertEquals("2001:db8::1" to 51820, WgConfigParser.splitHostPort("[2001:db8::1]:51820"))
        assertEquals(null, WgConfigParser.splitHostPort("nohost"))
        assertEquals("[2001:db8::1]:1", WgConfigParser.joinHostPort("2001:db8::1", 1))
    }

    @Test(expected = WgConfigException::class)
    fun rejectsMissingKey() {
        WgConfigParser.parse(conf.replace("PrivateKey = $priv", ""))
    }

    @Test(expected = WgConfigException::class)
    fun rejectsBadKey() {
        WgConfigParser.parse(conf.replace(pub, "abc"))
    }

    @Test(expected = WgConfigException::class)
    fun rejectsMissingPeer() {
        WgConfigParser.parse(conf.substringBefore("[Peer]"))
    }
}
