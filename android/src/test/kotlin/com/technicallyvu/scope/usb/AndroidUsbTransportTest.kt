package com.technicallyvu.scope.usb

import com.technicallyvu.scope.core.usb.UsbException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AndroidUsbTransportTest {
    private class FakeConnection : UsbConnection {
        val calls = mutableListOf<String>()
        var bulkResults = ArrayDeque<Int>()
        var controlReply: ByteArray? = null
        override fun claimInterface(number: Int) = true.also { calls += "claim $number" }
        override fun releaseInterface(number: Int) = true.also { calls += "release $number" }
        override fun setInterface(number: Int, alt: Int) = true.also { calls += "alt $number $alt" }
        override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            calls += "bulk %02X %d".format(endpoint, length)
            val r = bulkResults.removeFirstOrNull() ?: -1
            if (r > 0) for (i in 0 until r) buffer[i] = 7
            return r
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeoutMs: Int): Int {
            calls += "ctrl %02X %02X %04X %04X %d".format(requestType, request, value, index, length)
            val reply = controlReply ?: return length
            reply.copyInto(buffer!!, 0, 0, minOf(reply.size, length)); return minOf(reply.size, length)
        }
        override fun close() { calls += "close" }
    }

    @Test
    fun `timeouts return zero until the limit then throw`() {
        val c = FakeConnection()
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertEquals(0, t.bulkRead(0x82, buf, 500))
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, buf, 500) }
    }

    @Test
    fun `data resets the timeout counter`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(-1, -1, 4, -1, -1)) }
        val t = AndroidUsbTransport(c, maxConsecutiveTimeouts = 3)
        val buf = ByteArray(16)
        t.bulkRead(0x82, buf, 500); t.bulkRead(0x82, buf, 500)
        assertEquals(4, t.bulkRead(0x82, buf, 500)); assertEquals(7, buf[3].toInt())
        assertEquals(0, t.bulkRead(0x82, buf, 500)); assertEquals(0, t.bulkRead(0x82, buf, 500))
    }

    @Test
    fun `detach makes the next read throw`() {
        val c = FakeConnection().apply { bulkResults = ArrayDeque(listOf(4)) }
        val t = AndroidUsbTransport(c)
        t.detached = true
        assertThrows(UsbException::class.java) { t.bulkRead(0x82, ByteArray(16), 500) }
    }

    @Test
    fun `control transfer fills IN buffers and reports lengths`() {
        val c = FakeConnection().apply { controlReply = byteArrayOf(1, 2, 3) }
        val t = AndroidUsbTransport(c)
        val buf = ByteArray(512)
        assertEquals(3, t.controlTransfer(0xA0, 0, 5, 0, buf, 1000))
        assertArrayEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        c.controlReply = null
        assertEquals(64, t.controlTransfer(0x20, 1, 5, 0, ByteArray(64), 1000))
        assertEquals(listOf("ctrl A0 00 0005 0000 512", "ctrl 20 01 0005 0000 64"), c.calls)
    }

    @Test
    fun `clearHalt is a CLEAR_FEATURE ENDPOINT_HALT control request`() {
        val c = FakeConnection()
        AndroidUsbTransport(c).clearHalt(0x01)
        assertEquals(listOf("ctrl 02 01 0000 0001 0"), c.calls)
    }

    @Test
    fun `claim failure and reset are UsbExceptions`() {
        val c = object : UsbConnection by FakeConnection() { override fun claimInterface(number: Int) = false }
        val t = AndroidUsbTransport(c)
        assertThrows(UsbException::class.java) { t.claimInterface(0) }
        assertThrows(UsbException::class.java) { t.resetDevice() }
    }
}
