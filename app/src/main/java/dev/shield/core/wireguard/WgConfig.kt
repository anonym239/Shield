package dev.shield.core.wireguard

import java.util.Base64

class WgConfigException(message: String) : Exception(message)

data class WgInterface(
    val privateKey: String,
    val addresses: List<String>,
    val dns: List<String>,
    val mtu: Int?,
)

data class WgPeer(
    val publicKey: String,
    val presharedKey: String?,
    val endpoint: String?,
    val allowedIps: List<String>,
    val keepalive: Int?,
)

data class WgConfig(val iface: WgInterface, val peers: List<WgPeer>) {
    val endpointSummary: String get() = peers.firstOrNull()?.endpoint ?: "-"

    /** Host part of the first endpoint ("[v6]:port" and "host:port"). */
    fun endpointHost(): String? = peers.firstOrNull()?.endpoint?.let { WgConfigParser.splitHostPort(it)?.first }

    /**
     * UAPI configuration for wireguard-go. [resolvedEndpoints] maps the original endpoint to
     * "ip:port" (UAPI does not resolve host names).
     */
    fun toUapi(resolvedEndpoints: Map<String, String> = emptyMap()): String = buildString {
        append("private_key=").append(WgConfigParser.keyToHex(iface.privateKey)).append('\n')
        append("replace_peers=true\n")
        peers.forEach { p ->
            append("public_key=").append(WgConfigParser.keyToHex(p.publicKey)).append('\n')
            p.presharedKey?.let { append("preshared_key=").append(WgConfigParser.keyToHex(it)).append('\n') }
            p.endpoint?.let { append("endpoint=").append(resolvedEndpoints[it] ?: it).append('\n') }
            p.keepalive?.let { append("persistent_keepalive_interval=").append(it).append('\n') }
            append("replace_allowed_ips=true\n")
            p.allowedIps.forEach { append("allowed_ip=").append(it).append('\n') }
        }
    }
}

/** Parser for wg-quick style .conf files (also used for QR codes). */
object WgConfigParser {

    fun parse(text: String): WgConfig {
        var section = ""
        var privateKey: String? = null
        val addresses = mutableListOf<String>()
        val dns = mutableListOf<String>()
        var mtu: Int? = null
        val peers = mutableListOf<WgPeer>()
        var peer: MutableMap<String, String>? = null

        fun flushPeer() {
            val p = peer ?: return
            val pub = p["publickey"] ?: throw WgConfigException("Peer ohne PublicKey")
            validateKey(pub, "PublicKey")
            p["presharedkey"]?.let { validateKey(it, "PresharedKey") }
            val allowed = p["allowedips"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (allowed.isEmpty()) throw WgConfigException("Peer ohne AllowedIPs")
            peers += WgPeer(
                publicKey = pub,
                presharedKey = p["presharedkey"],
                endpoint = p["endpoint"]?.also { if (splitHostPort(it) == null) throw WgConfigException("Ungültiger Endpoint") },
                allowedIps = allowed,
                keepalive = p["persistentkeepalive"]?.let {
                    it.toIntOrNull()?.takeIf { v -> v in 0..65535 } ?: throw WgConfigException("Ungültiges PersistentKeepalive")
                },
            )
            peer = null
        }

        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                flushPeer()
                section = line.substring(1, line.length - 1).trim().lowercase()
                if (section == "peer") peer = mutableMapOf()
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) throw WgConfigException("Ungültige Zeile: ${line.take(20)}")
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            when (section) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "dns" -> dns += value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "mtu" -> mtu = value.toIntOrNull()?.takeIf { it in 576..9000 } ?: throw WgConfigException("Ungültige MTU")
                    else -> Unit // ListenPort, PostUp ... are ignored
                }
                "peer" -> peer!![key] = value
                else -> throw WgConfigException("Eintrag außerhalb von [Interface]/[Peer]")
            }
        }
        flushPeer()
        val pk = privateKey ?: throw WgConfigException("PrivateKey fehlt")
        validateKey(pk, "PrivateKey")
        if (addresses.isEmpty()) throw WgConfigException("Address fehlt")
        if (peers.isEmpty()) throw WgConfigException("Kein [Peer]")
        return WgConfig(WgInterface(pk, addresses, dns, mtu), peers)
    }

    private fun validateKey(k: String, name: String) {
        val bytes = runCatching { Base64.getDecoder().decode(k) }.getOrNull()
        if (bytes == null || bytes.size != 32) throw WgConfigException("$name ist ungültig")
    }

    fun keyToHex(base64: String): String =
        Base64.getDecoder().decode(base64).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Splits "host:port" or "[v6]:port". */
    fun splitHostPort(ep: String): Pair<String, Int>? {
        val s = ep.trim()
        return if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0 || end + 1 >= s.length || s[end + 1] != ':') return null
            val port = s.substring(end + 2).toIntOrNull() ?: return null
            s.substring(1, end) to port
        } else {
            val c = s.lastIndexOf(':')
            if (c <= 0) return null
            val port = s.substring(c + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            s.substring(0, c) to port
        }
    }

    fun joinHostPort(host: String, port: Int) = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
