package dev.shield.core.filter

/** Result of parsing one blocklist. */
data class ParsedList(val blocked: Set<String>, val allowed: Set<String>, val skippedLines: Int)

/**
 * Parses hosts files, plain domain lists and the AdGuard/ABP base syntax
 * (`||domain^`, `@@||domain^`). Rules with path parts or modifiers that a DNS filter
 * cannot apply (e.g. `$third-party`) are skipped to avoid over-blocking.
 */
object BlocklistParser {
    private val hostsIps = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "::0")
    private val ignoredHosts = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "0.0.0.0",
    )
    private val allowedModifiers = setOf("important", "all", "dnstype=a", "dnstype=aaaa")

    fun parse(lines: Sequence<String>): ParsedList {
        val blocked = HashSet<String>()
        val allowed = HashSet<String>()
        var skipped = 0
        for (raw in lines) {
            when (val r = parseLine(raw)) {
                is LineResult.Block -> blocked.add(r.domain)
                is LineResult.Allow -> allowed.add(r.domain)
                LineResult.Skip -> skipped++
                LineResult.Empty -> Unit
            }
        }
        return ParsedList(blocked, allowed, skipped)
    }

    fun parse(text: String): ParsedList = parse(text.lineSequence())

    sealed interface LineResult {
        data class Block(val domain: String) : LineResult
        data class Allow(val domain: String) : LineResult
        data object Skip : LineResult
        data object Empty : LineResult
    }

    fun parseLine(raw: String): LineResult {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith('!') || line.startsWith('#') || line.startsWith('[')) {
            return LineResult.Empty
        }
        // inline comments in hosts files
        val hash = line.indexOf(" #")
        if (hash > 0) line = line.substring(0, hash).trim()

        if (line.startsWith("@@||")) {
            val d = parseAdguard(line.substring(4)) ?: return LineResult.Skip
            return LineResult.Allow(d)
        }
        if (line.startsWith("||")) {
            val d = parseAdguard(line.substring(2)) ?: return LineResult.Skip
            return LineResult.Block(d)
        }
        if (line.startsWith("@@") || line.startsWith("/") || line.contains("##") || line.contains("#@#")) {
            return LineResult.Skip
        }
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts[0] in hostsIps) {
            val host = parts[1]
            if (host in ignoredHosts) return LineResult.Empty
            val d = Domains.normalize(host) ?: return LineResult.Skip
            return LineResult.Block(d)
        }
        if (parts.size == 1) {
            var token = parts[0]
            if (token.startsWith("*.")) token = token.substring(2)
            if (token in ignoredHosts) return LineResult.Empty
            val d = Domains.normalize(token) ?: return LineResult.Skip
            return LineResult.Block(d)
        }
        return LineResult.Skip
    }

    /** Parses "domain^", "domain^$important", "domain" (after the leading ||). */
    private fun parseAdguard(rest: String): String? {
        var body = rest
        val dollar = body.indexOf('$')
        if (dollar >= 0) {
            val mods = body.substring(dollar + 1).split(',').map { it.trim().lowercase() }
            if (mods.any { it !in allowedModifiers }) return null
            body = body.substring(0, dollar)
        }
        if (body.endsWith("^|")) body = body.dropLast(2)
        if (body.endsWith('^')) body = body.dropLast(1)
        if (body.endsWith('|')) body = body.dropLast(1)
        if (body.any { it == '/' || it == '*' || it == '^' || it == ':' || it == '?' || it == '=' }) return null
        return Domains.normalize(body)
    }
}
