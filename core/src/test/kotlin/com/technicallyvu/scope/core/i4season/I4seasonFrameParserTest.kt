package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I4seasonFrameParserTest {
    private val w = 4
    private val h = 2   // payload 16 bytes, frame 527 bytes

    private fun feed(p: I4seasonFrameParser, stream: ByteArray, chunk: Int) =
        stream.toList().chunked(chunk).flatMap { c -> p.accept(c.toByteArray(), c.size, 5L) }

    @Test
    fun `reassembles frames across arbitrary read boundaries`() {
        val a = I4seasonTestFrames.frame(w, h, fill = 0x11)
        val b = I4seasonTestFrames.frame(w, h, fill = 0x22)
        val p = I4seasonFrameParser(w, h)
        val frames = feed(p, a + b, 100)
        assertEquals(2, frames.size)
        val d0 = frames[0].data as FrameData.Yuyv422
        assertEquals(w, d0.width); assertEquals(h, d0.height)
        assertArrayEquals(ByteArray(16) { 0x11 }, d0.bytes)
        assertArrayEquals(ByteArray(16) { 0x22 }, (frames[1].data as FrameData.Yuyv422).bytes)
        assertEquals(5L, frames[0].timestampNanos)
        assertEquals(2, p.framesEmitted); assertEquals(0, p.framesDropped)
        assertEquals((a.size + b.size).toLong(), p.bytesReceived)
    }

    @Test
    fun `button flag comes from header byte 7`() {
        val p = I4seasonFrameParser(w, h)
        val frames = feed(p, I4seasonTestFrames.frame(w, h, flags = 0) + I4seasonTestFrames.frame(w, h, flags = 0x02), 64)
        assertFalse(frames[0].buttonPressed)
        assertTrue(frames[1].buttonPressed)
    }

    @Test
    fun `resynchronises after garbage and counts one drop`() {
        val p = I4seasonFrameParser(w, h)
        val garbage = ByteArray(300) { 0x55 }
        val frames = feed(p, garbage + I4seasonTestFrames.frame(w, h, fill = 0x33), 50)
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(16) { 0x33 }, (frames[0].data as FrameData.Yuyv422).bytes)
        assertEquals(1, p.framesDropped)
    }

    @Test
    fun `a magic split across two reads is still found`() {
        val p = I4seasonFrameParser(w, h)
        val stream = ByteArray(7) { 0x01 } + I4seasonTestFrames.frame(w, h, fill = 0x44)
        val frames = feed(p, stream, 8)   // first read ends with DD, second starts with CC
        assertEquals(1, frames.size)
    }

    @Test
    fun `payload bytes that look like the magic do not break framing`() {
        val p = I4seasonFrameParser(w, h)
        val f = I4seasonTestFrames.frame(w, h, fill = 0xDD.toByte())
        f[511 + 1] = 0xCC.toByte()   // DD CC inside the payload
        val frames = feed(p, f + I4seasonTestFrames.frame(w, h, fill = 0x66), 33)
        assertEquals(2, frames.size)
        assertEquals(0, p.framesDropped)
    }
}
