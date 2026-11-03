package dev.shield.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.BuildConfig
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.SecurityEventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a text export (live log + events, optionally DNS history) and shares it via the
 * system share sheet. By default no domains / IPs are included (SPEC §3.5).
 */
@Singleton
class LogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: SecurityEventDao,
    private val queries: DnsQueryDao,
) {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val ipRe = Regex("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b|\\b([0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\\b")
    private val domainRe = Regex("\\b([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}\\b")

    suspend fun buildShareIntent(includeDomains: Boolean): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "shield-log-${System.currentTimeMillis()}.txt")
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        file.bufferedWriter().use { w ->
            w.appendLine("Shield ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            w.appendLine("Export: ${fmt.format(Date())} · Domains/IPs: ${if (includeDomains) "enthalten" else "entfernt"}")
            w.appendLine()
            w.appendLine("== Ereignisse (7 Tage) ==")
            events.since(since).forEach { e ->
                w.appendLine("${fmt.format(Date(e.time))} [${e.severity}] ${e.type}: ${clean(e.message, includeDomains)}")
            }
            w.appendLine()
            w.appendLine("== Live-Log ==")
            ShieldLog.lines.value.forEach { l ->
                w.appendLine("${fmt.format(Date(l.time))} ${l.level.short}/${l.tag}: ${clean(l.message, includeDomains)}")
            }
            if (includeDomains) {
                w.appendLine()
                w.appendLine("== DNS-Anfragen (letzte 2000) ==")
                queries.since(since, 2000).forEach { q ->
                    w.appendLine("${fmt.format(Date(q.time))} ${q.app} ${q.domain} ${q.result} ${q.rule ?: ""} ${q.latencyMs}ms")
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Shield Log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun clean(text: String, includeDomains: Boolean): String {
        val r = Redactor.redact(text)
        if (includeDomains) return r
        return domainRe.replace(ipRe.replace(r, "<ip>")) { m ->
            // keep class names / file names that look like domains (e.g. "ShieldVpnService.kt")
            if (m.value.endsWith(".kt") || m.value.endsWith(".java")) m.value else "<domain>"
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        ShieldLog.clear()
        events.deleteAll()
        queries.deleteAll()
    }
}
