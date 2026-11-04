package dev.shield.core.filter

import java.net.URI

/**
 * uBlock Origin lists are split into parts that the main file pulls in with `!#include name.txt`.
 * Only relative files next to the main list are followed (same host, no "..", one level deep),
 * so a list can never make Shield download from somewhere else.
 */
object ListIncludes {
    const val MAX_INCLUDES = 32
    private const val DIRECTIVE = "!#include "

    /** Names of the files included by [lines], in order, without duplicates. */
    fun find(lines: Sequence<String>): List<String> =
        lines.map { it.trim() }
            .filter { it.startsWith(DIRECTIVE) }
            .map { it.removePrefix(DIRECTIVE).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_INCLUDES)
            .toList()

    /** The URL of [name] next to [base], or null if [name] is not a plain relative file. */
    fun resolve(base: String, name: String): String? {
        if (name.contains("..") || name.contains("://") || name.startsWith("/") || name.contains('\\')) return null
        if (!Regex("^[A-Za-z0-9._/-]+$").matches(name)) return null
        return try {
            val b = URI(base)
            if (b.scheme != "https") return null
            val r = b.resolve(name)
            if (r.host != b.host || r.scheme != "https") null else r.toString()
        } catch (e: java.net.URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
