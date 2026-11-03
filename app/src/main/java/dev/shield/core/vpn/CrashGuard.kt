package dev.shield.core.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.R
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.Redactor
import dev.shield.ui.common.Notifications
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A crash must never silently leave the user unprotected (SPEC §7): the crash is written to a
 * marker file synchronously, a red notification is shown and the event is stored on the next start.
 */
@Singleton
class CrashGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventLogger,
) {
    private val marker = File(context.filesDir, "last_crash.txt")

    fun install() {
        if (marker.exists()) {
            val text = runCatching { marker.readText() }.getOrDefault("")
            events.critical(EventType.SERVICE_CRASH, "Shield ist abgestürzt", mapOf("trace" to text.take(4000)))
            marker.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                marker.writeText(Redactor.redact("${t.name}: ${e.stackTraceToString()}"))
                Notifications.alert(
                    context,
                    context.getString(R.string.alert_unprotected_title),
                    context.getString(R.string.alert_crash),
                )
            }
            previous?.uncaughtException(t, e)
        }
    }
}
