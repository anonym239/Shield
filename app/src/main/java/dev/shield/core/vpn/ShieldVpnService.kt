package dev.shield.core.vpn

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.core.dns.DnsCache
import dev.shield.core.dns.DnsCodec
import dev.shield.core.dns.DnsResolver
import dev.shield.core.dns.DohClient
import dev.shield.core.firewall.FirewallEngine
import dev.shield.core.filter.FilterEngine
import dev.shield.core.wireguard.WgConfig
import dev.shield.core.wireguard.WgConfigParser
import dev.shield.core.wireguard.WgProfileRepository
import dev.shield.data.BlockResponse
import dev.shield.data.DohUpstream
import dev.shield.data.ProtectionLevel
import dev.shield.data.Settings
import dev.shield.data.TunnelMode
import dev.shield.data.repo.QueryRecorder
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import dev.shield.ui.common.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject

/**
 * The single VpnService of the app (Android allows only one active VPN app): DNS blocker,
 * firewall and WireGuard all run through it (SPEC §1, §5.2).
 */
@AndroidEntryPoint
class ShieldVpnService : VpnService() {

    @Inject lateinit var controller: VpnController
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var filter: FilterEngine
    @Inject lateinit var cache: DnsCache
    @Inject lateinit var recorder: QueryRecorder
    @Inject lateinit var firewall: FirewallEngine
    @Inject lateinit var network: NetworkMonitor
    @Inject lateinit var profiles: WgProfileRepository
    @Inject lateinit var events: EventLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private lateinit var cm: ConnectivityManager
    private lateinit var resolver: DnsResolver

    @Volatile private var settings = Settings()
    @Volatile private var tunnel: RunningTunnel? = null
    @Volatile private var goTunnel: shieldcore.Tunnel? = null
    @Volatile private var doh: DohClient? = null
    @Volatile private var spec: Spec? = null
    @Volatile private var userStopped = false
    private var monitorJob: Job? = null

    private enum class Kind { DNS_ONLY, FULL, PANIC }

    private data class Spec(
        val kind: Kind,
        val dohUrl: String,
        val bootstrap: List<String>,
        val wgProfileId: String?,
        val blockDnsBypass: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        resolver = DnsResolver(filter, cache, recorder, firewall, { settings.blockResponse == BlockResponse.ZERO_IP })
        controller.service = this
        network.start()
        observe()
    }

