package dev.shield.logging

import dev.shield.data.db.SecurityEventDao
import dev.shield.data.db.SecurityEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records security events (table security_event) and mirrors them into the live log.
 * Every security relevant state change goes through here (CLAUDE.md).
 */
@Singleton
class EventLogger @Inject constructor(private val dao: SecurityEventDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(
        type: EventType,
        severity: Severity,
        message: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        val safeMessage = Redactor.redact(message)
        val level = when (severity) {
            Severity.INFO -> LogLevel.INFO
            Severity.WARNING -> LogLevel.WARN
            Severity.CRITICAL -> LogLevel.ERROR
        }
        ShieldLog.log(level, "Event", "${type.name}: $safeMessage", null)
        val json = if (details.isEmpty()) {
            null
        } else {
            Redactor.redact(JSONObject().apply { details.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }.toString())
        }
        scope.launch {
            try {
                dao.insert(
                    SecurityEventEntity(
                        time = System.currentTimeMillis(),
                        type = type.name,
                        severity = severity.name,
                        message = safeMessage,
                        detailsJson = json,
                    ),
                )
            } catch (e: Exception) {
                ShieldLog.e("Event", "could not store event", e)
            }
        }
    }

    fun info(type: EventType, message: String, details: Map<String, Any?> = emptyMap()) =
        record(type, Severity.INFO, message, details)

    fun warn(type: EventType, message: String, details: Map<String, Any?> = emptyMap()) =
        record(type, Severity.WARNING, message, details)

    fun critical(type: EventType, message: String, details: Map<String, Any?> = emptyMap()) =
        record(type, Severity.CRITICAL, message, details)
}
