package dev.shield.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.shield.MainActivity
import dev.shield.R
import dev.shield.core.checks.StatusLevel
import dev.shield.core.checks.StatusRepository
import dev.shield.core.vpn.TunnelPhase
import dev.shield.core.vpn.VpnController
import dev.shield.data.db.StatsDao
import dev.shield.data.repo.QueryRecorder
import dev.shield.ui.common.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home screen widget: status color + text, on/off, blocked today (SPEC §2.5). */
@AndroidEntryPoint
class ShieldWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var controller: VpnController
    @Inject lateinit var status: StatusRepository
    @Inject lateinit var stats: StatsDao

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
        ensureObserving(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val running = controller.state.value.phase != TunnelPhase.STOPPED
            if (!running && VpnService.prepare(context) != null) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            val pending = goAsync()
            scope.launch {
                try {
                    controller.setProtection(!running)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val st = controller.state.value
        val level = status.overview.value?.status?.level
            ?: if (st.phase == TunnelPhase.RUNNING) StatusLevel.LIMITED else StatusLevel.UNPROTECTED
        val blocked = runCatching { stats.blockedOnDayOnce(QueryRecorder.today()) }.getOrDefault(0L)
        val color = ContextCompat.getColor(
            context,
            when (level) {
                StatusLevel.PROTECTED -> R.color.status_green
                StatusLevel.LIMITED -> R.color.status_yellow
                StatusLevel.UNPROTECTED -> R.color.status_red
            },
        )
        val statusText = context.getString(
            when {
                st.panic -> R.string.status_panic
                level == StatusLevel.PROTECTED -> R.string.status_protected
                level == StatusLevel.LIMITED -> R.string.status_limited
                else -> R.string.status_unprotected
            },
        )
        val running = st.phase != TunnelPhase.STOPPED
        ids.forEach { id ->
            val v = RemoteViews(context.packageName, R.layout.widget_shield)
            v.setImageViewResource(R.id.widget_icon, R.drawable.ic_shield)
            v.setInt(R.id.widget_icon, "setColorFilter", color)
            v.setTextViewText(R.id.widget_status, statusText)
            v.setContentDescription(R.id.widget_icon, statusText)
            v.setTextViewText(R.id.widget_blocked, context.getString(R.string.widget_blocked, Format.number(blocked)))
            v.setTextViewText(R.id.widget_toggle, context.getString(if (running) R.string.action_stop else R.string.action_start))
            v.setOnClickPendingIntent(
                R.id.widget_toggle,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, ShieldWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            v.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(id, v)
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun ensureObserving(context: Context) {
        synchronized(Companion) {
            if (observing) return
            observing = true
        }
        val app = context.applicationContext
        scope.launch {
            combine(controller.state, status.overview) { a, b -> a to b }.debounce(1000).collect { refresh(app) }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "dev.shield.widget.TOGGLE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var observing = false

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ShieldWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ShieldWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
