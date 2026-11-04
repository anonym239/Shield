package dev.shield.core.wireguard

import dev.shield.data.SecretStore
import dev.shield.data.db.WgProfileDao
import dev.shield.data.db.WgProfileEntity
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** WireGuard profiles: metadata in Room, the config itself encrypted in the [SecretStore]. */
@Singleton
class WgProfileRepository @Inject constructor(
    private val dao: WgProfileDao,
    private val secrets: SecretStore,
    private val events: EventLogger,
) {
    val profiles: Flow<List<WgProfileEntity>> = dao.observe()

    /** Imports a .conf / QR text. Throws [WgConfigException] with a readable message. */
    suspend fun import(name: String, text: String): WgProfileEntity = withContext(Dispatchers.IO) {
        val cfg = WgConfigParser.parse(text)
        val id = UUID.randomUUID().toString()
        secrets.put("wg_$id", text.toByteArray())
        val p = WgProfileEntity(id, name.ifBlank { cfg.endpointSummary }, cfg.endpointSummary, System.currentTimeMillis())
        dao.upsert(p)
        events.info(EventType.SETTINGS_CHANGED, "WireGuard-Profil importiert: ${p.name}")
        p
    }

    suspend fun config(id: String): WgConfig? = withContext(Dispatchers.IO) {
        secrets.get("wg_$id")?.let { WgConfigParser.parse(String(it)) }
    }

    suspend fun rawConfig(id: String): String? = withContext(Dispatchers.IO) { secrets.get("wg_$id")?.let { String(it) } }

    suspend fun get(id: String): WgProfileEntity? = dao.get(id)

    suspend fun all(): List<WgProfileEntity> = dao.all()

    suspend fun rename(id: String, name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        dao.get(id)?.let { dao.upsert(it.copy(name = clean)) }
        events.info(EventType.SETTINGS_CHANGED, "VPN-Server umbenannt: $clean")
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        secrets.delete("wg_$id")
        dao.delete(id)
        events.info(EventType.SETTINGS_CHANGED, "WireGuard-Profil gelöscht")
    }
}
