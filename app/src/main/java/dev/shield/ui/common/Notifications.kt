package dev.shield.ui.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.shield.MainActivity
import dev.shield.R
import dev.shield.core.vpn.ShieldVpnService
import dev.shield.core.vpn.TunnelPhase
import dev.shield.core.vpn.TunnelState
import dev.shield.data.TunnelMode

object Notifications {
    /** Quiet mode: no pop-ups; set from the settings by ShieldApp. */
    @Volatile
    var quiet: Boolean = false

    const val CH_STATUS = "status"
    const val CH_STATUS_QUIET = "status_quiet"
    const val CH_ALERT = "alerts"
    const val CH_REPORT = "report"
    const val STATUS_ID = 1
    const val ALERT_ID = 2
    const val REPORT_ID = 3

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS_QUIET, ctx.getString(R.string.channel_status_quiet), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REPORT, ctx.getString(R.string.channel_report), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun statusNotification(ctx: Context, st: TunnelState): Notification {
        val (title, text) = when {
            st.panic -> ctx.getString(R.string.status_panic) to ctx.getString(R.string.notif_panic_text)
            st.phase == TunnelPhase.RUNNING && st.wgWanted && !st.wgConnected ->
                ctx.getString(R.string.status_limited) to ctx.getString(R.string.notif_vpn_connecting, st.wgProfileName ?: "")
            st.phase == TunnelPhase.RUNNING -> ctx.getString(R.string.status_protected) to when {
                st.wgConnected -> ctx.getString(R.string.notif_vpn_connected, st.wgProfileName ?: "")
                st.mode == TunnelMode.FULL -> ctx.getString(R.string.notif_full)
                else -> ctx.getString(R.string.notif_dns_only)
            }
            st.phase == TunnelPhase.ERROR -> ctx.getString(R.string.status_unprotected) to (st.error ?: "")
            else -> ctx.getString(R.string.status_starting) to ""
        }
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, if (quiet) CH_STATUS_QUIET else CH_STATUS)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(ContextCompat.getColor(ctx, if (st.phase == TunnelPhase.ERROR || st.panic) R.color.status_red else R.color.status_green))
            .setContentIntent(openAppIntent(ctx))
            .addAction(0, ctx.getString(R.string.action_stop), stop)
            .build()
    }

    fun updateStatus(ctx: Context, st: TunnelState) {
        if (st.phase == TunnelPhase.STOPPED) return
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(STATUS_ID, statusNotification(ctx, st)) }
    }

    /** Red notification: protection is not (fully) active. */
    fun alert(ctx: Context, title: String, text: String) {
        if (quiet) return
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(ctx, R.color.status_red))
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n) }
    }

    fun cancelAlert(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    fun report(ctx: Context, title: String, text: String) {
        if (quiet) return
        val n = NotificationCompat.Builder(ctx, CH_REPORT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(REPORT_ID, n) }
    }
}
