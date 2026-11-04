package dev.shield.core.filter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.data.repo.SettingsRepository
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A subscribable blocklist. */
data class BlocklistSource(
    val id: String,
    val name: String,
    val url: String,
    val category: ListCategory,
    val defaultEnabled: Boolean,
)

data class ListStatus(val id: String, val rules: Int, val updatedAt: Long, val error: String?)

data class BlocklistState(
    val loaded: Boolean = false,
    val totalRules: Int = 0,
    val oldestUpdate: Long = 0,
    val updating: Boolean = false,
    val lists: Map<String, ListStatus> = emptyMap(),
)

/** Downloads, stores and parses the subscribed lists and loads them into the [FilterEngine]. */
@Singleton
class BlocklistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val engine: FilterEngine,
    private val settings: SettingsRepository,
    private val events: EventLogger,
) {
    private val dir = File(context.filesDir, "lists").apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")
    /** Serialises downloads. */
    private val mutex = Mutex()
    /** Serialises engine reloads; never held during network I/O, so a new level applies at once. */
    private val reloadMutex = Mutex()
    private val _state = MutableStateFlow(BlocklistState())
    val state: StateFlow<BlocklistState> = _state.asStateFlow()

    /** Loads the stored lists into the engine (no network). */
    suspend fun loadFromDisk() = withContext(Dispatchers.IO) { reloadEngine(settings.current().enabledLists) }

    /** Downloads all enabled lists (user action or 24 h worker) and reloads the engine. */
    suspend fun update(force: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            _state.value = _state.value.copy(updating = true)
            val enabled = settings.current().enabledLists
            val meta = readMeta()
            var ok = true
            for (src in SOURCES.filter { it.id in enabled && !it.url.startsWith(ASSET) }) {
                val prev = meta.optJSONObject(src.id)
                try {
                    events.info(EventType.NETWORK_ACCESS, "Blockliste laden: ${src.name}", mapOf("url" to src.url))
                    val req = Request.Builder().url(src.url).apply {
                        if (!force) prev?.optString("etag")?.takeIf { it.isNotEmpty() }?.let { header("If-None-Match", it) }
                        if (!force) prev?.optString("lastModified")?.takeIf { it.isNotEmpty() }?.let { header("If-Modified-Since", it) }
                    }.build()
                    http.newCall(req).execute().use { resp ->
                        when {
                            resp.code == 304 -> {
                                prev?.put("updatedAt", System.currentTimeMillis())
                                prev?.remove("error")
                            }
                            resp.isSuccessful -> {
                                val tmp = File(dir, "${src.id}.tmp")
                                resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                                val parsed = tmp.bufferedReader().useLines { BlocklistParser.parse(it) }
                                if (parsed.blocked.isEmpty()) throw IllegalStateException("Liste ist leer")
                                tmp.renameTo(File(dir, "${src.id}.txt"))
                                meta.put(
                                    src.id,
                                    JSONObject()
                                        .put("updatedAt", System.currentTimeMillis())
                                        .put("rules", parsed.blocked.size)
                                        .put("etag", resp.header("ETag") ?: "")
                                        .put("lastModified", resp.header("Last-Modified") ?: ""),
                                )
                            }
                            else -> throw IllegalStateException("HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    ok = false
                    ShieldLog.e("Lists", "update ${src.id} failed", e)
                    val o = prev ?: JSONObject()
                    o.put("error", e.message ?: e.javaClass.simpleName)
                    meta.put(src.id, o)
                    events.warn(EventType.LISTS_UPDATE_FAILED, "${src.name}: ${e.message}")
                }
            }
            writeMeta(meta)
            reloadEngine(settings.current().enabledLists) // the selection may have changed during the download
            settings.setListsUpdatedAt(System.currentTimeMillis())
            if (ok) {
                events.info(EventType.LISTS_UPDATED, "Blocklisten aktualisiert: ${engine.ruleCount} Regeln")
            }
            _state.value = _state.value.copy(updating = false)
            ok
        }
    }

    private suspend fun reloadEngine(enabled: Set<String>) = reloadMutex.withLock { reloadEngineLocked(enabled) }

    private suspend fun reloadEngineLocked(enabled: Set<String>) {
        val meta = readMeta()
        val aggressive = settings.current().protectionLevel.aggressiveEasyPrivacy
        val loaded = SOURCES.filter { it.id in enabled }.mapNotNull { src ->
            try {
                val reader = if (src.url.startsWith(ASSET)) {
                    context.assets.open(src.url.removePrefix(ASSET)).bufferedReader()
                } else {
                    val f = File(dir, "${src.id}.txt")
                    if (!f.exists()) return@mapNotNull null
                    f.bufferedReader()
                }
                val agg = aggressive && src.id == "easyprivacy"
                LoadedList(src.id, src.name, src.category, reader.useLines { BlocklistParser.parse(it, agg) })
            } catch (e: Exception) {
                ShieldLog.e("Lists", "parse ${src.id} failed", e)
                null
            }
        }
        engine.setLists(loaded)
        val statuses = SOURCES.associate { src ->
            val m = meta.optJSONObject(src.id)
            src.id to ListStatus(
                src.id,
                engine.listCounts[src.id] ?: 0,
                if (src.url.startsWith(ASSET)) System.currentTimeMillis() else m?.optLong("updatedAt") ?: 0L,
                m?.optString("error")?.takeIf { it.isNotEmpty() },
            )
        }
        val activeUpdates = statuses.values.filter { it.id in enabled && it.rules > 0 }.map { it.updatedAt }
        _state.value = BlocklistState(
            loaded = engine.ruleCount > 0,
            totalRules = engine.ruleCount,
            oldestUpdate = activeUpdates.minOrNull() ?: 0L,
            updating = _state.value.updating,
            lists = statuses,
        )
        ShieldLog.i("Lists", "${loaded.size} Listen geladen, ${engine.ruleCount} Regeln")
    }

    private fun readMeta(): JSONObject =
        runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }

    private fun writeMeta(o: JSONObject) {
        metaFile.writeText(o.toString())
    }

    companion object {
        /** Lists shipped inside the APK (work offline, no download). */
        const val ASSET = "asset:"
        private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock"

        val SOURCES = listOf(
            BlocklistSource(
                "adguard_dns", "AdGuard DNS Filter",
                "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", ListCategory.ADS, true,
            ),
            BlocklistSource(
                "easyprivacy", "EasyPrivacy",
                "https://easylist.to/easylist/easyprivacy.txt", ListCategory.TRACKING, true,
            ),
            BlocklistSource(
                "urlhaus", "URLhaus Malware",
                "https://urlhaus.abuse.ch/downloads/hostfile/", ListCategory.MALWARE, true,
            ),
            BlocklistSource(
                "phishing_army", "Phishing Army",
                "https://phishing.army/download/phishing_army_blocklist.txt", ListCategory.PHISHING, true,
            ),
            BlocklistSource(
                "phishing_filter", "Phishing Filter (malware-filter)",
                "https://malware-filter.gitlab.io/malware-filter/phishing-filter-agh.txt", ListCategory.PHISHING, false,
            ),
            BlocklistSource("builtin_telemetry", "Shield Telemetrie-Liste (eingebaut)", ASSET + "lists/telemetry.txt", ListCategory.TRACKING, false),
            BlocklistSource("hagezi_pro", "HaGeZi Pro", "$HAGEZI/pro.txt", ListCategory.TRACKING, false),
            BlocklistSource("hagezi_pro_plus", "HaGeZi Pro++", "$HAGEZI/pro.plus.txt", ListCategory.TRACKING, false),
            BlocklistSource("hagezi_tif", "HaGeZi Threat Intelligence (Medium)", "$HAGEZI/tif.medium.txt", ListCategory.MALWARE, false),
            BlocklistSource("hagezi_doh", "HaGeZi DNS-Umgehung (DoH/DoT-Server)", "$HAGEZI/doh.txt", ListCategory.MALWARE, false),
            BlocklistSource("hagezi_native_samsung", "HaGeZi Samsung-Telemetrie", "$HAGEZI/native.samsung.txt", ListCategory.TRACKING, false),
            BlocklistSource("hagezi_native_xiaomi", "HaGeZi Xiaomi-Telemetrie", "$HAGEZI/native.xiaomi.txt", ListCategory.TRACKING, false),
        )
        val PHISHING_IDS = SOURCES.filter { it.category == ListCategory.PHISHING }.map { it.id }.toSet()
    }
}
