package dev.shield.core.checks

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProtocolTest {
    private val snap = HealthSnapshot(
        36, "1.1.0", true, "RUNNING", "FULL", "STRICT", true, false, 400, 120000, 3, 0,
        listOf(Triple("UPSTREAM", false, "DoH nicht erreichbar: dns.quad9.net/9.9.9.9 timeout")),
        mapOf("VPN_DISCONNECTED" to 2), listOf("WireGuard getrennt (Proton NL) 185.159.157.1"), 80, listOf("DNS_LEAK"),
    )

    @Test
    fun promptContainsNoDomainsOrIps() {
        val p = AiProtocol.prompt(snap)
        assertFalse(p.contains("quad9"))
        assertFalse(p.contains("9.9.9.9"))
        assertFalse(p.contains("185.159.157.1"))
        assertTrue(p.contains("VPN_DISCONNECTED"))
        assertTrue(JSONObject(AiProtocol.requestBody(p)).has("systemInstruction"))
    }

    @Test
    fun keysAreRedacted() {
        assertFalse(AiProtocol.sanitize("key AIzaSyA1234567890abcdefghijklmnopqrstuv").contains("AIzaSy"))
    }

    @Test
    fun parsesAnswerAndDropsUnknownActions() {
        val inner = """{"status":"problem","summary":"Das VPN war 2x getrennt. Alles ist 100 % sicher.","actions":["RESTART_TUNNEL","DISABLE_FIREWALL","NONE"]}"""
        val body = JSONObject().put(
            "candidates",
            org.json.JSONArray().put(JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", inner))))),
        ).toString()
        val v = AiProtocol.parse(body, 5)
        assertEquals(AiStatus.PROBLEM, v.status)
        assertEquals(listOf(AiAction.RESTART_TUNNEL), v.actions)
        assertFalse(v.summary.contains("100"))
        assertEquals(v, AiVerdict.fromJson(v.toJson()))
    }

    @Test(expected = IllegalStateException::class)
    fun apiErrorIsReported() {
        AiProtocol.parse("""{"error":{"code":400,"message":"API key not valid"}}""")
    }
}
