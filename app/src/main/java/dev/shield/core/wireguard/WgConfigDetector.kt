package dev.shield.core.wireguard

/** Recognises WireGuard configurations in files, shared text and the clipboard. */
object WgConfigDetector {
    /** Larger texts are never a WireGuard config (protects against huge clipboard contents). */
    const val MAX_SIZE = 64 * 1024

    fun looksLikeConfig(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length > MAX_SIZE) return false
        val t = text.lowercase()
        return "[interface]" in t && "privatekey" in t && "[peer]" in t && "publickey" in t
    }

    /**
     * Profile name from a file name like "wg-NL-FREE-12.conf" or "Proton%20DE#7.conf".
     * Falls back to "" (the repository then uses the endpoint).
     */
    fun nameFromFile(fileName: String?): String {
        if (fileName.isNullOrBlank()) return ""
        val raw = fileName.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: IllegalArgumentException) {
            raw // malformed %-escape: keep the name as it is
        }
        val base = decoded
            .removeSuffix(".conf").removeSuffix(".CONF")
            .replace(Regex("[^\\p{L}\\p{N} ._#+-]"), " ")
            .trim()
        // the downloads provider sometimes only exposes a numeric id
        return if (base.isEmpty() || base.all { it.isDigit() }) "" else base.take(40)
    }
}
