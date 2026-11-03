package dev.shield.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.R
import dev.shield.core.checks.SelfTestRunner
import dev.shield.core.filter.BlocklistRepository
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.SecurityEventDao
import dev.shield.data.db.StatsDao
import dev.shield.data.repo.AppRepository
import dev.shield.data.repo.QueryRecorder
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import dev.shield.ui.common.Notifications
import dev.shield.ui.widget.ShieldWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules all background work (SPEC §5.1 WorkManager). */
@Singleton
class Schedules @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureScheduled() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "selftest", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SelfTestWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "retention", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
        )
        scope.launch {
            settings.settings.map { Triple(it.listsAutoUpdate, it.listsWifiOnly, it.weeklyReportEnabled) }
                .distinctUntilChanged()
                .collect { (auto, wifiOnly, weekly) ->
                    if (auto) {
                        val c = Constraints.Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build()
                        wm.enqueueUniquePeriodicWork(
                            "lists", ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequestBuilder<ListUpdateWorker>(24, TimeUnit.HOURS).setConstraints(c).build(),
                        )
                    } else {
                        wm.cancelUniqueWork("lists")
                    }
                    if (weekly) {
                        wm.enqueueUniquePeriodicWork(
                            "weekly", ExistingPeriodicWorkPolicy.KEEP,
                            PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS).build(),
                        )
                    } else {
                        wm.cancelUniqueWork("weekly")
                    }
                }
        }
    }
}

@HiltWorker
class ListUpdateWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val blocklists: BlocklistRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = if (blocklists.update()) Result.success() else Result.retry()
}

@HiltWorker
class SelfTestWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val runner: SelfTestRunner,
    private val settings: SettingsRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (settings.current().protectionEnabled) {
            val report = runner.run(full = false)
            if (!report.allPassed) {
                Notifications.alert(
                    applicationContext,
                    applicationContext.getString(R.string.alert_selftest_title),
                    report.results.filter { it.passed == false }.joinToString("\n") { it.detail },
                )
            }
        }
        ShieldWidgetProvider.refresh(applicationContext)
        return Result.success()
    }
}

/** Retention rules (SPEC §4): queries 7 d, daily stats 90 d, events 30 d (configurable). */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val dnsDao: DnsQueryDao,
    private val eventDao: SecurityEventDao,
    private val statsDao: StatsDao,
    private val events: EventLogger,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = settings.current()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        return try {
            val q = if (s.noHistory) dnsDao.deleteOlderThan(Long.MAX_VALUE) else dnsDao.deleteOlderThan(now - s.retentionQueriesDays * day)
            val e = eventDao.deleteOlderThan(now - s.retentionEventsDays * day)
            val st = statsDao.deleteAppOlderThan(QueryRecorder.dayOf(LocalDate.now().minusDays(s.retentionStatsDays.toLong())))
            statsDao.deleteHourOlderThan(now / QueryRecorder.HOUR - s.retentionStatsDays * 24L)
            ShieldLog.i("Retention", "gelöscht: $q Anfragen, $e Ereignisse, $st Tagesstatistiken")
            events.info(EventType.RETENTION, "Aufräumen: $q Anfragen, $e Ereignisse, $st Statistiken gelöscht")
            Result.success()
        } catch (ex: Exception) {
            ShieldLog.e("Retention", "failed", ex)
            Result.retry()
        }
    }
}

/** Local weekly summary notification (no network). */
@HiltWorker
class WeeklyReportWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val statsDao: StatsDao,
    private val apps: AppRepository,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val fromHour = System.currentTimeMillis() / QueryRecorder.HOUR - 7 * 24
        val q = statsDao.queriesSinceOnce(fromHour)
        val b = statsDao.blockedSinceOnce(fromHour)
        val top = statsDao.topAppsOnce(QueryRecorder.dayOf(LocalDate.now().minusDays(7)), 1).firstOrNull()
        val topName = top?.let { apps.resolve(it.uid).second }
        val ctx = applicationContext
        val text = buildString {
            append(ctx.getString(R.string.report_text, b, q))
            if (topName != null) append('\n').append(ctx.getString(R.string.report_top_app, topName, top.queries))
        }
        Notifications.report(ctx, ctx.getString(R.string.report_title), text)
        return Result.success()
    }
}
