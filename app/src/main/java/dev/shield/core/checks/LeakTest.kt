package dev.shield.core.checks

import dev.shield.core.vpn.VpnController
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

data class LeakResult(
    val ipv4: String?,
    val country: String?,
    val ipv6: String?,
    val resolvers: List<String>,
    val dnsLeak: Boolean,
    val ipv6Leak: Boolean,
    val homeIpMatch: Boolean,
)

/**
 * Leak test (SPEC §2.3): public IP + country, DNS leak (which resolvers see our queries) and IPv6 leak.
 * Online check – only on explicit user request, every access is logged as an event.
 */
@Singleton
class LeakTest @Inject constructor(
    private val http: OkHttpClient,
    private val controller: VpnController,
    private val settings: SettingsRepository,
    private val events: EventLogger,
) {
    private fun get(url: String): String? {
        events.info(EventType.NETWORK_ACCESS, "Leak-Test: $url")
        return runCatching {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    suspend fun run(): LeakResult = withContext(Dispatchers.IO) {
        val v4 = get("https://ipv4.am.i.mullvad.net/json")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val v6 = get("https://ipv6.am.i.mullvad.net/json")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val ip4 = v4?.optString("ip")?.takeIf { it.isNotEmpty() }
        val ip6 = v6?.optString("ip")?.takeIf { it.isNotEmpty() }

        // DNS leak test via bash.ws: resolve unique names, then ask which resolvers saw them.
        val resolvers = mutableListOf<String>()
        var dnsLeak = false
        val id = get("https://bash.ws/id")?.trim()?.takeIf { it.all { c -> c.isLetterOrDigit() } }
        if (id != null) {
            coroutineScope {
                (1..6).map { i -> async { runCatching { InetAddress.getAllByName("$i.$id.bash.ws") } } }.awaitAll()
            }
            val arr = get("https://bash.ws/dnsleak/test/$id?json")?.let { runCatching { JSONArray(it) }.getOrNull() }
            var ipAsn: String? = null
            val dnsAsns = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    when (o.optString("type")) {
                        "ip" -> ipAsn = o.optString("asn")
                        "dns" -> {
                            resolvers += "${o.optString("ip")} (${o.optString("asn")}, ${o.optString("country_name")})"
                            dnsAsns += o.optString("asn")
                        }
                    }
                }
            }
            // A resolver in the same network as our public IP is the ISP resolver: plaintext DNS leaked.
            dnsLeak = ipAsn != null && ipAsn.isNotEmpty() && dnsAsns.any { it == ipAsn }
        }

        val st = controller.state.value
        val home = settings.current().homeIp.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val vpnActive = st.wgConnected
        if (!vpnActive) {
            // remember the IPs without VPN as "home IP" (only stored locally)
            val ips = listOfNotNull(ip4, ip6).joinToString(",")
            if (ips.isNotEmpty()) settings.update { it.copy(homeIp = ips) }
        }
        val homeMatch = vpnActive && (ip4 != null && ip4 in home)
        val ipv6Leak = vpnActive && ip6 != null && ip6 in home
        val result = LeakResult(ip4, v4?.optString("country"), ip6, resolvers, dnsLeak, ipv6Leak, homeMatch)
        val problems = listOfNotNull(
            if (dnsLeak) "DNS-Leak" else null,
            if (ipv6Leak) "IPv6-Leak" else null,
            if (homeMatch) "Heim-IP sichtbar" else null,
        )
        if (problems.isEmpty()) {
            events.info(EventType.LEAK_TEST, "Leak-Test ok · IP ${ip4 ?: "-"} (${result.country ?: "-"})")
        } else {
            events.warn(EventType.LEAK_TEST, "Leak-Test: ${problems.joinToString()}")
        }
        result
    }
}
