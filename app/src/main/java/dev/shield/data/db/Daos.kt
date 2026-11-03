package dev.shield.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class HourCount(val hour: Long, val queries: Long, val blocked: Long)
data class DomainCount(val domain: String, val count: Long)
data class AppCount(val uid: Int, val packageName: String, val queries: Long, val blocked: Long)

@Dao
interface DnsQueryDao {
    @Insert
    suspend fun insertAll(items: List<DnsQueryEntity>)

    @Query(
        """SELECT * FROM dns_query
           WHERE (:result IS NULL OR result = :result)
             AND (:uid IS NULL OR uid = :uid)
             AND (:search = '' OR domain LIKE '%' || :search || '%' OR app LIKE '%' || :search || '%')
           ORDER BY time DESC LIMIT :limit""",
    )
    fun observe(result: String?, uid: Int?, search: String, limit: Int): Flow<List<DnsQueryEntity>>

    @Query(
        """SELECT domain, COUNT(*) AS count FROM dns_query
           WHERE result = 'BLOCKED' AND time >= :since
           GROUP BY domain ORDER BY count DESC LIMIT :limit""",
    )
    fun topBlockedDomains(since: Long, limit: Int): Flow<List<DomainCount>>

    @Query(
        """SELECT domain, COUNT(*) AS count FROM dns_query
           WHERE uid = :uid AND time >= :since
           GROUP BY domain ORDER BY count DESC LIMIT :limit""",
    )
    fun topDomainsForUid(uid: Int, since: Long, limit: Int): Flow<List<DomainCount>>

    @Query(
        """SELECT domain, COUNT(*) AS count FROM dns_query
           WHERE uid = :uid AND result = 'BLOCKED' AND time >= :since
           GROUP BY domain ORDER BY count DESC LIMIT :limit""",
    )
    fun topBlockedForUid(uid: Int, since: Long, limit: Int): Flow<List<DomainCount>>

    @Query("SELECT * FROM dns_query WHERE time >= :since ORDER BY time DESC LIMIT :limit")
    suspend fun since(since: Long, limit: Int): List<DnsQueryEntity>

    @Query("DELETE FROM dns_query WHERE time < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM dns_query")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM dns_query")
    suspend fun count(): Long
}

@Dao
interface SecurityEventDao {
    @Insert
    suspend fun insert(event: SecurityEventEntity): Long

    @Query("SELECT * FROM security_event ORDER BY time DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<SecurityEventEntity>>

    @Query("SELECT * FROM security_event WHERE time >= :since ORDER BY time DESC")
    suspend fun since(since: Long): List<SecurityEventEntity>

    @Query("DELETE FROM security_event WHERE time < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM security_event")
    suspend fun deleteAll()
}

@Dao
abstract class StatsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAppIgnore(e: AppStatsEntity)

