package dev.shield.core.checks

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMathTest {
    @Test
    fun mbps() {
        assertEquals(80.0, SpeedMath.mbps(10_000_000, 1_000_000_000), 0.001) // 10 MB in 1 s
        assertEquals(0.0, SpeedMath.mbps(10, 0), 0.0)
    }

    @Test
    fun median() {
        assertEquals(3L, SpeedMath.median(listOf(5, 1, 3)))
        assertEquals(25L, SpeedMath.median(listOf(10, 40, 20, 30)))
    }

    @Test
    fun display() {
        assertEquals("4,5", SpeedMath.display(4.54))
        assertEquals("87", SpeedMath.display(87.9))
    }
}
