package com.technicallyvu.scope.core.fixture

import com.technicallyvu.scope.core.TestPackets.packet
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbInterfaceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ReplayDeviceSourceTest {
    @Test
    fun `serves the log from a stream factory and parses it once`() {
        val out = ByteArrayOutputStream()
        PacketLogWriter(out).use { w -> val p = packet(7, 1, byteArrayOf(1, 2)); w.onPacket(p, p.size, 0) }
        var opens = 0
        val info = UsbDeviceInfo(0x2CE3, 0x3828, 0xEF, listOf(UsbInterfaceInfo(0, 0xFF, 0xF0, 1)))
        val src = ReplayDeviceSource({ opens++; ByteArrayInputStream(out.toByteArray()) }, info, videoEndpoint = 0x82)
        assertEquals(info, src.list().single().info)
        val buf = ByteArray(64)
        val t1 = src.open(src.list().single()); assertEquals(14, t1.bulkRead(0x82, buf, 100))
        val t2 = src.open(src.list().single()); assertEquals(14, t2.bulkRead(0x82, buf, 100))
        assertEquals(1, opens)
    }
}
