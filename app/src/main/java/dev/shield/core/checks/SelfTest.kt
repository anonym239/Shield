package dev.shield.core.checks

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.filter.FilterEngine
import dev.shield.core.vpn.ShieldVpnService
import dev.shield.core.vpn.VpnController
import dev.shield.data.repo.QueryRecorder
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

enum class TestId { TUNNEL, SYSTEM_DNS, TEST_DOMAIN, UPSTREAM, LISTS, VPN }

/** passed = null: not applicable (e.g. VPN test without WireGuard profile). */
data class TestResult(val id: TestId, val passed: Boolean?, val detail: String)

data class SelfTestReport(val time: Long, val results: List<TestResult>) {
    val applicable get() = results.filter { it.passed != null }
    val passedCount get() = applicable.count { it.passed == true }
    val allPassed get() = applicable.isNotEmpty() && applicable.all { it.passed == true }

    fun toJson(): String = JSONObject().put("time", time).put(
        "results",
        JSONArray().apply {
            results.forEach { r ->
                put(JSONObject().put("id", r.id.name).put("passed", r.passed ?: JSONObject.NULL).put("detail", r.detail))
            }
        },
    ).toString()

    companion object {
        fun fromJson(s: String?): SelfTestReport? = s?.let {
            runCatching {
                val o = JSONObject(it)
                val arr = o.getJSONArray("results")
                SelfTestReport(
                    o.getLong("time"),
                    (0 until arr.length()).map { i ->
                        val r = arr.getJSONObject(i)
                        TestResult(
                            TestId.valueOf(r.getString("id")),
                            if (r.isNull("passed")) null else r.getBoolean("passed"),
                            r.optString("detail"),
                        )
                    },
                )
            }.getOrNull()
        }
    }
}

