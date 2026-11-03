package dev.shield.core.checks

/** Status colors (SPEC §3.1). Never shown by color alone – always icon + text too. */
enum class StatusLevel { PROTECTED, LIMITED, UNPROTECTED }

/** Reasons that lead to yellow or red; mapped to texts in the UI. */
enum class StatusReason {
    TUNNEL_OFF, TUNNEL_ERROR, SELFTEST_FAILED, PANIC,
    LISTS_MISSING, LISTS_OUTDATED, VPN_DISCONNECTED, OPEN_WIFI_NO_VPN, SELFTEST_PENDING,
}

data class StatusInput(
    val tunnelRunning: Boolean,
    val tunnelError: Boolean,
    val panic: Boolean,
    /** null = no self-test since the tunnel started */
    val lastSelfTestPassed: Boolean?,
    val listsLoaded: Boolean,
    val listsAgeHours: Long,
    val wgWanted: Boolean,
    val wgConnected: Boolean,
    val openWifi: Boolean,
)

data class StatusResult(val level: StatusLevel, val reasons: List<StatusReason>)

data class FindingInput(
    val status: StatusInput,
    val screenLock: Boolean = true,
    val patchAgeDays: Long? = null,
    val adb: Boolean = false,
    val devOptions: Boolean = false,
    val installerApps: Int = 0,
    val unusualApps: Int = 0,
    val alwaysOn: Boolean = true,
    val phishingEnabled: Boolean = true,
    val killSwitch: Boolean = true,
    val dnsLeak: Boolean = false,
    val captivePortal: Boolean = false,
)

/** Pure status / findings logic (unit tested). */
object ProtectionStatus {

    fun evaluate(i: StatusInput): StatusResult {
        val red = buildList {
            if (i.tunnelError) add(StatusReason.TUNNEL_ERROR) else if (!i.tunnelRunning) add(StatusReason.TUNNEL_OFF)
            if (i.tunnelRunning && i.lastSelfTestPassed == false) add(StatusReason.SELFTEST_FAILED)
        }
        if (red.isNotEmpty()) return StatusResult(StatusLevel.UNPROTECTED, red)
        val yellow = buildList {
            if (i.panic) add(StatusReason.PANIC)
            if (!i.listsLoaded) add(StatusReason.LISTS_MISSING) else if (i.listsAgeHours >= 48) add(StatusReason.LISTS_OUTDATED)
            if (i.wgWanted && !i.wgConnected) add(StatusReason.VPN_DISCONNECTED)
            if (i.openWifi && !(i.wgWanted && i.wgConnected)) add(StatusReason.OPEN_WIFI_NO_VPN)
            if (i.lastSelfTestPassed == null) add(StatusReason.SELFTEST_PENDING)
        }
        return StatusResult(if (yellow.isEmpty()) StatusLevel.PROTECTED else StatusLevel.LIMITED, yellow)
    }

    fun findings(f: FindingInput): List<Finding> = buildList {
        val s = f.status
        if (!s.tunnelRunning) add(Finding.PROTECTION_OFF)
        if (s.tunnelRunning && s.lastSelfTestPassed == false) add(Finding.SELFTEST_FAILED)
        if (!s.listsLoaded || s.listsAgeHours >= 48) add(Finding.LISTS_OUTDATED)
        if (s.openWifi && !(s.wgWanted && s.wgConnected)) add(Finding.OPEN_WIFI_WITHOUT_VPN)
        if (!f.screenLock) add(Finding.NO_SCREEN_LOCK)
        if ((f.patchAgeDays ?: 0) > 90) add(Finding.PATCH_OUTDATED)
        if (f.adb) add(Finding.ADB_ENABLED)
        if (f.devOptions) add(Finding.DEV_OPTIONS)
        if (f.installerApps > 0) add(Finding.UNKNOWN_SOURCES)
        if (f.unusualApps > 0) add(Finding.RISKY_APPS)
        if (s.tunnelRunning && !f.alwaysOn) add(Finding.ALWAYS_ON_OFF)
        if (!f.phishingEnabled) add(Finding.PHISHING_OFF)
        if (s.wgWanted && !f.killSwitch) add(Finding.KILL_SWITCH_OFF)
        if (f.dnsLeak) add(Finding.DNS_LEAK)
        if (f.captivePortal) add(Finding.CAPTIVE_PORTAL)
    }
}
