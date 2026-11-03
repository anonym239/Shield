package dev.shield.data.repo

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shield.core.wireguard.WgProfileRepository
import dev.shield.data.BackupCrypto
import dev.shield.data.BlockResponse
import dev.shield.data.DohUpstream
import dev.shield.data.TunnelMode
import dev.shield.data.db.AppRuleEntity
import dev.shield.data.db.CustomRuleEntity
import dev.shield.data.db.HomelabDao
import dev.shield.data.db.HomelabTileEntity
import dev.shield.data.db.RuleDao
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted backup/restore of settings, rules, homelab tiles and (optionally) WireGuard profiles. */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val rules: RuleDao,
    private val homelab: HomelabDao,
    private val profiles: WgProfileRepository,
    private val events: EventLogger,
) {
    suspend fun export(uri: Uri, password: CharArray, includeProfiles: Boolean) = withContext(Dispatchers.IO) {
        val s = settings.current()
        val root = JSONObject()
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put(
                "settings",
                JSONObject()
                    .put("tunnelMode", s.tunnelMode.name)
                    .put("upstream", s.upstream.name)
                    .put("customDohUrl", s.customDohUrl)
                    .put("customDohBootstrap", s.customDohBootstrap)
                    .put("blockResponse", s.blockResponse.name)
                    .put("enabledLists", JSONArray(s.enabledLists.toList()))
                    .put("listsAutoUpdate", s.listsAutoUpdate)
                    .put("listsWifiOnly", s.listsWifiOnly)
                    .put("retentionQueriesDays", s.retentionQueriesDays)
                    .put("retentionStatsDays", s.retentionStatsDays)
                    .put("retentionEventsDays", s.retentionEventsDays)
                    .put("noHistory", s.noHistory)
                    .put("killSwitch", s.killSwitch)
                    .put("autoVpnEnabled", s.autoVpnEnabled)
                    .put("homeSsids", JSONArray(s.homeSsids.toList()))
                    .put("vpnOnOpenWifi", s.vpnOnOpenWifi)
                    .put("vpnOnUnknownWifi", s.vpnOnUnknownWifi)
                    .put("vpnOnMobile", s.vpnOnMobile)
                    .put("nightModeEnabled", s.nightModeEnabled)
                    .put("nightStartMinutes", s.nightStartMinutes)
                    .put("nightEndMinutes", s.nightEndMinutes)
                    .put("dataSaverEnabled", s.dataSaverEnabled)
                    .put("weeklyReportEnabled", s.weeklyReportEnabled),
            )
            .put("customRules", JSONArray().apply { rules.allCustom().forEach { put(JSONObject().put("p", it.pattern).put("a", it.action)) } })
            .put(
                "appRules",
                JSONArray().apply {
                    rules.allAppRules().forEach {
                        put(JSONObject().put("pkg", it.packageName).put("policy", it.policy).put("night", it.nightAllowed).put("saver", it.dataSaver))
                    }
                },
            )
            .put("homelab", JSONArray().apply { homelab.all().forEach { put(JSONObject().put("name", it.name).put("url", it.url)) } })
        if (includeProfiles) {
            root.put(
                "wireguard",
                JSONArray().apply {
                    profiles.all().forEach { p ->
                        profiles.rawConfig(p.id)?.let { put(JSONObject().put("name", p.name).put("conf", it)) }
                    }
                },
            )
        }
        val enc = BackupCrypto.encrypt(root.toString().toByteArray(), password)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(enc) } ?: error("Datei nicht beschreibbar")
        events.info(EventType.BACKUP, "Sicherung erstellt${if (includeProfiles) " (mit VPN-Profilen)" else ""}")
    }

    suspend fun import(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Datei nicht lesbar")
        val root = JSONObject(String(BackupCrypto.decrypt(data, password)))
        root.optJSONObject("settings")?.let { o ->
            settings.update { s ->
                s.copy(
                    tunnelMode = enumOr(o.optString("tunnelMode"), s.tunnelMode),
                    upstream = enumOr(o.optString("upstream"), s.upstream),
                    customDohUrl = o.optString("customDohUrl", s.customDohUrl),
                    customDohBootstrap = o.optString("customDohBootstrap", s.customDohBootstrap),
                    blockResponse = enumOr(o.optString("blockResponse"), s.blockResponse),
                    enabledLists = o.optJSONArray("enabledLists")?.strings()?.toSet() ?: s.enabledLists,
                    listsAutoUpdate = o.optBoolean("listsAutoUpdate", s.listsAutoUpdate),
                    listsWifiOnly = o.optBoolean("listsWifiOnly", s.listsWifiOnly),
                    retentionQueriesDays = o.optInt("retentionQueriesDays", s.retentionQueriesDays),
                    retentionStatsDays = o.optInt("retentionStatsDays", s.retentionStatsDays),
                    retentionEventsDays = o.optInt("retentionEventsDays", s.retentionEventsDays),
                    noHistory = o.optBoolean("noHistory", s.noHistory),
                    killSwitch = o.optBoolean("killSwitch", s.killSwitch),
                    autoVpnEnabled = o.optBoolean("autoVpnEnabled", s.autoVpnEnabled),
                    homeSsids = o.optJSONArray("homeSsids")?.strings()?.toSet() ?: s.homeSsids,
                    vpnOnOpenWifi = o.optBoolean("vpnOnOpenWifi", s.vpnOnOpenWifi),
                    vpnOnUnknownWifi = o.optBoolean("vpnOnUnknownWifi", s.vpnOnUnknownWifi),
                    vpnOnMobile = o.optBoolean("vpnOnMobile", s.vpnOnMobile),
                    nightModeEnabled = o.optBoolean("nightModeEnabled", s.nightModeEnabled),
                    nightStartMinutes = o.optInt("nightStartMinutes", s.nightStartMinutes),
                    nightEndMinutes = o.optInt("nightEndMinutes", s.nightEndMinutes),
                    dataSaverEnabled = o.optBoolean("dataSaverEnabled", s.dataSaverEnabled),
                    weeklyReportEnabled = o.optBoolean("weeklyReportEnabled", s.weeklyReportEnabled),
                )
            }
        }
        root.optJSONArray("customRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                rules.upsertCustom(CustomRuleEntity(pattern = o.getString("p"), action = o.getString("a"), createdAt = System.currentTimeMillis()))
            }
        }
        val pm = context.packageManager
        root.optJSONArray("appRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.getString("pkg")
                val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: continue
                rules.upsertAppRule(AppRuleEntity(pkg, uid, o.getString("policy"), o.optBoolean("night"), o.optBoolean("saver")))
            }
        }
        root.optJSONArray("homelab")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                homelab.upsert(HomelabTileEntity(name = o.getString("name"), url = o.getString("url"), position = i))
            }
        }
        root.optJSONArray("wireguard")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                runCatching { profiles.import(o.getString("name"), o.getString("conf")) }
            }
        }
        events.info(EventType.BACKUP, "Sicherung wiederhergestellt")
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private inline fun <reified T : Enum<T>> enumOr(v: String?, d: T): T = enumValues<T>().firstOrNull { it.name == v } ?: d

    @Suppress("unused")
    private val keepTypes = listOf(TunnelMode.DNS_ONLY, DohUpstream.QUAD9, BlockResponse.ZERO_IP)
}
