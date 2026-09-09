package com.technicallyvu.scope.core.i4season

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class I4seasonInfoBlockTest {
    @Test
    fun `parses the real info block`() {
        val info = I4seasonInfoBlock.parse(I4seasonTestFrames.realInfo(), 480)
        assertEquals("i4season", info.vendor)
        assertEquals("su4p-002", info.product)
        assertEquals("5.0.13", info.firmware)
        assertEquals(320, info.width)
        assertEquals(240, info.height)
    }

    @Test
    fun `falls back to 320x240 when the block is short or implausible`() {
        val short = I4seasonInfoBlock.parse(ByteArray(10), 10)
        assertEquals(320, short.width); assertEquals(240, short.height); assertEquals("", short.vendor)
        val zero = I4seasonInfoBlock.parse(I4seasonTestFrames.info(0, 0), 480)
        assertEquals(320, zero.width); assertEquals(240, zero.height)
        val huge = I4seasonInfoBlock.parse(I4seasonTestFrames.info(9000, 9000), 480)
        assertEquals(320, huge.width); assertEquals(240, huge.height)
    }
}
