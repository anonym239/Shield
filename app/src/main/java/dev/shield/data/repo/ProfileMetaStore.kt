package dev.shield.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileMeta: DataStore<Preferences> by preferencesDataStore(name = "profile_meta")

/** Last speed test of a VPN server. */
data class SpeedResult(val downMbps: Double, val upMbps: Double, val pingMs: Int, val testedAt: Long)

/** Extra data for a WireGuard profile (no secrets): country and the last speed test. */
data class ProfileMeta(val country: String? = null, val speed: SpeedResult? = null)

/**
 * Stored separately from the Room table so no database migration is needed (the database falls back to a
 * destructive migration on schema changes, which would delete the user's data).
 */
@Singleton
class ProfileMetaStore @Inject constructor(@ApplicationContext private val context: Context) {
    val all: Flow<Map<String, ProfileMeta>> = context.profileMeta.data.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) -> (v as? String)?.let { k.name to decode(it) } }.toMap()
    }

    suspend fun get(id: String): ProfileMeta = all.first()[id] ?: ProfileMeta()

    suspend fun setCountry(id: String, code: String?) = edit(id) { it.copy(country = code) }

    suspend fun setSpeed(id: String, r: SpeedResult) = edit(id) { it.copy(speed = r) }

    suspend fun delete(id: String) {
        context.profileMeta.edit { it.remove(stringPreferencesKey(id)) }
    }

    private suspend fun edit(id: String, fn: (ProfileMeta) -> ProfileMeta) {
        context.profileMeta.edit { prefs ->
            val key = stringPreferencesKey(id)
            prefs[key] = encode(fn(prefs[key]?.let(::decode) ?: ProfileMeta()))
        }
    }

    companion object {
        fun encode(m: ProfileMeta): String = JSONObject().apply {
            m.country?.let { put("country", it) }
            m.speed?.let {
                put("down", it.downMbps)
                put("up", it.upMbps)
                put("ping", it.pingMs)
                put("at", it.testedAt)
            }
        }.toString()

        fun decode(s: String): ProfileMeta = try {
            val o = JSONObject(s)
            ProfileMeta(
                country = o.optString("country").takeIf { it.length == 2 },
                speed = if (o.has("at")) SpeedResult(o.optDouble("down"), o.optDouble("up"), o.optInt("ping"), o.optLong("at")) else null,
            )
        } catch (e: org.json.JSONException) {
            ProfileMeta()
        }
    }
}