    @Query("UPDATE app_stats SET queries = queries + :q, blocked = blocked + :b WHERE day = :day AND uid = :uid")
    abstract suspend fun addApp(day: Int, uid: Int, q: Long, b: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertHourIgnore(e: HourStatsEntity)

    @Query("UPDATE hour_stats SET queries = queries + :q, blocked = blocked + :b, phishing = phishing + :p WHERE hour = :hour")
    abstract suspend fun addHour(hour: Long, q: Long, b: Long, p: Long)

    @Transaction
    open suspend fun increment(day: Int, hour: Long, perApp: Map<Pair<Int, String>, LongArray>, phishing: Long) {
        var totalQ = 0L
        var totalB = 0L
        perApp.forEach { (key, v) ->
            insertAppIgnore(AppStatsEntity(day, key.first, key.second, 0, 0))
            addApp(day, key.first, v[0], v[1])
            totalQ += v[0]
            totalB += v[1]
        }
        insertHourIgnore(HourStatsEntity(hour, 0, 0, 0))
        addHour(hour, totalQ, totalB, phishing)
    }

    @Query("SELECT hour, queries, blocked FROM hour_stats WHERE hour >= :fromHour ORDER BY hour")
    abstract fun hours(fromHour: Long): Flow<List<HourCount>>

    @Query(
        """SELECT uid, packageName, SUM(queries) AS queries, SUM(blocked) AS blocked FROM app_stats
           WHERE day >= :fromDay GROUP BY uid ORDER BY blocked DESC, queries DESC LIMIT :limit""",
    )
    abstract fun topApps(fromDay: Int, limit: Int): Flow<List<AppCount>>

    @Query(
        """SELECT uid, packageName, SUM(queries) AS queries, SUM(blocked) AS blocked FROM app_stats
           WHERE day >= :fromDay GROUP BY uid""",
    )
    abstract fun appsSince(fromDay: Int): Flow<List<AppCount>>

    @Query("SELECT COALESCE(SUM(blocked), 0) FROM app_stats WHERE day = :day")
    abstract fun blockedOnDay(day: Int): Flow<Long>

    @Query("SELECT COALESCE(SUM(blocked), 0) FROM app_stats WHERE day = :day")
    abstract suspend fun blockedOnDayOnce(day: Int): Long

    @Query("SELECT COALESCE(SUM(queries), 0) FROM app_stats WHERE day = :day")
    abstract fun queriesOnDay(day: Int): Flow<Long>

    @Query("SELECT COALESCE(SUM(phishing), 0) FROM hour_stats WHERE hour >= :fromHour")
    abstract fun phishingSince(fromHour: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(queries), 0) FROM hour_stats WHERE hour >= :fromHour")
    abstract suspend fun queriesSinceOnce(fromHour: Long): Long

    @Query("SELECT COALESCE(SUM(blocked), 0) FROM hour_stats WHERE hour >= :fromHour")
    abstract suspend fun blockedSinceOnce(fromHour: Long): Long

    @Query(
        """SELECT uid, packageName, SUM(queries) AS queries, SUM(blocked) AS blocked FROM app_stats
           WHERE day >= :fromDay GROUP BY uid ORDER BY queries DESC LIMIT :limit""",
    )
    abstract suspend fun topAppsOnce(fromDay: Int, limit: Int): List<AppCount>

    @Query("DELETE FROM app_stats WHERE day < :beforeDay")
    abstract suspend fun deleteAppOlderThan(beforeDay: Int): Int

    @Query("DELETE FROM hour_stats WHERE hour < :beforeHour")
    abstract suspend fun deleteHourOlderThan(beforeHour: Long): Int

    @Query("DELETE FROM app_stats")
    abstract suspend fun deleteAllApp()

    @Query("DELETE FROM hour_stats")
    abstract suspend fun deleteAllHour()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM custom_rule ORDER BY pattern")
    fun observeCustom(): Flow<List<CustomRuleEntity>>

    @Query("SELECT * FROM custom_rule")
    suspend fun allCustom(): List<CustomRuleEntity>

    @Upsert
    suspend fun upsertCustom(rule: CustomRuleEntity)

    @Query("DELETE FROM custom_rule WHERE pattern = :pattern")
    suspend fun deleteCustom(pattern: String)

    @Query("DELETE FROM custom_rule")
    suspend fun deleteAllCustom()

    @Query("SELECT * FROM app_rule")
    fun observeAppRules(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rule")
    suspend fun allAppRules(): List<AppRuleEntity>

    @Upsert
    suspend fun upsertAppRule(rule: AppRuleEntity)

    @Query("DELETE FROM app_rule")
    suspend fun deleteAllAppRules()
}

@Dao
interface HomelabDao {
    @Query("SELECT * FROM homelab_tile ORDER BY position, id")
    fun observe(): Flow<List<HomelabTileEntity>>

    @Upsert
    suspend fun upsert(tile: HomelabTileEntity)

    @Query("DELETE FROM homelab_tile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM homelab_tile")
    suspend fun all(): List<HomelabTileEntity>

    @Query("DELETE FROM homelab_tile")
    suspend fun deleteAll()
}

@Dao
interface WgProfileDao {
    @Query("SELECT * FROM wg_profile ORDER BY name")
    fun observe(): Flow<List<WgProfileEntity>>

    @Query("SELECT * FROM wg_profile WHERE id = :id")
    suspend fun get(id: String): WgProfileEntity?

    @Query("SELECT * FROM wg_profile")
    suspend fun all(): List<WgProfileEntity>

    @Upsert
    suspend fun upsert(p: WgProfileEntity)

    @Query("DELETE FROM wg_profile WHERE id = :id")
    suspend fun delete(id: String)
}
