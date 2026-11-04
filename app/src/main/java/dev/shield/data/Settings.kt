package dev.shield.data

/** Tunnel stage (SPEC §5.2). */
enum class TunnelMode { DNS_ONLY, FULL }

/**
 * Protection level presets. STANDARD blocks ads/trackers/malware/phishing without breaking apps,
 * STRICT additionally blocks app telemetry (Firebase, Crashlytics, analytics SDKs), MAXIMUM adds
 * large threat feeds, DNS-bypass protection and forces the full tunnel with kill switch.
 */
enum class ProtectionLevel(val lists: Set<String>, val aggressiveEasyPrivacy: Boolean) {
    STANDARD(setOf("adguard_dns", "easyprivacy", "urlhaus", "phishing_army"), false),
    STRICT(setOf("adguard_dns", "easyprivacy", "urlhaus", "phishing_army", "builtin_telemetry", "hagezi_pro"), true),
    MAXIMUM(
        setOf(
            "adguard_dns", "easyprivacy", "urlhaus", "phishing_army", "phishing_filter", "builtin_telemetry",
            "hagezi_pro_plus", "hagezi_tif", "hagezi_doh", "hagezi_native_samsung", "hagezi_native_xiaomi",
        ),
        true,
    ),
    ;

    /** Settings implied by the level (lists, tunnel, kill switch). */
    fun applyTo(s: Settings): Settings = s.copy(
        protectionLevel = this,
        enabledLists = lists,
        tunnelMode = if (this == MAXIMUM) TunnelMode.FULL else s.tunnelMode,
        killSwitch = if (this == MAXIMUM) true else s.killSwitch,
    )
}

/** How blocked domains are answered. */
enum class BlockResponse { ZERO_IP, NXDOMAIN }

/** Built-in DNS-over-HTTPS upstreams with bootstrap IPs (no plaintext DNS lookup needed). */
enum class DohUpstream(val label: String, val url: String, val bootstrap: List<String>) {
    QUAD9("Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe")),
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")),
    MULLVAD("Mullvad", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2", "2a07:e340::2")),
    ADGUARD("AdGuard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
    CUSTOM("Eigener", "", emptyList()),
}

data class Settings(
    val protectionEnabled: Boolean = false,
    val protectionLevel: ProtectionLevel = ProtectionLevel.STANDARD,
    val tunnelMode: TunnelMode = TunnelMode.DNS_ONLY,
    val upstream: DohUpstream = DohUpstream.QUAD9,
    val customDohUrl: String = "",
    val customDohBootstrap: String = "",
    val blockResponse: BlockResponse = BlockResponse.ZERO_IP,
    val enabledLists: Set<String> = DEFAULT_LISTS,
    val listsAutoUpdate: Boolean = true,
    val listsWifiOnly: Boolean = true,
    val retentionQueriesDays: Int = 7,
    val retentionStatsDays: Int = 90,
    val retentionEventsDays: Int = 30,
    val noHistory: Boolean = false,
    val activeProfileId: String? = null,
    val killSwitch: Boolean = true,
    val autoVpnEnabled: Boolean = false,
    val homeSsids: Set<String> = emptySet(),
    val vpnOnOpenWifi: Boolean = true,
    val vpnOnUnknownWifi: Boolean = true,
    val vpnOnMobile: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val nightStartMinutes: Int = 23 * 60,
    val nightEndMinutes: Int = 6 * 60,
    val dataSaverEnabled: Boolean = false,
    val weeklyReportEnabled: Boolean = false,
    val homeIp: String = "",
    val dynamicColor: Boolean = false,
    val panic: Boolean = false,
    /** No pop-up notifications (alerts, weekly report); the mandatory VPN notice is minimised. */
    val quietMode: Boolean = false,
    /** Restart tunnel / reload lists automatically when the self-test fails. */
    val autoHeal: Boolean = true,
    /** Daily AI health check with the user's own Gemini API key (opt-in). */
    val aiEnabled: Boolean = false,
    val aiModel: String = DEFAULT_AI_MODEL,
) {
    val dohUrl: String get() = if (upstream == DohUpstream.CUSTOM) customDohUrl else upstream.url
    val dohBootstrap: List<String>
        get() = if (upstream == DohUpstream.CUSTOM) {
            customDohBootstrap.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            upstream.bootstrap
        }
    /** MAXIMUM always uses the full tunnel (packet firewall, DoT blocking). */
    val effectiveTunnelMode: TunnelMode get() = if (protectionLevel == ProtectionLevel.MAXIMUM) TunnelMode.FULL else tunnelMode

    val dohLabel: String get() = if (upstream == DohUpstream.CUSTOM) customDohUrl else upstream.label

    companion object {
        val DEFAULT_LISTS = ProtectionLevel.STANDARD.lists
        const val DEFAULT_AI_MODEL = "gemini-flash-latest"
    }
}
