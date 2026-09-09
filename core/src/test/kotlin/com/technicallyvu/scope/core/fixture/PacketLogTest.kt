package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.TestPackets.packet
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.useeplus.UseeplusDriver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PacketLogTest {
    @Test
    fun `writer and reader round trip`() {
        val p1 = packet(7, 1, byteArrayOf(1, 2, 3))
        val p2 = packet(11, 1, byteArrayOf(4, 5))
        val out = ByteArrayOutputStream()
        PacketLogWriter(out).use { w ->
            w.onPacket(p1, p1.size, 1_000)
            w.onPacket(p2 + ByteArray(50), p2.size, 2_000)   // len < buffer: only len bytes stored
        }
        val bytes = out.toByteArray()
        assertEquals('U'.code.toByte(), bytes[0])
        val packets = PacketLogReader.read(ByteArrayInputStream(bytes))
        assertEquals(2, packets.size)
        assertEquals(1_000, packets[0].timestampNanos)
        assertArrayEquals(p1, packets[0].bytes)
        assertEquals(2_000, packets[1].timestampNanos)
        assertArrayEquals(p2, packets[1].bytes)
    }

    @Test
    fun `reader rejects a file without the magic`() {
        assertThrows(IllegalArgumentException::class.java) {
            PacketLogReader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
        }
    }

    @Test
    fun `replay transport serves video packets in order and ends`() {
        val p1 = packet(7, 1, byteArrayOf(1))
        val p2 = packet(11, 1, byteArrayOf(2))
        val t = ReplayTransport(listOf(LoggedPacket(0, p1), LoggedPacket(10, p2)))
        val buf = ByteArray(1024)
        assertEquals(p1.size, t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100))
        assertArrayEquals(p1, buf.copyOf(p1.size))
        assertEquals(p2.size, t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100))
        assertThrows(UsbException::class.java) { t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100) }
    }

    @Test
    fun `replay transport loops when asked and paces by timestamp`() {
        val p1 = packet(7, 1, byteArrayOf(1))
        val slept = mutableListOf<Long>()
        val t = ReplayTransport(listOf(LoggedPacket(0, p1), LoggedPacket(50_000_000, p1)), loop = true, sleep = { slept += it })
        val buf = ByteArray(1024)
        repeat(3) { t.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, 100) }
        assertEquals(listOf(50L), slept)   // one 50 ms gap; loop restart does not sleep
    }

    @Test
    fun `replay transport control-in reads time out and calls are recorded`() {
        val t = ReplayTransport(emptyList())
        assertEquals(0, t.bulkRead(UseeplusDriver.EP_CONTROL_IN, ByteArray(64), 100))
        t.claimInterface(0)
        t.setAltSetting(1, 1)
        t.clearHalt(0x01)
        t.bulkWrite(0x02, byteArrayOf(0xFF.toByte(), 0x55), 100)
        assertEquals(listOf("claim 0", "alt 1 1", "clearHalt 01", "write 02 FF 55"), t.calls)
    }

    @Test
    fun `replay transport records control transfers and answers IN requests from canned replies`() {
        val t = ReplayTransport(emptyList())
        val out = ByteArray(64)
        assertEquals(64, t.controlTransfer(0x20, 1, 5, 0, out, 1000))
        val buf = ByteArray(512)
        assertEquals(0, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))           // nothing canned -> 0 bytes
        t.controlResponses[0xA0 to 0x00] = byteArrayOf(1, 2, 3)
        assertEquals(3, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        assertEquals(listOf("ctrl 20 01 0005 0000 64", "ctrl A0 00 0005 0000 512", "ctrl A0 00 0005 0000 512"), t.calls)
    }
}
