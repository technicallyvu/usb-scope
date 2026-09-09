package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.TestPackets
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.fixture.LoggedPacket
import com.technicallyvu.scope.core.fixture.ReplayTransport
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UseeplusDriverTest {
    private val slept = mutableListOf<Long>()
    private var now = 0L
    private fun driver(sink: PacketSink? = null) = UseeplusDriver(
        clock = { now },
        sleep = { slept += it },
        ioDispatcher = Dispatchers.Default,
        packetSink = sink,
    )

    /** N synthetic frames as head+tail packets, each frame's bytes distinct, plus a trailing head to flush the last. */
    private fun stream(frameCount: Int, buttonOn: Set<Int> = emptySet()): List<LoggedPacket> {
        val packets = ArrayList<LoggedPacket>()
        var ts = 0L
        for (f in 1..frameCount) {
            val jpeg = TestPackets.SOI + ByteArray(30) { f.toByte() } + TestPackets.EOI
            val flags = if (f in buttonOn) UseeplusPacket.BUTTON_MASK else 0
            for (p in TestPackets.framePackets(f, jpeg, flags)) { packets += LoggedPacket(ts, p); ts += 1_000_000 }
        }
        packets += LoggedPacket(ts, TestPackets.packet(7, frameCount + 1, TestPackets.SOI))
        return packets
    }

    @Test
    fun `matches both supported ids and nothing else`() {
        val d = driver()
        assertTrue(d.matches(UsbDeviceInfo(0x2CE3, 0x3828, 0xFF)))
        assertTrue(d.matches(UsbDeviceInfo(0x0329, 0x2022, 0xFF)))
        assertFalse(d.matches(UsbDeviceInfo(0x2CE3, 0x0001, 0xFF)))
    }

    @Test
    fun `handshake issues the documented sequence`() {
        val t = ReplayTransport(emptyList())
        driver().open(t)
        assertEquals(
            listOf(
                "claim 0", "claim 1",
                "alt 1 1",
                "clearHalt 01",
                "write 02 FF 55 FF 55 EE 10",
                "write 01 BB AA 05 00 00",
            ),
            t.calls,
        )
    }

    @Test
    fun `open retries with a reset after a failure`() {
        val t = ReplayTransport(emptyList()).apply { failuresBeforeSuccess = 1 }
        driver().open(t)
        assertEquals(1, t.resets)
        assertEquals(listOf(UseeplusDriver.RESET_WAIT_MS), slept)
    }

    @Test
    fun `open gives up after three failures`() {
        val t = ReplayTransport(emptyList()).apply { failuresBeforeSuccess = 3 }
        assertThrows(UsbException::class.java) { driver().open(t) }
        assertEquals(3, t.resets)
    }

    @Test
    fun `discards the first two frames then emits the rest`() {
        val t = ReplayTransport(stream(5))
        val source = driver().open(t)
        val frames = runBlocking { source.frames.take(3).toList() }
        assertEquals(listOf<Byte>(3, 4, 5), frames.map { it.jpeg[2] })
        assertArrayEquals(TestPackets.SOI + ByteArray(30) { 3 } + TestPackets.EOI, frames[0].jpeg)
        assertEquals(3, source.stats.value.framesEmitted)
        assertEquals(0, source.stats.value.framesDropped)
    }

    @Test
    fun `button flag reaches the frame`() {
        val t = ReplayTransport(stream(4, buttonOn = setOf(4)))
        val frames = runBlocking { driver().open(t).frames.take(2).toList() }
        assertFalse(frames[0].buttonPressed)
        assertTrue(frames[1].buttonPressed)
    }

    @Test
    fun `stream ends with a UsbException when the device goes away`() {
        val t = ReplayTransport(stream(3))
        val source = driver().open(t)
        assertThrows(UsbException::class.java) { runBlocking { source.frames.toList() } }
    }

    @Test
    fun `packet sink sees every raw packet`() {
        var count = 0
        val packets = stream(3)
        val t = ReplayTransport(packets)
        val source = driver(sink = PacketSink { _, _, _ -> count++ }).open(t)
        runCatching { runBlocking { source.frames.toList() } }
        assertEquals(packets.size, count)
    }

    @Test
    fun `close releases both interfaces and the transport`() {
        val t = ReplayTransport(emptyList())
        driver().open(t).close()
        assertEquals(listOf("release 1", "release 0", "close"), t.calls.takeLast(3))
    }
}
