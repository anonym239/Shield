package dev.shield.core.vpn

import dev.shield.core.firewall.NetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoVpnPolicyTest {
    private val rules = AutoVpnRules(true, setOf("Heimnetz"), vpnOnOpenWifi = true, vpnOnUnknownWifi = true, vpnOnMobile = false)

    @Test
    fun homeWifiOnlyBlocker() {
        assertEquals(false, AutoVpnPolicy.wantsVpn(rules, NetworkState(NetType.WIFI, "\"Heimnetz\"", WifiSecurity.WPA3)))
    }

    @Test
    fun openWifiEvenWithHomeNameGetsVpn() {
        assertEquals(true, AutoVpnPolicy.wantsVpn(rules, NetworkState(NetType.WIFI, "Heimnetz", WifiSecurity.OPEN)))
        assertEquals(true, AutoVpnPolicy.wantsVpn(rules, NetworkState(NetType.WIFI, "Cafe", WifiSecurity.OPEN)))
    }

    @Test
    fun unknownWifiAndMobile() {
        assertEquals(true, AutoVpnPolicy.wantsVpn(rules, NetworkState(NetType.WIFI, "Hotel", WifiSecurity.WPA2)))
        assertEquals(false, AutoVpnPolicy.wantsVpn(rules, NetworkState(NetType.MOBILE)))
        assertEquals(false, AutoVpnPolicy.wantsVpn(rules.copy(vpnOnUnknownWifi = false), NetworkState(NetType.WIFI, "Hotel", WifiSecurity.WPA2)))
        assertNull(AutoVpnPolicy.wantsVpn(rules, NetworkState(NetType.NONE)))
        assertNull(AutoVpnPolicy.wantsVpn(rules.copy(enabled = false), NetworkState(NetType.MOBILE)))
    }
}
