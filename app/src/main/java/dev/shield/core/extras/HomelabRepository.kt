package dev.shield.core.extras

import dev.shield.data.db.HomelabDao
import dev.shield.data.db.HomelabTileEntity
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class Reachability(val ok: Boolean, val code: Int?, val latencyMs: Long?, val error: String?)

/** Homelab tiles with reachability status and Wake-on-LAN (SPEC §2.4). */
@Singleton
class HomelabRepository @Inject constructor(
    private val dao: HomelabDao,
    http: OkHttpClient,
    private val events: EventLogger,
) {
    private val client = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).followRedirects(true).build()

    val tiles: Flow<List<HomelabTileEntity>> = dao.observe()

    suspend fun add(name: String, url: String) = dao.upsert(HomelabTileEntity(name = name, url = url))
    suspend fun delete(id: Long) = dao.delete(id)

    /** User-triggered reachability check of all tiles. */
    suspend fun checkAll(tiles: List<HomelabTileEntity>): Map<Long, Reachability> = coroutineScope {
        events.info(EventType.NETWORK_ACCESS, "Homelab-Check: ${tiles.size} Dienste")
        tiles.map { t -> async(Dispatchers.IO) { t.id to check(t.url) } }.awaitAll().toMap()
    }

    private fun check(url: String): Reachability {
        val start = System.currentTimeMillis()
        return try {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                Reachability(r.code < 500, r.code, System.currentTimeMillis() - start, null)
            }
        } catch (e: Exception) {
            Reachability(false, null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Sends a magic packet to the broadcast (or given) address, port 9. */
    suspend fun wake(mac: String, broadcast: String): Boolean = withContext(Dispatchers.IO) {
        val m = WakeOnLan.parseMac(mac) ?: return@withContext false
        val packet = WakeOnLan.magicPacket(m)
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                val addr = InetAddress.getByName(broadcast.ifBlank { "255.255.255.255" })
                s.send(DatagramPacket(packet, packet.size, addr, 9))
            }
            events.info(EventType.NETWORK_ACCESS, "Wake-on-LAN gesendet")
            true
        }.getOrDefault(false)
    }
}
