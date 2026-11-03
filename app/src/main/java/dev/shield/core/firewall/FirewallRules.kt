package dev.shield.core.firewall

/** Per-app internet policy (SPEC §2.2). */
enum class AppPolicy { ALLOW, WIFI_ONLY, MOBILE_ONLY, BLOCK }

/** Current network type. */
enum class NetType { WIFI, MOBILE, OTHER, NONE }

data class AppRule(
    val packageName: String,
    val uid: Int,
    val policy: AppPolicy = AppPolicy.ALLOW,
    val nightAllowed: Boolean = false,
    val dataSaver: Boolean = false,
)

data class FirewallContext(
    val netType: NetType,
    val minuteOfDay: Int,
    val nightEnabled: Boolean = false,
    val nightStart: Int = 23 * 60,
    val nightEnd: Int = 6 * 60,
    val dataSaverEnabled: Boolean = false,
)

/** Why the firewall blocks an app. */
enum class BlockCause { RULE_BLOCK, RULE_WIFI_ONLY, RULE_MOBILE_ONLY, NIGHT_MODE, DATA_SAVER }

/** Pure firewall decision logic (unit tested). */
object FirewallRules {

    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end // crosses midnight
    }

    /** Returns the cause if the app must be blocked, null if allowed. */
    fun blockCause(rule: AppRule?, ctx: FirewallContext): BlockCause? {
        val policy = rule?.policy ?: AppPolicy.ALLOW
        when (policy) {
            AppPolicy.BLOCK -> return BlockCause.RULE_BLOCK
            AppPolicy.WIFI_ONLY -> if (ctx.netType == NetType.MOBILE) return BlockCause.RULE_WIFI_ONLY
            AppPolicy.MOBILE_ONLY -> if (ctx.netType == NetType.WIFI) return BlockCause.RULE_MOBILE_ONLY
            AppPolicy.ALLOW -> Unit
        }
        if (ctx.nightEnabled && inWindow(ctx.minuteOfDay, ctx.nightStart, ctx.nightEnd) && rule?.nightAllowed != true) {
            return BlockCause.NIGHT_MODE
        }
        if (ctx.dataSaverEnabled && ctx.netType == NetType.MOBILE && rule?.dataSaver == true) {
            return BlockCause.DATA_SAVER
        }
        return null
    }

    fun causeText(c: BlockCause): String = when (c) {
        BlockCause.RULE_BLOCK -> "Firewall: App gesperrt"
        BlockCause.RULE_WIFI_ONLY -> "Firewall: nur WLAN"
        BlockCause.RULE_MOBILE_ONLY -> "Firewall: nur Mobilfunk"
        BlockCause.NIGHT_MODE -> "Nachtmodus"
        BlockCause.DATA_SAVER -> "Datenspar-Modus"
    }
}
