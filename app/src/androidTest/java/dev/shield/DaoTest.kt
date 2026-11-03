package dev.shield

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.shield.data.db.DnsQueryEntity
import dev.shield.data.db.ShieldDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: ShieldDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ShieldDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun queriesFilterAndRetention() = runBlocking {
        val now = System.currentTimeMillis()
        db.dnsQueryDao().insertAll(
            listOf(
                DnsQueryEntity(time = now, uid = 1, app = "A", domain = "ads.com", type = 1, result = "BLOCKED", rule = "L", latencyMs = 0),
                DnsQueryEntity(time = now, uid = 1, app = "A", domain = "ads.com", type = 1, result = "BLOCKED", rule = "L", latencyMs = 0),
                DnsQueryEntity(time = now - 10 * 86_400_000L, uid = 2, app = "B", domain = "ok.org", type = 1, result = "ALLOWED", rule = null, latencyMs = 5),
            ),
        )
        assertEquals(2, db.dnsQueryDao().observe("BLOCKED", null, "", 100).first().size)
        assertEquals(1, db.dnsQueryDao().observe(null, 2, "", 100).first().size)
        assertEquals("ads.com", db.dnsQueryDao().topBlockedDomains(now - 1000, 5).first().single().domain)
        assertEquals(1, db.dnsQueryDao().deleteOlderThan(now - 7 * 86_400_000L))
    }

    @Test
    fun statsIncrementAggregates() = runBlocking {
        val dao = db.statsDao()
        dao.increment(20261103, 500L, mapOf((1 to "a") to longArrayOf(10, 3)), 1)
        dao.increment(20261103, 500L, mapOf((1 to "a") to longArrayOf(5, 2), (2 to "b") to longArrayOf(1, 0)), 0)
        assertEquals(5L, dao.blockedOnDay(20261103).first())
        assertEquals(16L, dao.queriesOnDay(20261103).first())
        assertEquals(1L, dao.phishingSince(0).first())
        assertEquals(1, dao.topApps(20261103, 5).first().first().uid)
    }
}
