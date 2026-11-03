package dev.shield.ui.common

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.shield.R
import dev.shield.core.checks.Finding

/** Localized recommendation text + optional system settings action for a finding. */
object Recommendations {
    fun text(f: Finding): Int = when (f) {
        Finding.PROTECTION_OFF -> R.string.rec_protection_off
        Finding.SELFTEST_FAILED -> R.string.rec_selftest_failed
        Finding.NO_SCREEN_LOCK -> R.string.rec_screen_lock
        Finding.OPEN_WIFI_WITHOUT_VPN -> R.string.rec_open_wifi
        Finding.DNS_LEAK -> R.string.rec_dns_leak
        Finding.PATCH_OUTDATED -> R.string.rec_patch
        Finding.LISTS_OUTDATED -> R.string.rec_lists
        Finding.ADB_ENABLED -> R.string.rec_adb
        Finding.RISKY_APPS -> R.string.rec_risky_apps
        Finding.ALWAYS_ON_OFF -> R.string.rec_always_on
        Finding.PHISHING_OFF -> R.string.rec_phishing
        Finding.DEV_OPTIONS -> R.string.rec_dev_options
        Finding.UNKNOWN_SOURCES -> R.string.rec_unknown_sources
        Finding.KILL_SWITCH_OFF -> R.string.rec_kill_switch
        Finding.CAPTIVE_PORTAL -> R.string.rec_captive
    }

    /** System screen that fixes the finding, if any. */
    fun systemIntent(f: Finding): Intent? = when (f) {
        Finding.ALWAYS_ON_OFF -> Intent(Settings.ACTION_VPN_SETTINGS)
        Finding.NO_SCREEN_LOCK -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        Finding.ADB_ENABLED, Finding.DEV_OPTIONS -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        Finding.PATCH_OUTDATED -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        Finding.UNKNOWN_SOURCES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        else -> null
    }

    fun open(ctx: Context, intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
