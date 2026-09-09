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
        val stream = ByteArray(6) { 0x01 } + I4seasonTestFrames.frame(w, h, fill = 0x44)
        val frames = feed(p, stream, 8)   // first read ends with DD CC, second starts with 01 00
        assertEquals(1, frames.size)
    }

    @Test
    fun `a false magic inside a desynchronised stream does not produce a frame`() {
        val p = I4seasonFrameParser(w, h)
        val garbage = ByteArray(200) { 0x00 }
        // A candidate "frame" that starts with a real magic but whose trailer is not followed by
        // a real magic: the parser must not lock onto it.
        val falseHeader = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0x01, 0x00) + ByteArray(507) { 0xAA.toByte() }
        val falsePayload = ByteArray(16) { 0xBB.toByte() }
        val filler = ByteArray(8) { 0x00 }   // ensures the bytes right after the false frame are not the magic
        val real = I4seasonTestFrames.frame(w, h, fill = 0x33)
        val stream = garbage + falseHeader + falsePayload + filler + real
        val frames = feed(p, stream, 64)
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(16) { 0x33 }, (frames[0].data as FrameData.Yuyv422).bytes)
        assertTrue(p.framesDropped >= 1)
    }

    @Test
    fun `a header with a different type byte is not treated as a frame start`() {
        val p = I4seasonFrameParser(w, h)
        val corrupted = I4seasonTestFrames.frame(w, h, fill = 0x77)
        corrupted[2] = 0x02.toByte()   // byte 2 of the header no longer matches the magic's type byte
        val real = I4seasonTestFrames.frame(w, h, fill = 0x88.toByte())
        val frames = feed(p, corrupted + real, 64)
        assertEquals(1, frames.size)
        assertArrayEquals(ByteArray(16) { 0x88.toByte() }, (frames[0].data as FrameData.Yuyv422).bytes)
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
