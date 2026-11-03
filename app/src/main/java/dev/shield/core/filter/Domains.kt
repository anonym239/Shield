package dev.shield.core.filter

/** Helpers for domain names. */
object Domains {
    private val labelRe = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")

    /** Lower-cases, strips a trailing dot and validates. Returns null if invalid. */
    fun normalize(input: String): String? {
        var d = input.trim().lowercase()
        if (d.endsWith('.')) d = d.dropLast(1)
        if (d.isEmpty() || d.length > 253) return null
        val labels = d.split('.')
        if (labels.size < 2) return null
        if (labels.any { !labelRe.matches(it) }) return null
        return d
    }

    /** "a.b.c.com" -> ["a.b.c.com", "b.c.com", "c.com", "com"] */
    inline fun forEachSuffix(domain: String, action: (String) -> Boolean): Boolean {
        var start = 0
        while (true) {
            if (action(if (start == 0) domain else domain.substring(start))) return true
            val dot = domain.indexOf('.', start)
            if (dot < 0) return false
            start = dot + 1
        }
    }
}
