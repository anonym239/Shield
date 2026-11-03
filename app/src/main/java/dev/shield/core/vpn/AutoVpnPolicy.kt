package dev.shield.core.vpn

import dev.shield.core.firewall.NetType

enum class WifiSecurity { OPEN, OWE, WEP, WPA, WPA2, WPA3, UNKNOWN }

data class NetworkState(
    val type: NetType,
    val ssid: String? = null,
    val security: WifiSecurity = WifiSecurity.UNKNOWN,
    val captivePortal: Boolean = false,
    val validated: Boolean = true,
)

data class AutoVpnRules(
    val enabled: Boolean,
    val homeSsids: Set<String>,
    val vpnOnOpenWifi: Boolean,
    val vpnOnUnknownWifi: Boolean,
    val vpnOnMobile: Boolean,
)

/** Decides whether the WireGuard profile should be used on the current network (SPEC §2.1 Auto-VPN). */
object AutoVpnPolicy {
    fun isOpen(security: WifiSecurity) = security == WifiSecurity.OPEN || security == WifiSecurity.WEP

    /** @return true = VPN on, false = only blocker, null = no change (no auto rules). */
    fun wantsVpn(rules: AutoVpnRules, net: NetworkState): Boolean? {
        if (!rules.enabled) return null
        return when (net.type) {
            NetType.WIFI -> {
                val ssid = net.ssid?.trim('"')
                when {
                    ssid != null && rules.homeSsids.any { it.equals(ssid, ignoreCase = false) } && !isOpen(net.security) -> false
                    isOpen(net.security) -> rules.vpnOnOpenWifi
                    else -> rules.vpnOnUnknownWifi
                }
            }
            NetType.MOBILE -> rules.vpnOnMobile
            NetType.OTHER -> rules.vpnOnUnknownWifi
            NetType.NONE -> null
        }
    }
}