/** Live self-test (SPEC §6). Every test produces a security_event. */
@Singleton
class SelfTestRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: VpnController,
    private val blocklists: BlocklistRepository,
    private val recorder: QueryRecorder,
    private val settings: SettingsRepository,
    private val leakTest: LeakTest,
    private val events: EventLogger,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** @param full also runs the online leak checks of test 6 (only on user request). */
    suspend fun run(full: Boolean): SelfTestReport = mutex.withLock {
        _running.value = true
        try {
            val results = listOf(
                tunnel(), systemDns(), testDomain(), upstream(), lists(), vpn(full),
            )
            results.forEach { r ->
                if (r.passed != null) {
                    events.record(
                        if (r.passed) EventType.SELFTEST_PASSED else EventType.SELFTEST_FAILED,
                        if (r.passed) dev.shield.logging.Severity.INFO else dev.shield.logging.Severity.WARNING,
                        "Test ${r.id.name}: ${r.detail}",
                    )
                }
            }
            val report = SelfTestReport(System.currentTimeMillis(), results)
            settings.setSelfTestJson(report.toJson())
            if (report.allPassed) {
                events.info(EventType.SELFTEST_PASSED, "Selbsttest bestanden (${report.passedCount}/${report.applicable.size})")
            } else {
                events.warn(EventType.SELFTEST_FAILED, "Selbsttest: ${report.passedCount}/${report.applicable.size} bestanden")
            }
            report
        } finally {
            _running.value = false
        }
    }

    private fun tunnel(): TestResult {
        val st = controller.state.value
        val ok = st.running && controller.tunnelInterfaceUp()
        return TestResult(TestId.TUNNEL, ok, if (ok) "VpnService läuft, TUN aktiv" else "Tunnel ist aus")
    }

    private fun systemDns(): TestResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val vpn = cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            ?: return TestResult(TestId.SYSTEM_DNS, false, "Kein VPN-Netzwerk")
        val lp = cm.getLinkProperties(vpn) ?: return TestResult(TestId.SYSTEM_DNS, false, "Keine Netzwerkdaten")
        val servers = lp.dnsServers.mapNotNull { it.hostAddress }
        val virtual = servers.any { it == ShieldVpnService.DNS4 || it.equals(ShieldVpnService.DNS6, true) }
        if (lp.isPrivateDnsActive && lp.privateDnsServerName != null) {
            return TestResult(TestId.SYSTEM_DNS, false, "„Privates DNS“ (${lp.privateDnsServerName}) umgeht den Filter")
        }
        return TestResult(TestId.SYSTEM_DNS, virtual, if (virtual) "System-DNS: ${servers.joinToString()}" else "System-DNS zeigt nicht auf Shield")
    }

    private suspend fun testDomain(): TestResult = withContext(Dispatchers.IO) {
        if (!controller.state.value.running) return@withContext TestResult(TestId.TEST_DOMAIN, false, "Tunnel ist aus")
        val start = System.currentTimeMillis()
        val name = "t${System.nanoTime() % 1_000_000}.${FilterEngine.SELFTEST_DOMAIN}"
        val addrs: List<InetAddress> = withTimeoutOrNull(5000) {
            try {
                InetAddress.getAllByName(name).toList()
            } catch (e: UnknownHostException) {
                emptyList()
            }
        } ?: emptyList()
        recorder.flush()
        val seen = recorder.lastSelfTestHit.get() >= start
        val blockedAnswer = addrs.isEmpty() || addrs.all { it.isAnyLocalAddress || (it is Inet4Address && it.hostAddress == "0.0.0.0") }
        val ok = seen && blockedAnswer
        TestResult(TestId.TEST_DOMAIN, ok, if (ok) "Test-Domain wurde blockiert" else "Test-Domain wurde nicht vom Filter beantwortet")
    }

    private suspend fun upstream(): TestResult {
        val r = controller.probeUpstream()
        return r.fold(
            { ms -> TestResult(TestId.UPSTREAM, true, "DoH erreichbar · $ms ms") },
            { e -> TestResult(TestId.UPSTREAM, false, "DoH nicht erreichbar: ${e.message}") },
        )
    }

    private fun lists(): TestResult {
        val s = blocklists.state.value
        val ageH = if (s.oldestUpdate > 0) (System.currentTimeMillis() - s.oldestUpdate) / 3_600_000 else -1
        val ok = s.totalRules > 0 && ageH in 0..47
        val detail = when {
            s.totalRules == 0 -> "Keine Blocklisten geladen"
            !ok -> "Listen veraltet (${ageH} h)"
            else -> "%,d Regeln · %d h alt".format(s.totalRules, ageH)
        }
        return TestResult(TestId.LISTS, ok, detail)
    }

    private suspend fun vpn(full: Boolean): TestResult {
        val st = controller.state.value
        if (!st.wgWanted) return TestResult(TestId.VPN, null, "Kein VPN-Profil aktiv")
        if (!st.wgConnected || st.handshakeAgeSec !in 0..180) {
            return TestResult(TestId.VPN, false, "Kein WireGuard-Handshake in den letzten 3 Min")
        }
        if (!full) return TestResult(TestId.VPN, true, "Handshake vor ${st.handshakeAgeSec} s")
        return try {
            val r = leakTest.run()
            val homeIp = settings.current().homeIp
            val problems = buildList {
                if (homeIp.isNotEmpty() && r.ipv4 == homeIp) add("öffentliche IP = Heim-IP")
                if (r.dnsLeak) add("DNS-Leak")
                if (r.ipv6Leak) add("IPv6-Leak")
            }
            TestResult(
                TestId.VPN, problems.isEmpty(),
                if (problems.isEmpty()) "Handshake ok · IP ${r.ipv4 ?: "?"} (${r.country ?: "?"}) · kein Leak" else problems.joinToString(),
            )
        } catch (e: Exception) {
            ShieldLog.w("SelfTest", "leak test failed", e)
            TestResult(TestId.VPN, false, "Leak-Test fehlgeschlagen: ${e.message}")
        }
    }
}
