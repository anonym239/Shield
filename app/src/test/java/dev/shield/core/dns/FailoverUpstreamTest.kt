package dev.shield.core.dns

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class FailoverUpstreamTest {
    private class Up(val tag: Byte) : DnsUpstream {
        var fail = false
        var calls = 0
        override suspend fun resolve(query: ByteArray): ByteArray {
            calls++
            if (fail) throw IOException("down")
            return byteArrayOf(tag)
        }
    }

    @Test
    fun fallsBackAndSkipsPrimaryDuringCooldown() = runBlocking {
        var now = 0L
        val a = Up(1).apply { fail = true }
        val b = Up(2)
        val f = FailoverUpstream(a, b, cooldownMs = 1000, clock = { now })
        assertEquals(2, f.resolve(ByteArray(0))[0].toInt())
        assertEquals(2, f.resolve(ByteArray(0))[0].toInt())
        assertEquals(1, a.calls) // skipped during cooldown
        a.fail = false
        now = 2000
        assertEquals(1, f.resolve(ByteArray(0))[0].toInt()) // primary back after cooldown
    }

    @Test
    fun hangingPrimaryFallsBackQuickly() = runBlocking {
        val hanging = object : DnsUpstream {
            override suspend fun resolve(query: ByteArray): ByteArray {
                kotlinx.coroutines.delay(60_000)
                return byteArrayOf(1)
            }
        }
        val start = System.currentTimeMillis()
        val r = FailoverUpstream(hanging, Up(2), primaryTimeoutMs = 200).resolve(ByteArray(0))
        assertEquals(2, r[0].toInt())
        assert(System.currentTimeMillis() - start < 2000)
    }

    @Test(expected = IOException::class)
    fun bothDownThrows() = runBlocking<Unit> {
        FailoverUpstream(Up(1).apply { fail = true }, Up(2).apply { fail = true }).resolve(ByteArray(0))
    }
}
