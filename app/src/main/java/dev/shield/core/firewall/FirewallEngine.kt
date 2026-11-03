package dev.shield.core.firewall

import dev.shield.core.dns.DnsAppPolicy
import dev.shield.core.vpn.NetworkMonitor
import dev.shield.data.Settings
import dev.shield.data.repo.AppRepository
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the per-app rules. Stage A uses it for DNS (blocked apps get NXDOMAIN),
 * stage B additionally for every packet flow.
 */
@Singleton
class FirewallEngine @Inject constructor(
    private val network: NetworkMonitor,
    private val apps: AppRepository,
) : DnsAppPolicy {

    @Volatile
    private var byUid: Map<Int, AppRule> = emptyMap()

    @Volatile
    private var settings: Settings = Settings()

    fun update(rules: Collection<AppRule>, s: Settings) {
        // Rules are stored per package; resolve the current uid (it can change after reinstall).
        byUid = rules.associateBy { r -> apps.uidOf(r.packageName) ?: r.uid }
        settings = s
    }

    val blockedCount: Int get() = byUid.values.count { it.policy == AppPolicy.BLOCK }

    private fun context(): FirewallContext {
        val now = LocalTime.now()
        val s = settings
        return FirewallContext(
            netType = network.state.value.type,
            minuteOfDay = now.hour * 60 + now.minute,
            nightEnabled = s.nightModeEnabled,
            nightStart = s.nightStartMinutes,
            nightEnd = s.nightEndMinutes,
            dataSaverEnabled = s.dataSaverEnabled,
        )
    }

    fun cause(uid: Int): BlockCause? {
        if (uid < 0 || uid == apps.ownUid) return null
        if (uid < FIRST_APP_UID && byUid[uid] == null) return null // system daemons
        return FirewallRules.blockCause(byUid[uid], context())
    }

    override fun blockReason(uid: Int): String? = cause(uid)?.let(FirewallRules::causeText)

    fun allowFlow(uid: Int): Boolean = cause(uid) == null

    companion object {
        const val FIRST_APP_UID = 10000
    }
}