    @OptIn(FlowPreview::class)
    private fun observe() {
        scope.launch {
            combine(settingsRepo.settings, network.state) { s, n -> s to n }
                .debounce(400)
                .collect { (s, _) ->
                    settings = s
                    if (s.protectionEnabled && !userStopped) reconfigure(force = false)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopByUser()
                return START_NOT_STICKY
            }
            else -> {
                // START, RESTART or a start by the system (always-on VPN / boot)
                userStopped = false
                goForeground(Notifications.statusNotification(this, controller.state.value))
                scope.launch {
                    if (intent == null || intent.action == ACTION_ALWAYS_ON) {
                        settingsRepo.update { it.copy(protectionEnabled = true) }
                    }
                    settings = settingsRepo.current()
                    reconfigure(force = intent?.action == ACTION_RESTART)
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        ServiceCompat.startForeground(
            this, Notifications.STATUS_ID, n,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
    }

    fun requestReconfigure(force: Boolean) {
        scope.launch {
            settings = settingsRepo.current()
            reconfigure(force)
        }
    }

    fun stopByUser() {
        userStopped = true
        scope.launch {
            mutex.withLock { teardown() }
            controller.mutableState.value = TunnelState()
            ServiceCompat.stopForeground(this@ShieldVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun tunnelUp(): Boolean = tunnel != null || goTunnel != null

    private suspend fun desiredSpec(s: Settings): Spec {
        val net = network.state.value
        val auto = AutoVpnPolicy.wantsVpn(
            AutoVpnRules(s.autoVpnEnabled, s.homeSsids, s.vpnOnOpenWifi, s.vpnOnUnknownWifi, s.vpnOnMobile),
            net,
        )
        val wgWanted = s.activeProfileId != null && (auto ?: true)
        val profileId = if (wgWanted) s.activeProfileId else null
        val kind = when {
            s.panic -> Kind.PANIC
            profileId != null || s.effectiveTunnelMode == TunnelMode.FULL -> Kind.FULL
            else -> Kind.DNS_ONLY
        }
        var bootstrap = s.dohBootstrap
        if (bootstrap.isEmpty() && s.upstream == DohUpstream.CUSTOM) {
            bootstrap = resolveOutside(hostOf(s.dohUrl))
        }
        return Spec(kind, s.dohUrl, bootstrap, profileId, s.protectionLevel == ProtectionLevel.MAXIMUM)
    }

    private suspend fun reconfigure(force: Boolean) = mutex.withLock {
        val s = settings
        if (!s.protectionEnabled || userStopped) return@withLock
        val want = try {
            desiredSpec(s)
        } catch (e: Exception) {
            fail("Konfiguration ungültig: ${e.message}", e)
            return@withLock
        }
        val current = spec
        if (!force && current == want && tunnelUp()) {
            goTunnel?.setKillSwitch(s.killSwitch)
            publish { it.copy(killSwitch = s.killSwitch && want.wgProfileId != null) }
            return@withLock
        }
        teardown()
        controller.mutableState.value = controller.state.value.copy(phase = TunnelPhase.STARTING, error = null)
        try {
            when (want.kind) {
                Kind.DNS_ONLY -> startDnsOnly(want)
                Kind.FULL -> startFull(want, s)
                Kind.PANIC -> startPanic()
            }
            spec = want
            startMonitor()
            Notifications.cancelAlert(this)
        } catch (e: Exception) {
            teardown()
            fail(e.message ?: e.javaClass.simpleName, e)
        }
        updateNotification()
    }

    private fun baseBuilder(): Builder = Builder()
        .setSession("Shield")
        .setMtu(MTU)
        .setBlocking(true)
        .also { b ->
            b.setMetered(false) // meteredness follows the underlying network
            b.setConfigureIntent(Notifications.openAppIntent(this))
        }

    private fun startDnsOnly(sp: Spec) {
        val b = baseBuilder()
            .addAddress(TUN4, 32)
            .addRoute(DNS4, 32)
            .addDnsServer(DNS4)
            .addAddress(TUN6, 128)
            .addRoute(DNS6, 128)
            .addDnsServer(DNS6)
        val pfd = b.establish() ?: throw IllegalStateException("VPN-Berechtigung fehlt")
        val client = DohClient(sp.dohUrl, sp.bootstrap) { s -> protect(s) }
        doh = client
        tunnel = DnsOnlyTunnel(pfd, resolver, client, { pkt ->
            try {
                cm.getConnectionOwnerUid(
                    OsConstants.IPPROTO_UDP,
                    InetSocketAddress(pkt.srcAddress, pkt.srcPort),
                    InetSocketAddress(pkt.dstAddress, pkt.dstPort),
                )
            } catch (e: Exception) {
                -1
            }
        }, { e -> scope.launch { fail("Tunnel-Fehler: ${e.message}", e) } }).start()
        publishRunning(TunnelMode.DNS_ONLY, sp, null, listOf("$TUN4/32", "$TUN6/128"), listOf("$DNS4/32", "$DNS6/128"))
        events.info(EventType.PROTECTION_ON, "Tunnel aktiv (nur DNS) · ${settings.dohLabel}")
    }

    private suspend fun startFull(sp: Spec, s: Settings) {
        var wgCfg: WgConfig? = null
        var uapi = ""
        var wgName: String? = null
        if (sp.wgProfileId != null) {
            wgCfg = profiles.config(sp.wgProfileId) ?: throw IllegalStateException("WireGuard-Profil nicht gefunden")
            wgName = profiles.get(sp.wgProfileId)?.name
            val resolved = wgCfg.peers.mapNotNull { it.endpoint }.associateWith { ep ->
                val (host, port) = WgConfigParser.splitHostPort(ep) ?: throw IllegalStateException("Ungültiger Endpoint")
                val ip = DohClient.literal(host)?.hostAddress ?: resolveOutside(host).firstOrNull()
                    ?: throw IllegalStateException("Endpoint $host nicht auflösbar")
                WgConfigParser.joinHostPort(ip, port)
            }
            uapi = wgCfg.toUapi(resolved)
        }
        val b = baseBuilder()
            .addAddress(TUN4, 24)
            .addAddress(TUN6, 64)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(DNS4)
            .addDnsServer(DNS6)
        val pfd = b.establish() ?: throw IllegalStateException("VPN-Berechtigung fehlt")
        val cfg = shieldcore.Shieldcore.newConfig().apply {
            setFd(pfd.fd)
            setMtu(MTU)
            setDohURL(sp.dohUrl)
            setDohBootstrap(sp.bootstrap.joinToString(","))
            setWgUapi(uapi)
            setWgAddresses(wgCfg?.iface?.addresses?.joinToString(",") ?: "")
            setWgMtu(wgCfg?.iface?.mtu ?: 1280)
            setKillSwitch(s.killSwitch)
            setBlockDnsBypass(sp.blockDnsBypass)
            setVirtualDNS4(DNS4)
            setVirtualDNS6(DNS6)
        }
        val t = try {
            withContext(Dispatchers.IO) { shieldcore.Shieldcore.start(cfg, GoBridge(this@ShieldVpnService, cm, resolver, firewall)) }
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw IllegalStateException("Netzwerk-Kern: ${e.message}", e)
        }
        goTunnel = t
        tunnel = object : RunningTunnel {
            override fun close() {
                runCatching { t.close() }
                runCatching { pfd.close() }
            }
        }
        publishRunning(TunnelMode.FULL, sp, wgName, listOf("$TUN4/24", "$TUN6/64"), listOf("0.0.0.0/0", "::/0"))
        events.info(
            EventType.PROTECTION_ON,
            if (wgName != null) "Voller Tunnel mit WireGuard ($wgName)" else "Voller Tunnel (Firewall aktiv)",
        )
    }

    private fun startPanic() {
        val pfd = baseBuilder()
            .addAddress(TUN4, 24)
            .addAddress(TUN6, 64)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(DNS4)
            .establish() ?: throw IllegalStateException("VPN-Berechtigung fehlt")
        tunnel = BlackholeTunnel(pfd).start()
        publish {
            TunnelState(
                phase = TunnelPhase.RUNNING, mode = TunnelMode.FULL, startedAt = System.currentTimeMillis(),
                panic = true, addresses = listOf("$TUN4/24"), routes = listOf("0.0.0.0/0", "::/0"), mtu = MTU,
            )
        }
    }

    private fun publishRunning(mode: TunnelMode, sp: Spec, wgName: String?, addrs: List<String>, routes: List<String>) {
        val prev = controller.state.value
        publish {
            TunnelState(
                phase = TunnelPhase.RUNNING,
                mode = mode,
                startedAt = if (prev.running && prev.mode == mode) prev.startedAt else System.currentTimeMillis(),
                wgProfileName = wgName,
                wgWanted = sp.wgProfileId != null,
                killSwitch = settings.killSwitch && sp.wgProfileId != null,
                addresses = addrs,
                routes = routes,
                dnsServers = listOf(DNS4, DNS6),
                mtu = MTU,
                upstream = settings.dohLabel,
                alwaysOn = isAlwaysOn,
                lockdown = isLockdownEnabled,
            )
        }
    }

    private inline fun publish(f: (TunnelState) -> TunnelState) {
        controller.mutableState.value = f(controller.state.value)
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var lastConnected: Boolean? = null
            var lastBlocked = 0L
            var lastKillEvent = 0L
            while (isActive) {
                val g = goTunnel
                if (g != null) {
                    val stats = runCatching { g.stats() }.getOrDefault("")
                    val age = runCatching { g.handshakeAgeSeconds() }.getOrDefault(-1)
                    val connected = runCatching { g.wireGuardConnected() }.getOrDefault(false)
                    val st = controller.state.value
                    if (st.wgWanted) {
                        if (lastConnected != connected) {
                            if (connected) {
                                events.info(EventType.VPN_CONNECTED, "WireGuard verbunden (${st.wgProfileName})")
                            } else if (lastConnected == true) {
                                events.warn(EventType.VPN_DISCONNECTED, "WireGuard getrennt (${st.wgProfileName})")
                            }
                            lastConnected = connected
                            updateNotification()
                        }
                        val blocked = Regex("\"flows_blocked\":(\\d+)").find(stats)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        if (!connected && st.killSwitch && blocked > lastBlocked && System.currentTimeMillis() - lastKillEvent > 60_000) {
                            events.warn(EventType.KILL_SWITCH_BLOCKED, "Kill-Switch hat Verbindungen blockiert (VPN nicht verbunden)")
                            lastKillEvent = System.currentTimeMillis()
                        }
                        lastBlocked = blocked
                    }
                    publish {
                        it.copy(
                            wgConnected = connected,
                            wgSince = if (connected && !it.wgConnected) System.currentTimeMillis() else if (connected) it.wgSince else 0,
                            handshakeAgeSec = age,
                            coreStats = stats,
                            alwaysOn = isAlwaysOn,
                            lockdown = isLockdownEnabled,
                        )
                    }
                } else {
                    publish { it.copy(alwaysOn = isAlwaysOn, lockdown = isLockdownEnabled) }
                }
                delay(5_000)
            }
        }
    }

    /** Self-test 4. */
    suspend fun probeUpstream(): Result<Long> = runCatching {
        val q = DnsCodec.buildQuery((0..0xFFFF).random(), PROBE_DOMAIN, DnsCodec.TYPE_A)
        val start = System.currentTimeMillis()
        val answer = goTunnel?.let { withContext(Dispatchers.IO) { it.probeDns(q) } }
            ?: doh?.resolve(q)
            ?: throw IllegalStateException("Kein Upstream aktiv")
        if (DnsCodec.rcode(answer) != DnsCodec.RCODE_NOERROR) throw IllegalStateException("Upstream-Antwort ${DnsCodec.rcode(answer)}")
        System.currentTimeMillis() - start
    }

    private fun teardown() {
        monitorJob?.cancel()
        monitorJob = null
        tunnel?.close()
        tunnel = null
        goTunnel = null
        doh?.shutdown()
        doh = null
        spec = null
        cache.clear()
    }

    private fun fail(message: String, e: Throwable?) {
        ShieldLog.e("Vpn", message, e)
        events.critical(EventType.TUNNEL_ERROR, message)
        publish { TunnelState(phase = TunnelPhase.ERROR, error = message) }
        Notifications.alert(this, getString(dev.shield.R.string.alert_unprotected_title), message)
        updateNotification()
    }

    private fun updateNotification() {
        Notifications.updateStatus(this, controller.state.value)
    }

    /** Resolves a host name via the physical network (bypassing the VPN). */
    private suspend fun resolveOutside(host: String): List<String> = withContext(Dispatchers.IO) {
        if (host.isEmpty()) return@withContext emptyList()
        val n = network.currentNetwork
        val addrs: Array<InetAddress> = try {
            n?.getAllByName(host) ?: InetAddress.getAllByName(host)
        } catch (e: Exception) {
            ShieldLog.w("Vpn", "cannot resolve $host", e)
            emptyArray()
        }
        addrs.mapNotNull { it.hostAddress }
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")

    override fun onRevoke() {
        events.critical(EventType.TUNNEL_REVOKED, "VPN wurde vom System oder einer anderen VPN-App beendet")
        Notifications.alert(this, getString(dev.shield.R.string.alert_unprotected_title), getString(dev.shield.R.string.alert_revoked))
        userStopped = true
        runBlocking { settingsRepo.update { it.copy(protectionEnabled = false) } }
        teardown()
        controller.mutableState.value = TunnelState()
        stopSelf()
    }

    override fun onDestroy() {
        if (!userStopped && settings.protectionEnabled) {
            events.critical(EventType.SERVICE_CRASH, "Schutzdienst wurde unerwartet beendet")
            Notifications.alert(this, getString(dev.shield.R.string.alert_unprotected_title), getString(dev.shield.R.string.alert_service_died))
        }
        teardown()
        controller.mutableState.value = TunnelState()
        controller.service = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.shield.START"
        const val ACTION_STOP = "dev.shield.STOP"
        const val ACTION_RESTART = "dev.shield.RESTART"
        const val ACTION_ALWAYS_ON = "android.net.VpnService"
        const val TUN4 = "10.111.222.1"
        const val DNS4 = "10.111.222.3"
        const val TUN6 = "fd00:5:1::1"
        const val DNS6 = "fd00:5:1::3"
        const val MTU = 1500
        const val PROBE_DOMAIN = "example.com"
    }
}
