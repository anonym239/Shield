package dev.shield.core.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.core.firewall.NetType
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the physical (non-VPN) networks: type, WLAN name, WLAN encryption and captive portals.
 * Network changes are logged as security events.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventLogger,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val _state = MutableStateFlow(NetworkState(NetType.NONE))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    @Volatile
    var currentNetwork: Network? = null
        private set

    private var started = false

    private val callback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(network, caps)
                override fun onLost(network: Network) = lost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(network, caps)
                override fun onLost(network: Network) = lost(network)
            }
        }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(req, callback)
        } catch (e: RuntimeException) {
            ShieldLog.e("Network", "registerNetworkCallback failed", e)
        }
    }

    private fun changed(network: Network, caps: NetworkCapabilities) {
        networks[network] = caps
        recompute()
    }

    private fun lost(network: Network) {
        networks.remove(network)
        recompute()
    }

    @Synchronized
    private fun recompute() {
        val best = networks.entries.sortedBy { (_, c) ->
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                else -> 2
            }
        }.firstOrNull()
        currentNetwork = best?.key
        val new = if (best == null) NetworkState(NetType.NONE) else toState(best.value)
        val old = _state.value
        _state.value = new
        if (old.type != new.type || old.ssid != new.ssid || old.security != new.security) {
            events.info(
                EventType.NETWORK_CHANGED,
                describe(new),
                mapOf("type" to new.type.name, "security" to new.security.name, "captive" to new.captivePortal),
            )
            if (new.type == NetType.WIFI && AutoVpnPolicy.isOpen(new.security)) {
                events.warn(EventType.OPEN_WIFI, "Offenes WLAN ohne Verschlüsselung")
            }
        }
    }

    private fun toState(c: NetworkCapabilities): NetworkState {
        val type = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.MOBILE
            else -> NetType.OTHER
        }
        var ssid: String? = null
        var security = WifiSecurity.UNKNOWN
        if (type == NetType.WIFI) {
            val info = wifiInfo(c)
            ssid = info?.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID && it.isNotBlank() }?.trim('"')
            security = securityOf(info)
        }
        return NetworkState(
            type = type,
            ssid = ssid,
            security = security,
            captivePortal = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            validated = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(c: NetworkCapabilities): WifiInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.transportInfo as? WifiInfo
        } else {
            runCatching { wifi?.connectionInfo }.getOrNull()
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun securityOf(info: WifiInfo?): WifiSecurity {
        if (info == null) return WifiSecurity.UNKNOWN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return when (info.currentSecurityType) {
                WifiInfo.SECURITY_TYPE_OPEN -> WifiSecurity.OPEN
                WifiInfo.SECURITY_TYPE_WEP -> WifiSecurity.WEP
                WifiInfo.SECURITY_TYPE_PSK -> WifiSecurity.WPA2
                WifiInfo.SECURITY_TYPE_EAP -> WifiSecurity.WPA2
                WifiInfo.SECURITY_TYPE_SAE, WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
                WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> WifiSecurity.WPA3
                WifiInfo.SECURITY_TYPE_OWE -> WifiSecurity.OWE
                else -> WifiSecurity.UNKNOWN
            }
        }
        // Android 10/11: look up the scan result of the connected access point
        val bssid = info.bssid ?: return WifiSecurity.UNKNOWN
        val scan = runCatching { wifi?.scanResults }.getOrNull()?.firstOrNull { it.BSSID == bssid } ?: return WifiSecurity.UNKNOWN
        val cap = scan.capabilities ?: ""
        return when {
            cap.contains("SAE") -> WifiSecurity.WPA3
            cap.contains("OWE") -> WifiSecurity.OWE
            cap.contains("WPA2") || cap.contains("RSN") -> WifiSecurity.WPA2
            cap.contains("WPA") -> WifiSecurity.WPA
            cap.contains("WEP") -> WifiSecurity.WEP
            else -> WifiSecurity.OPEN
        }
    }

    companion object {
        fun describe(s: NetworkState): String = when (s.type) {
            NetType.WIFI -> "WLAN ${s.ssid ?: ""} (${s.security.name})".replace("  ", " ")
            NetType.MOBILE -> "Mobilfunk"
            NetType.OTHER -> "Anderes Netzwerk"
            NetType.NONE -> "Kein Netzwerk"
        }
    }
}
