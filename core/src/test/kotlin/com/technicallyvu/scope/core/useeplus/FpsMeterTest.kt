package com.technicallyvu.scope.core.useeplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FpsMeterTest {
    private val ms = 1_000_000L

    @Test
    fun `zero until two samples`() {
        val m = FpsMeter()
        assertEquals(0.0, m.tick(0))
    }

    @Test
    fun `ten frames per second`() {
        val m = FpsMeter(windowNanos = 2_000 * ms)
        var fps = 0.0
        for (i in 0..10) fps = m.tick(i * 100 * ms)
        assertEquals(10.0, fps, 0.01)
    }

    @Test
    fun `old samples fall out of the window`() {
        val m = FpsMeter(windowNanos = 1_000 * ms)
        m.tick(0)
        m.tick(100 * ms)
        val fps = m.tick(5_000 * ms)   // only the last two samples remain: 4.9 s apart
        assertEquals(1.0 / 4.9, fps, 0.01)
    }
}
