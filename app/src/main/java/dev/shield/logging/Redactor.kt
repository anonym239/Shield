package dev.shield.logging

/** Removes secrets (WireGuard keys, tokens, passwords) from log text. */
object Redactor {
    private val patterns = listOf(
        // WireGuard keys in .conf or UAPI form
        Regex("(?i)(private_?key|preshared_?key)\\s*[=:]\\s*\\S+") to "$1=<redacted>",
        // generic tokens / passwords
        Regex("(?i)(token|password|passwort|secret|authorization)\\s*[=:]\\s*\\S+") to "$1=<redacted>",
        // 32-byte base64 keys (44 chars ending with '=')
        Regex("\\b[A-Za-z0-9+/]{42}[AEIMQUYcgkosw048]=") to "<key>",
        // 64 hex chars (UAPI keys)
        Regex("\\b[0-9a-fA-F]{64}\\b") to "<key>",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}
