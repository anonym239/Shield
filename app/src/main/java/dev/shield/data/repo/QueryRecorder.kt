package dev.shield.data.repo

import dev.shield.core.dns.QueryResult
import dev.shield.core.dns.QuerySink
import dev.shield.core.filter.FilterEngine
import dev.shield.core.filter.ListCategory
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.DnsQueryEntity
import dev.shield.data.db.StatsDao
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects handled DNS queries and writes them in batches (every 2 s) to Room:
 * individual rows into dns_query (unless "Kein Verlauf speichern"), counters into app_stats/hour_stats.
 */
@Singleton
class QueryRecorder @Inject constructor(
    private val dnsDao: DnsQueryDao,
    private val statsDao: StatsDao,
    private val apps: AppRepository,
    settingsRepository: SettingsRepository,
) : QuerySink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var pending = ArrayList<DnsQueryEntity>()
    private var perApp = HashMap<Pair<Int, String>, LongArray>()
    private var phishing = 0L

    @Volatile
    private var noHistory = false

    /** Time of the last handled query for the self-test domain. */
    val lastSelfTestHit = AtomicLong(0)

    private val _live = MutableStateFlow(0L)
    /** Number of queries handled since the service started (for live UI updates). */
    val liveCounter: StateFlow<Long> = _live

    init {
        scope.launch { settingsRepository.settings.collect { noHistory = it.noHistory } }
        scope.launch {
            while (isActive) {
                delay(2000)
                flush()
            }
        }
    }

    override fun record(
        uid: Int,
        domain: String,
        type: Int,
        result: QueryResult,
        rule: String?,
        category: ListCategory?,
        latencyMs: Int,
    ) {
        if (domain == FilterEngine.SELFTEST_DOMAIN || domain.endsWith("." + FilterEngine.SELFTEST_DOMAIN)) {
            lastSelfTestHit.set(System.currentTimeMillis())
        }
        val (pkg, label) = apps.resolve(uid)
        val blocked = result == QueryResult.BLOCKED
        synchronized(lock) {
            if (!noHistory) {
                pending.add(
                    DnsQueryEntity(
                        time = System.currentTimeMillis(), uid = uid, app = label, domain = domain, type = type,
                        result = result.name, rule = rule, latencyMs = latencyMs,
                    ),
                )
            }
            val c = perApp.getOrPut(uid to pkg) { LongArray(2) }
            c[0]++
            if (blocked) c[1]++
            if (blocked && category == ListCategory.PHISHING) phishing++
        }
        _live.value = _live.value + 1
    }

    suspend fun flush() {
        val rows: List<DnsQueryEntity>
        val counts: Map<Pair<Int, String>, LongArray>
        val ph: Long
        synchronized(lock) {
            if (pending.isEmpty() && perApp.isEmpty()) return
            rows = pending
            counts = perApp
            ph = phishing
            pending = ArrayList()
            perApp = HashMap()
            phishing = 0
        }
        try {
            if (rows.isNotEmpty()) dnsDao.insertAll(rows)
            if (counts.isNotEmpty()) {
                val now = System.currentTimeMillis()
                statsDao.increment(today(), now / HOUR, counts, ph)
            }
        } catch (e: Exception) {
            ShieldLog.e("Recorder", "could not store queries", e)
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        fun today(): Int = LocalDate.now().format(DAY_FMT).toInt()
        fun dayOf(date: LocalDate): Int = date.format(DAY_FMT).toInt()
    }
}
