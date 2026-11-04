package dev.shield.core.filter

/** Result of parsing one blocklist. */
data class ParsedList(val blocked: Set<String>, val allowed: Set<String>, val skippedLines: Int)

/**
 * Parses hosts files, plain domain lists and the AdGuard/ABP/uBlock Origin base syntax
 * (`||domain^`, `@@||domain^`). Rules with path parts or modifiers that a DNS filter
 * cannot apply (e.g. `$script`, cosmetic `##` rules, scriptlets) are skipped to avoid over-blocking.
 *
 * `abp = true` is for adblock-syntax lists (uBlock Origin, EasyList): there a bare token is a URL
 * fragment, not a host name, so only `||domain^` rules are taken.
 */
object BlocklistParser {
    private val hostsIps = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "::0")
    private val ignoredHosts = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters", "0.0.0.0",
    )
    private val allowedModifiers = setOf("important", "all", "dnstype=a", "dnstype=aaaa")
    /** Only for block rules: blocking the page itself (`$doc`) equals blocking the domain. */
    private val blockOnlyModifiers = setOf("doc", "document")
    /** Accepted in aggressive mode: a DNS filter cannot tell first- from third-party requests. */
    private val aggressiveModifiers = setOf("third-party", "3p")

    fun parse(lines: Sequence<String>, aggressive: Boolean = false, abp: Boolean = false): ParsedList {
        val blocked = HashSet<String>()
        val allowed = HashSet<String>()
        var skipped = 0
        for (raw in lines) {
            when (val r = parseLine(raw, aggressive, abp)) {
                is LineResult.Block -> blocked.add(r.domain)
                is LineResult.Allow -> allowed.add(r.domain)
                LineResult.Skip -> skipped++
                LineResult.Empty -> Unit
            }
        }
        return ParsedList(blocked, allowed, skipped)
    }

    fun parse(text: String, aggressive: Boolean = false, abp: Boolean = false): ParsedList =
        parse(text.lineSequence(), aggressive, abp)

    sealed interface LineResult {
        data class Block(val domain: String) : LineResult
        data class Allow(val domain: String) : LineResult
        data object Skip : LineResult
        data object Empty : LineResult
    }

    fun parseLine(raw: String, aggressive: Boolean = false, abp: Boolean = false): LineResult {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith('!') || line.startsWith('#') || line.startsWith('[')) {
            return LineResult.Empty
        }
        // inline comments in hosts files
        val hash = line.indexOf(" #")
        if (hash > 0) line = line.substring(0, hash).trim()

        if (line.startsWith("@@||")) {
            // exceptions are never widened in aggressive mode
            val d = parseAdguard(line.substring(4), false, exception = true) ?: return LineResult.Skip
            return LineResult.Allow(d)
        }
        if (line.startsWith("||")) {
            val d = parseAdguard(line.substring(2), aggressive, exception = false) ?: return LineResult.Skip
            return LineResult.Block(d)
        }
        if (line.startsWith("@@") || line.startsWith("/") || line.contains("##") || line.contains("#@#")) {
            return LineResult.Skip
        }
        if (abp) return LineResult.Skip
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
    private fun parseAdguard(rest: String, aggressive: Boolean, exception: Boolean): String? {
        var body = rest
        val dollar = body.indexOf('$')
        if (dollar >= 0) {
            val mods = body.substring(dollar + 1).split(',').map { it.trim().lowercase() }
            val ok = { m: String ->
                m in allowedModifiers || (!exception && m in blockOnlyModifiers) || (aggressive && m in aggressiveModifiers)
            }
            if (!mods.all(ok)) return null
            body = body.substring(0, dollar)
        }
        if (body.endsWith("^|")) body = body.dropLast(2)
        if (body.endsWith('^')) body = body.dropLast(1)
        if (body.endsWith('|')) body = body.dropLast(1)
        if (body.any { it == '/' || it == '*' || it == '^' || it == ':' || it == '?' || it == '=' }) return null
        return Domains.normalize(body)
    }
}
