package dev.shield.core.vpn

/** A country a VPN server can be in. Data: scripts/countries.tsv (generated into CountryData.kt). */
data class Country(val code: String, val nameDe: String, val nameEn: String, val aliases: List<String>) {
    /** Flag emoji built from the regional indicator letters (no image download needed). */
    val flag: String get() = Countries.flag(code)
}

/** Server name suggestion for a newly imported config. */
data class ServerGuess(val country: Country?, val name: String)

object Countries {
    val all: List<Country> = COUNTRY_DATA.sortedBy { it.nameDe }
    private val byCode = COUNTRY_DATA.associateBy { it.code }

    /** Proton Secure Core entry countries: in "CH-NL#1" the exit (NL) is where the traffic leaves. */
    private val secureCoreEntries = setOf("CH", "IS", "SE")
    private val protonServer = Regex("""^#\s*([A-Z]{2})(?:-([A-Z]{2}))?(-FREE)?#(\d+)\s*$""")
    private val genericNames = setOf("document", "dokument", "download", "wireguard", "wg0", "config", "profile", "vpn", "proton")

    fun byCode(code: String?): Country? = code?.uppercase()?.let { byCode[it] }

    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return "🏳"
        val base = 0x1F1E6 - 'A'.code
        return code.uppercase().map { String(Character.toChars(base + it.code)) }.joinToString("")
    }

    /**
     * Finds the country in a name like "Niederlande #1", "Netherlands", "wg-NL-FREE-12" or "NL".
     * Country names match as whole words (any case); two-letter codes only in capitals or as file-name parts.
     */
    fun detect(text: String?): Country? {
        if (text.isNullOrBlank()) return null
        val lower = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
        // longest names first, so "Südkorea" wins over "Korea" and "Saudi-Arabien" over shorter names
        val candidates = COUNTRY_DATA.flatMap { c -> (listOf(c.nameDe, c.nameEn) + c.aliases).map { it to c } }
            .sortedByDescending { it.first.length }
        for ((name, c) in candidates) {
            val n = " " + name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
            if (n.isNotBlank() && n in lower) return c
        }
        val tokens = text.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        val fileLike = text.contains('-') || text.contains('_') || text.endsWith(".conf", ignoreCase = true)
        for (t in tokens) {
            if (t.length == 2 && (t.all { it.isUpperCase() } || fileLike)) byCode(t)?.let { return it }
        }
        return null
    }

    /** Country and a readable name from the server comment Proton writes into its configs ("# NL-FREE#12"). */
    fun fromProtonComment(config: String): ServerGuess? {
        for (line in config.lineSequence()) {
            val m = protonServer.matchEntire(line.trim()) ?: continue
            val (first, second, free, number) = m.destructured
            val secureCore = second.isNotEmpty() && first in secureCoreEntries && byCode(second) != null
            val c = byCode(if (secureCore) second else first) ?: continue
            val suffix = when {
                secureCore -> " (Secure Core)"
                free.isNotEmpty() -> " (Free)"
                else -> ""
            }
            return ServerGuess(c, "${c.nameDe} #$number$suffix")
        }
        return null
    }

    /**
     * Best guess for a new profile: Proton's server comment, then the file name, then the endpoint.
     * A meaningless file name ("Dokument", "1234") is never used as the profile name.
     */
    fun guess(fileName: String, config: String, endpoint: String): ServerGuess {
        fromProtonComment(config)?.let { return it }
        val useful = fileName.isNotBlank() && fileName.lowercase().trim() !in genericNames && !fileName.all { it.isDigit() }
        val c = detect(fileName)
        return when {
            useful -> ServerGuess(c, fileName)
            c != null -> ServerGuess(c, c.nameDe)
            else -> ServerGuess(null, endpoint.substringBeforeLast(':').ifBlank { "VPN" })
        }
    }
}
