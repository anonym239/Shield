package dev.shield.core.checks

import dev.shield.logging.Redactor
import org.json.JSONArray
import org.json.JSONObject

enum class AiStatus { OK, WARNING, PROBLEM }

/** The only actions the AI may trigger – none of them can weaken the protection. */
enum class AiAction { RESTART_TUNNEL, UPDATE_LISTS, RUN_SELFTEST, NONE }

data class AiVerdict(val time: Long, val status: AiStatus, val summary: String, val actions: List<AiAction>) {
    fun toJson(): String = JSONObject()
        .put("time", time).put("status", status.name).put("summary", summary)
        .put("actions", JSONArray(actions.map { it.name })).toString()

    companion object {
        fun fromJson(s: String?): AiVerdict? = s?.let {
            runCatching {
                val o = JSONObject(it)
                AiVerdict(
                    o.getLong("time"), AiStatus.valueOf(o.getString("status")), o.getString("summary"),
                    (0 until o.getJSONArray("actions").length()).map { i -> AiAction.valueOf(o.getJSONArray("actions").getString(i)) },
                )
            }.getOrNull()
        }
    }
}

/** Anonymous health data sent to the AI. Contains no domains, IPs or keys. */
data class HealthSnapshot(
    val androidVersion: Int,
    val appVersion: String,
    val protectionEnabled: Boolean,
    val tunnelPhase: String,
    val tunnelMode: String,
    val protectionLevel: String,
    val wgWanted: Boolean,
    val wgConnected: Boolean,
    val handshakeAgeSec: Long,
    val listsRules: Int,
    val listsAgeHours: Long,
    val listErrors: Int,
    val selfTest: List<Triple<String, Boolean?, String>>,
    val eventCounts: Map<String, Int>,
    val recentProblems: List<String>,
    val score: Int,
    val findings: List<String>,
)

/** Builds the Gemini request and parses the answer (pure, unit tested). */
object AiProtocol {
    private val ipRe = Regex("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b|\\b([0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\\b")
    private val domainRe = Regex("\\b([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}\\b")

    const val SYSTEM_PROMPT = """Du bist der Diagnose-Assistent der Android-Sicherheits-App "Shield" (DNS-Blocker, Firewall, WireGuard-VPN).
Du bekommst eine anonymisierte Zustandsübersicht der letzten 24 Stunden.
Beurteile, ob der Schutz zuverlässig läuft. Antworte NUR als JSON mit genau diesen Feldern:
{"status": "OK" | "WARNING" | "PROBLEM",
 "summary": "höchstens 3 kurze Sätze auf Deutsch, verständlich für Laien, ohne Fachbegriffe",
 "actions": Liste aus "RESTART_TUNNEL", "UPDATE_LISTS", "RUN_SELFTEST" oder leer}
Wähle Aktionen nur, wenn sie ein konkretes erkanntes Problem beheben. Behaupte nie, etwas sei 100 % sicher."""

    /** Removes everything that could identify visited sites, addresses or secrets. */
    fun sanitize(text: String): String =
        domainRe.replace(ipRe.replace(Redactor.redact(text), "<ip>"), "<domain>").take(300)

    fun prompt(h: HealthSnapshot): String = JSONObject()
        .put("android_api", h.androidVersion)
        .put("app_version", h.appVersion)
        .put("protection_enabled", h.protectionEnabled)
        .put("tunnel", JSONObject().put("phase", h.tunnelPhase).put("mode", h.tunnelMode).put("level", h.protectionLevel))
        .put(
            "vpn",
            JSONObject().put("profile_active", h.wgWanted).put("connected", h.wgConnected).put("handshake_age_s", h.handshakeAgeSec),
        )
        .put("blocklists", JSONObject().put("rules", h.listsRules).put("age_hours", h.listsAgeHours).put("errors", h.listErrors))
        .put(
            "self_test",
            JSONArray().apply {
                h.selfTest.forEach { (id, ok, detail) ->
                    put(JSONObject().put("test", id).put("passed", ok ?: JSONObject.NULL).put("detail", sanitize(detail)))
                }
            },
        )
        .put("events_24h", JSONObject(h.eventCounts))
        .put("recent_problems", JSONArray(h.recentProblems.take(20).map { sanitize(it) }))
        .put("security_score", h.score)
        .put("findings", JSONArray(h.findings))
        .toString()

    fun requestBody(prompt: String): String = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
        .put(
            "contents",
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))),
        )
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
        .toString()

    /** Parses a Gemini generateContent response. Unknown actions are dropped. */
    fun parse(body: String, now: Long = System.currentTimeMillis()): AiVerdict {
        val root = JSONObject(body)
        root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Gemini-Fehler")) }
        val text = root.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        val json = JSONObject(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val status = runCatching { AiStatus.valueOf(json.optString("status").uppercase()) }.getOrDefault(AiStatus.WARNING)
        val actions = json.optJSONArray("actions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> runCatching { AiAction.valueOf(arr.getString(i).uppercase()) }.getOrNull() }
        }.orEmpty().filter { it != AiAction.NONE }.distinct()
        val summary = json.optString("summary").replace("100 %", "voll").replace("100%", "voll").take(400)
        return AiVerdict(now, status, summary.ifBlank { "Keine Zusammenfassung erhalten." }, actions)
    }
}
