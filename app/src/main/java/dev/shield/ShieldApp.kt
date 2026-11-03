package dev.shield

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.shield.core.vpn.CrashGuard
import dev.shield.data.repo.RuleSync
import dev.shield.logging.ShieldLog
import dev.shield.ui.common.Notifications
import dev.shield.work.Schedules
import javax.inject.Inject

@HiltAndroidApp
class ShieldApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var crashGuard: CrashGuard
    @Inject lateinit var schedules: Schedules
    @Inject lateinit var ruleSync: RuleSync

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        ShieldLog.minLevel = if (BuildConfig.DEBUG) dev.shield.logging.LogLevel.VERBOSE else dev.shield.logging.LogLevel.DEBUG
        Notifications.createChannels(this)
        crashGuard.install()
        ruleSync.start()
        schedules.ensureScheduled()
        ShieldLog.i("App", "Shield ${BuildConfig.VERSION_NAME} gestartet")
    }
}
