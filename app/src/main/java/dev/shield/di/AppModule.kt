package dev.shield.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.shield.core.dns.DnsCache
import dev.shield.core.filter.FilterEngine
import dev.shield.data.db.DnsQueryDao
import dev.shield.data.db.HomelabDao
import dev.shield.data.db.RuleDao
import dev.shield.data.db.SecurityEventDao
import dev.shield.data.db.ShieldDatabase
import dev.shield.data.db.StatsDao
import dev.shield.data.db.WgProfileDao
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun db(@ApplicationContext ctx: Context): ShieldDatabase =
        Room.databaseBuilder(ctx, ShieldDatabase::class.java, "shield.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun dnsDao(db: ShieldDatabase): DnsQueryDao = db.dnsQueryDao()
    @Provides fun eventDao(db: ShieldDatabase): SecurityEventDao = db.securityEventDao()
    @Provides fun statsDao(db: ShieldDatabase): StatsDao = db.statsDao()
    @Provides fun ruleDao(db: ShieldDatabase): RuleDao = db.ruleDao()
    @Provides fun homelabDao(db: ShieldDatabase): HomelabDao = db.homelabDao()
    @Provides fun wgDao(db: ShieldDatabase): WgProfileDao = db.wgProfileDao()

    @Provides
    @Singleton
    fun filterEngine(): FilterEngine = FilterEngine()

    @Provides
    @Singleton
    fun dnsCache(): DnsCache = DnsCache()

    /** OkHttp for list downloads and user-triggered checks (never used for analytics). */
    @Provides
    @Singleton
    fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}
