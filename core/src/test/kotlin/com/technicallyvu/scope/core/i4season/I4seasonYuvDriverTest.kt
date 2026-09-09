package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class I4seasonYuvDriverTest {
    private val slept = mutableListOf<Long>()
    private fun driver(sink: PacketSink? = null) =
        I4seasonYuvDriver(clock = { 42L }, sleep = { slept += it }, ioDispatcher = Dispatchers.Default, packetSink = sink)

    private fun transport(frames: Int, w: Int = 4, h: Int = 2, buttonOn: Set<Int> = emptySet()): ReplayTransport {
        val stream = (1..frames).fold(ByteArray(0)) { acc, i ->
            acc + I4seasonTestFrames.frame(w, h, flags = if (i in buttonOn) 0x02 else 0, fill = i.toByte())
        }
        return ReplayTransport(I4seasonTestFrames.chunked(stream, 100), videoEndpoint = I4seasonYuvDriver.EP_IN).apply {
            controlResponses[0xA0 to 0x00] = I4seasonTestFrames.info(w, h)
        }
    }

    @Test
    fun `matches only the lone protocol-1 layout with a known id`() {
        val d = driver()
        val yuv = UsbInterfaceInfo(0, 0xFF, 0xF0, 1)
        val iap = UsbInterfaceInfo(0, 0xFF, 0xF0, 0)
        assertTrue(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(yuv))))
        assertTrue(d.matches(UsbDeviceInfo(0x0329, 0x2022, 0xEF, listOf(yuv))))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(iap, UsbInterfaceInfo(1, 0xFF, 0xF0, 1)))))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xEF)))              // layout unknown -> not ours
        assertFalse(d.matches(UsbDeviceInfo(0x1234, 0x0001, 0xEF, listOf(yuv))))
        assertEquals(180, d.defaultRotation)
    }

    @Test
    fun `handshake is claim, info, start`() {
        val t = transport(0)
        driver().open(t)
        assertEquals(listOf("claim 0", "ctrl A0 00 0005 0000 512", "ctrl 20 01 0005 0000 64"), t.calls)
    }

    @Test
    fun `frames carry YUYV data at the advertised size and the button flag`() {
        val t = transport(3, buttonOn = setOf(2))
        val frames = runBlocking { driver().open(t).frames.take(3).toList() }
        val d = frames[0].data as FrameData.Yuyv422
        assertEquals(4, d.width); assertEquals(2, d.height); assertEquals(16, d.bytes.size)
        assertEquals(listOf(false, true, false), frames.map { it.buttonPressed })
        assertEquals(42L, frames[0].timestampNanos)
    }

    @Test
    fun `stats count frames and bytes`() {
        val t = transport(3)
        val source = driver().open(t)
        runBlocking { source.frames.take(3).toList() }
        assertEquals(3, source.stats.value.framesEmitted)
        assertEquals(3L * (511 + 16), source.stats.value.bytesReceived)
    }

    @Test
    fun `missing info block falls back to 320x240`() {
        val t = ReplayTransport(I4seasonTestFrames.chunked(I4seasonTestFrames.frame(320, 240), 16384), videoEndpoint = I4seasonYuvDriver.EP_IN)
        val frame = runBlocking { driver().open(t).frames.take(1).toList() }.single()
        val d = frame.data as FrameData.Yuyv422
        assertEquals(320, d.width); assertEquals(240, d.height)
    }

    @Test
    fun `open retries with reset and gives up after three failures`() {
        val t = transport(0).apply { failuresBeforeSuccess = 3 }
        assertThrows(UsbException::class.java) { driver().open(t) }
        assertEquals(3, t.resets)
        assertEquals(listOf(1500L, 1500L, 1500L), slept)
    }

    @Test
    fun `stream ends with UsbException when the device goes away`() {
        val source = driver().open(transport(2))
        assertThrows(UsbException::class.java) { runBlocking { source.frames.toList() } }
    }

    @Test
    fun `close sends stop then releases and closes`() {
        val t = transport(0)
        driver().open(t).close()
        assertEquals(listOf("ctrl 20 02 0005 0000 0", "release 0", "close"), t.calls.takeLast(3))
    }

    @Test
    fun `packet sink sees raw reads`() {
        var n = 0
        val t = transport(2)
        runCatching { runBlocking { driver { _, _, _ -> n++ }.open(t).frames.toList() } }
        assertTrue(n > 0)
    }
}
