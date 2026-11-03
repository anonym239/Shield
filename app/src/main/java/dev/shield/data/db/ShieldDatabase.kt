package dev.shield.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DnsQueryEntity::class,
        SecurityEventEntity::class,
        AppStatsEntity::class,
        HourStatsEntity::class,
        CustomRuleEntity::class,
        AppRuleEntity::class,
        HomelabTileEntity::class,
        WgProfileEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ShieldDatabase : RoomDatabase() {
    abstract fun dnsQueryDao(): DnsQueryDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun statsDao(): StatsDao
    abstract fun ruleDao(): RuleDao
    abstract fun homelabDao(): HomelabDao
    abstract fun wgProfileDao(): WgProfileDao
}
