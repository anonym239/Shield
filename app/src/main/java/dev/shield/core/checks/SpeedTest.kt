package dev.shield.core.checks

import dev.shield.data.repo.SpeedResult
import dev.shield.logging.EventLogger
import dev.shield.logging.EventType
import dev.shield.logging.ShieldLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Pure helpers of the speed test (unit-tested). */
object SpeedMath {
    /** Megabit per second for [bytes] transferred in [nanos]. */
    fun mbps(bytes: Long, nanos: Long): Double = if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1e9) / 1_000_000.0

    fun median(values: List<Long>): Long {
        require(values.isNotEmpty())
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Rounded for display: 0.0 – 9.9 with one decimal, above that whole numbers. */
    fun display(mbps: Double): String = if (mbps < 10) String.format(java.util.Locale.GERMANY, "%.1f", mbps) else mbps.toLong().toString()
}

/**
 * User-triggered speed test through the current connection (the active VPN server) against Cloudflare's
 * public speed test endpoints. Uses about 35 MB of data; nothing is sent besides random test bytes.
 */
@Singleton
class SpeedTest @Inject constructor(http: OkHttpClient, private val events: EventLogger) {
    private val client = http.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun run(): SpeedResult = withContext(Dispatchers.IO) {
        events.info(EventType.NETWORK_ACCESS, "Geschwindigkeitstest (speed.cloudflare.com)")
        try {
            val ping = SpeedMath.median(List(5) { timeRequest("$BASE/__down?bytes=0") })
            val down = download()
            val up = upload()
            SpeedResult(down, up, (ping / 1_000_000).toInt(), System.currentTimeMillis())
        } catch (e: IOException) {
            ShieldLog.e("Speed", "speed test failed", e)
            events.warn(EventType.CHECK_RESULT, "Geschwindigkeitstest fehlgeschlagen: ${e.message}")
            throw e
        }
    }

    private fun timeRequest(url: String): Long {
        val t0 = System.nanoTime()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.bytes()
        }
        return System.nanoTime() - t0
    }

    /** Reads up to 25 MB for at most 8 s. */
    private fun download(): Double {
        val req = Request.Builder().url("$BASE/__down?bytes=$DOWN_BYTES").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val input = r.body?.byteStream() ?: throw IOException("leere Antwort")
            val buf = ByteArray(64 * 1024)
            var total = 0L
            val t0 = System.nanoTime()
            while (System.nanoTime() - t0 < MAX_NANOS) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
            return SpeedMath.mbps(total, System.nanoTime() - t0)
        }
    }

    /** Sends 10 MB of random bytes. */
    private fun upload(): Double {
        val chunk = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = UP_BYTES
            override fun writeTo(sink: BufferedSink) {
                var left = UP_BYTES
                while (left > 0) {
                    val n = minOf(left, chunk.size.toLong()).toInt()
                    sink.write(chunk, 0, n)
                    left -= n
                }
            }
        }
        val t0 = System.nanoTime()
        client.newCall(Request.Builder().url("$BASE/__up").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        }
        return SpeedMath.mbps(UP_BYTES, System.nanoTime() - t0)
    }

    companion object {
        private const val BASE = "https://speed.cloudflare.com"
        private const val DOWN_BYTES = 25_000_000L
        private const val UP_BYTES = 10_000_000L
        private const val MAX_NANOS = 8_000_000_000L
    }
}
