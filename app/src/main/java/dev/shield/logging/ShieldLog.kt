package dev.shield.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/** Log levels of the live log (same order as Android). */
enum class LogLevel(val priority: Int, val short: String) {
    VERBOSE(Log.VERBOSE, "V"),
    DEBUG(Log.DEBUG, "D"),
    INFO(Log.INFO, "I"),
    WARN(Log.WARN, "W"),
    ERROR(Log.ERROR, "E"),
    ;

    companion object {
        fun fromPriority(p: Int): LogLevel = entries.firstOrNull { it.priority == p } ?: INFO
    }
}

data class LogLine(val time: Long, val level: LogLevel, val tag: String, val message: String)

/**
 * The only logging entry point of the app (see CLAUDE.md). Writes to logcat and to an
 * in-memory ring buffer that the debug screen shows live. Messages are redacted before
 * they are stored, so secrets never reach logcat, the buffer or an export.
 */
object ShieldLog {
    private const val TAG_PREFIX = "Shield/"
    private const val CAPACITY = 2000

    private val lock = Any()
    private val buffer = ArrayDeque<LogLine>(CAPACITY)
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    fun v(tag: String, msg: String) = log(LogLevel.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(LogLevel.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(LogLevel.ERROR, tag, msg, t)

    fun log(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        if (level.priority < minLevel.priority) return
        val text = Redactor.redact(if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
        try {
            Log.println(level.priority, TAG_PREFIX + tag, text)
        } catch (_: RuntimeException) {
            // android.util.Log is not available in local unit tests
        }
        val line = LogLine(System.currentTimeMillis(), level, tag, text)
        synchronized(lock) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
            _lines.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }
}
