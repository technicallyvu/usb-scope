package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.jpegBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UvcPayloadParserTest {

    private val capacity = 16384

    /** One UVC payload: a [hdrLen]-byte header (bHeaderLength, bmHeaderInfo, rest zero) plus [data]. */
    private fun payload(flags: Int, data: ByteArray = ByteArray(0), hdrLen: Int = 12): ByteArray {
        val out = ByteArray(hdrLen + data.size)
        out[0] = hdrLen.toByte()
        out[1] = flags.toByte()
        data.copyInto(out, hdrLen)
        return out
    }

    private fun UvcPayloadParser.feed(bytes: ByteArray, chunkCapacity: Int = capacity, now: Long = 7L): List<Frame> =
        accept(bytes, bytes.size, chunkCapacity, now)

    private fun mjpeg(width: Int = 4, height: Int = 4, maxPayload: Int = capacity) =
        UvcPayloadParser(UvcFormatKind.MJPEG, width, height, maxPayload)

    @Test
    fun `two payloads make one frame and EOF ends it`() {
        val p = mjpeg()
        val a = TestPackets.fakeJpeg(40)
        val b = TestPackets.fakeJpeg(60)

        assertTrue(p.feed(payload(FID0, a.copyOfRange(0, 20))).isEmpty())
        val first = p.feed(payload(FID0 or EOF, a.copyOfRange(20, a.size)))
        assertEquals(1, first.size)
        assertArrayEquals(a, first.single().jpegBytes())

        val second = p.feed(payload(FID1 or EOF, b))
        assertEquals(1, second.size)
        assertArrayEquals(b, second.single().jpegBytes())

        assertEquals(2, p.framesEmitted)
        assertEquals(0, p.framesDropped)
        assertEquals(7L, second.single().timestampNanos)
    }

    @Test
    fun `the error bit drops the frame and counts it`() {
        val p = mjpeg()
        assertTrue(p.feed(payload(FID0 or ERR or EOF, TestPackets.fakeJpeg(40))).isEmpty())
        assertEquals(0, p.framesEmitted)
        assertEquals(1, p.framesDropped)

        // The error state does not stick to the next frame.
        val good = TestPackets.fakeJpeg(40)
        assertArrayEquals(good, p.feed(payload(FID1 or EOF, good)).single().jpegBytes())
        assertEquals(1, p.framesEmitted)
    }

    @Test
    fun `a FID toggle without EOF still emits the previous frame`() {
        val p = mjpeg()
        val a = TestPackets.fakeJpeg(40)
        assertTrue(p.feed(payload(FID0, a)).isEmpty())
        val emitted = p.feed(payload(FID1, TestPackets.fakeJpeg(10)))
        assertArrayEquals(a, emitted.single().jpegBytes())
    }

    @Test
    fun `a payload spanning several reads is parsed once and keeps every data byte`() {
        val p = mjpeg(maxPayload = 4096)
        val data = TestPackets.fakeJpeg(2984)
        assertEquals(2988, data.size)
        val bytes = payload(FID0, data)
        assertEquals(3000, bytes.size)

        // Reads of 1024, 1024, 952 at a chunk capacity of 1024: only the first carries a header.
        var offset = 0
        for (size in listOf(1024, 1024, 952)) {
            val chunk = bytes.copyOfRange(offset, offset + size)
            assertTrue(p.accept(chunk, size, 1024, 7L).isEmpty())
            offset += size
        }

        val emitted = p.accept(payload(FID1), 12, 1024, 9L)
        assertEquals(2988, emitted.single().jpegBytes().size)
        assertArrayEquals(data, emitted.single().jpegBytes())
        assertEquals(3012, p.bytesReceived)
    }

    @Test
    fun `non-jpeg bytes are dropped for an MJPEG format`() {
        val p = mjpeg()
        assertTrue(p.feed(payload(FID0 or EOF, ByteArray(40) { 0x11 })).isEmpty())
        assertEquals(1, p.framesDropped)
    }

    @Test
    fun `YUY2 frames must be exactly width times height times two`() {
        val p = UvcPayloadParser(UvcFormatKind.YUY2, 4, 4, capacity)

        assertTrue(p.feed(payload(FID0 or EOF, ByteArray(16))).isEmpty())
        assertEquals(1, p.framesDropped)

        val frame = p.feed(payload(FID1 or EOF, ByteArray(32) { 0x40 })).single()
        val data = frame.data as FrameData.Yuyv422
        assertEquals(4, data.width)
        assertEquals(4, data.height)
        assertEquals(32, data.bytes.size)
        assertEquals(1, p.framesEmitted)
    }

    @Test
    fun `a malformed header is counted and the stream resynchronises`() {
        val p = mjpeg()
        // bHeaderLength 0 (below the two mandatory bytes) and bHeaderLength past the end of the read.
        assertTrue(p.feed(byteArrayOf(0x00, 0x00, 0x11, 0x11)).isEmpty())
        assertTrue(p.feed(byteArrayOf(0x20, 0x00, 0x11, 0x11)).isEmpty())
        assertEquals(2, p.framesDropped)

        val good = TestPackets.fakeJpeg(40)
        assertArrayEquals(good, p.feed(payload(FID0 or EOF, good)).single().jpegBytes())
        assertEquals(1, p.framesEmitted)
    }

    private companion object {
        const val FID0 = 0x00
        const val FID1 = 0x01
        const val EOF = 0x02
        const val ERR = 0x40
    }
}
