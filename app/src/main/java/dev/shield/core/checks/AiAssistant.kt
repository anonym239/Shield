package dev.shield.core.checks

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.BuildConfig
import dev.shield.R
import dev.shield.core.filter.BlocklistRepository
import dev.shield.core.vpn.VpnController
import dev.shield.data.SecretStore
import dev.shield.data.db.SecurityEventDao
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.Severity
import dev.shield.logging.ShieldLog
import dev.shield.ui.common.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional daily AI health check with the user's own Google Gemini API key.
 * Sends only an anonymous summary (no domains, IPs or keys) and may only trigger [AiAction]s.
 */
@Singleton
class AiAssistant @Inject constructor(
    @ApplicationContext private val context: Context,
    http: OkHttpClient,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val controller: VpnController,
    private val blocklists: BlocklistRepository,
    private val runner: SelfTestRunner,
    private val status: StatusRepository,
    private val eventDao: SecurityEventDao,
    private val events: EventLogger,
) {
    private val client = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    val lastVerdict: Flow<AiVerdict?> = settings.aiResultJson.map { AiVerdict.fromJson(it) }

    fun hasKey(): Boolean = runCatching { secrets.get(KEY_NAME) != null }.getOrDefault(false)

    fun saveKey(key: String) {
        secrets.put(KEY_NAME, key.trim().toByteArray())
        events.info(EventType.SETTINGS_CHANGED, "Gemini-API-Key gespeichert (verschlüsselt)")
    }

    fun deleteKey() {
        secrets.delete(KEY_NAME)
        events.info(EventType.SETTINGS_CHANGED, "Gemini-API-Key gelöscht")
    }

    suspend fun check(): Result<AiVerdict> = mutex.withLock {
        _running.value = true
        try {
            val key = secrets.get(KEY_NAME)?.let { String(it) } ?: return@withLock Result.failure(IllegalStateException(context.getString(R.string.ai_no_key)))
            val s = settings.current()
            val snapshot = snapshot()
            events.info(EventType.NETWORK_ACCESS, "KI-Prüfung: anonymisierte Zusammenfassung an Google Gemini (${s.aiModel})")
            val verdict = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/${s.aiModel}:generateContent")
                    .header("x-goog-api-key", key)
                    .post(AiProtocol.requestBody(AiProtocol.prompt(snapshot)).toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { r -> AiProtocol.parse(r.body?.string().orEmpty()) }
            }
            settings.setAiResultJson(verdict.toJson())
            events.record(
                EventType.AI_CHECK,
                when (verdict.status) {
                    AiStatus.OK -> Severity.INFO
                    AiStatus.WARNING -> Severity.WARNING
                    AiStatus.PROBLEM -> Severity.CRITICAL
                },
                "KI-Prüfung: ${verdict.summary}",
                mapOf("actions" to verdict.actions.joinToString()),
            )
            execute(verdict.actions)
            if (verdict.status == AiStatus.PROBLEM) Notifications.alert(context, context.getString(R.string.ai_title), verdict.summary)
            Result.success(verdict)
        } catch (e: Exception) {
            ShieldLog.e("AI", "AI check failed", e)
            events.warn(EventType.AI_CHECK, "KI-Prüfung fehlgeschlagen: ${e.message}")
            Result.failure(e)
        } finally {
            _running.value = false
        }
    }

    private suspend fun execute(actions: List<AiAction>) {
        val s = settings.current()
        for (a in actions) {
            when (a) {
                AiAction.RESTART_TUNNEL -> if (s.protectionEnabled && !s.panic) {
                    events.info(EventType.AI_CHECK, "KI-Aktion: Tunnel wird neu verbunden")
                    controller.restart()
                }
                AiAction.UPDATE_LISTS -> {
                    events.info(EventType.AI_CHECK, "KI-Aktion: Blocklisten werden aktualisiert")
                    blocklists.update(force = true)
                }
                AiAction.RUN_SELFTEST -> runner.run(full = false)
                AiAction.NONE -> Unit
            }
        }
    }

    private suspend fun snapshot(): HealthSnapshot {
        val s = settings.current()
        val t = controller.state.value
        val lists = blocklists.state.value
        val since = System.currentTimeMillis() - 86_400_000L
        val ev = eventDao.since(since)
        val report = status.selfTest.first()
        val overview = status.overview.value
        return HealthSnapshot(
            androidVersion = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            protectionEnabled = s.protectionEnabled,
            tunnelPhase = t.phase.name,
            tunnelMode = t.mode.name,
            protectionLevel = s.protectionLevel.name,
            wgWanted = t.wgWanted,
            wgConnected = t.wgConnected,
            handshakeAgeSec = t.handshakeAgeSec,
            listsRules = lists.totalRules,
            listsAgeHours = if (lists.oldestUpdate > 0) (System.currentTimeMillis() - lists.oldestUpdate) / 3_600_000 else -1,
            listErrors = lists.lists.values.count { it.error != null },
            selfTest = report?.results?.map { Triple(it.id.name, it.passed, it.detail) }.orEmpty(),
            eventCounts = ev.groupingBy { it.type }.eachCount(),
            recentProblems = ev.filter { it.severity != "INFO" }.map { "${it.type}: ${it.message}" },
            score = overview?.score?.score ?: -1,
            findings = overview?.findings?.map { it.name }.orEmpty(),
        )
    }

    companion object {
        const val KEY_NAME = "gemini_api_key"
        private val JSON = "application/json".toMediaType()
    }
}
