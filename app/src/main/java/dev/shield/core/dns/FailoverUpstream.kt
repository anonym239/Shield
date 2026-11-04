package dev.shield.core.dns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tries the primary encrypted upstream and falls back to a second encrypted upstream when it fails.
 * After a failure the primary is skipped for [cooldownMs] so DNS stays fast. Never falls back to
 * plaintext DNS.
 */
class FailoverUpstream(
    private val primary: DnsUpstream,
    private val fallback: DnsUpstream?,
    private val cooldownMs: Long = 60_000,
    /** A hanging primary must not exceed Android's resolver timeout (~5 s). */
    private val primaryTimeoutMs: Long = 2_500,
    private val clock: () -> Long = System::currentTimeMillis,
) : DnsUpstream {
    @Volatile
    private var primaryDownUntil = 0L

    override suspend fun resolve(query: ByteArray): ByteArray {
        val fb = fallback ?: return primary.resolve(query)
        if (clock() >= primaryDownUntil) {
            try {
                val answer = withTimeoutOrNull(primaryTimeoutMs) { primary.resolve(query) }
                if (answer != null) return answer
                primaryDownUntil = clock() + cooldownMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                primaryDownUntil = clock() + cooldownMs
            }
        }
        return try {
            fb.resolve(query)
        } catch (e: Exception) {
            // both down: try the primary once more before giving up
            primaryDownUntil = 0
            primary.resolve(query)
        }
    }
}
