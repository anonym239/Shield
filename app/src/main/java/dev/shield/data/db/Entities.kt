package dev.shield.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One DNS query (SPEC §4). Not stored when "Kein Verlauf speichern" is on. */
@Entity(tableName = "dns_query", indices = [Index("time"), Index("uid"), Index("domain")])
data class DnsQueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val uid: Int,
    val app: String,
    val domain: String,
    val type: Int,
    /** ALLOWED, BLOCKED or ERROR */
    val result: String,
    /** Rule or list that decided, e.g. "AdGuard DNS Filter" or "Eigene Regel: *.ads.com" */
    val rule: String?,
    val latencyMs: Int,
)

@Entity(tableName = "security_event", indices = [Index("time"), Index("type")])
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val type: String,
    val severity: String,
    val message: String,
    val detailsJson: String?,
)

/** Aggregated counters per app and day (yyyyMMdd). */
@Entity(tableName = "app_stats", primaryKeys = ["day", "uid"], indices = [Index("day")])
data class AppStatsEntity(
    val day: Int,
    val uid: Int,
    val packageName: String,
    val queries: Long,
    val blocked: Long,
)

/** Aggregated counters per hour (epoch hour) for the 24 h chart; contains no domains. */
@Entity(tableName = "hour_stats")
data class HourStatsEntity(
    @PrimaryKey val hour: Long,
    val queries: Long,
    val blocked: Long,
    /** Blocked by a phishing list (own dashboard category). */
    val phishing: Long = 0,
)

@Entity(tableName = "custom_rule", indices = [Index(value = ["pattern"], unique = true)])
data class CustomRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    /** ALLOW or BLOCK */
    val action: String,
    val createdAt: Long,
)

@Entity(tableName = "app_rule")
data class AppRuleEntity(
    @PrimaryKey val packageName: String,
    val uid: Int,
    /** ALLOW, WIFI_ONLY, MOBILE_ONLY, BLOCK */
    val policy: String,
    /** May use the network during the night mode window. */
    val nightAllowed: Boolean = false,
    /** Mobile data blocked while the data saver is on. */
    val dataSaver: Boolean = false,
)

@Entity(tableName = "homelab_tile")
data class HomelabTileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val position: Int = 0,
)

/** Metadata of a WireGuard profile. The config with the private key is stored encrypted. */
@Entity(tableName = "wg_profile")
data class WgProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val endpoint: String,
    val createdAt: Long,
)
