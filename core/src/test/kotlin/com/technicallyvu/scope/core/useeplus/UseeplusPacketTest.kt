package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.TestPackets.packet
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UseeplusPacketTest {
    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `parses a head packet`() {
        val pkt = packet(channelId = 7, frameId = 42, payload = payload, cameraNumber = 1, flags = 0x02, sensor = 0x01020304)
        val chunk = UseeplusPacket.parse(pkt, pkt.size)!!
        assertEquals(7, chunk.channelId)
        assertEquals(42, chunk.frameId)
        assertEquals(1, chunk.cameraNumber)
        assertEquals(0x02, chunk.flags)
        assertEquals(0x01020304L, chunk.sensorValue)
        assertArrayEquals(payload, chunk.payload)
    }

    @Test
    fun `accepts tail channel 11`() {
        val pkt = packet(channelId = 11, frameId = 1, payload = payload)
        assertEquals(11, UseeplusPacket.parse(pkt, pkt.size)?.channelId)
    }

    @Test
    fun `rejects other channels`() {
        val pkt = packet(channelId = 3, frameId = 1, payload = payload)
        assertNull(UseeplusPacket.parse(pkt, pkt.size))
    }

    @Test
    fun `rejects bad magic`() {
        val pkt = packet(channelId = 7, frameId = 1, payload = payload)
        pkt[0] = 0x00
        assertNull(UseeplusPacket.parse(pkt, pkt.size))
    }

    @Test
    fun `rejects buffers shorter than the headers`() {
        assertNull(UseeplusPacket.parse(ByteArray(11), 11))
    }

    @Test
    fun `rejects declared length beyond the buffer`() {
        val pkt = packet(channelId = 7, frameId = 1, payload = payload)
        pkt[3] = 0xFF.toByte(); pkt[4] = 0x03  // length 1023 > buffer
        assertNull(UseeplusPacket.parse(pkt, pkt.size))
    }

    @Test
    fun `payload stops at declared length even when the buffer is larger`() {
        val pkt = packet(channelId = 7, frameId = 1, payload = payload)
        val padded = pkt + ByteArray(100) { 0x77 }   // a 1 KB bulk read has trailing garbage
        assertArrayEquals(payload, UseeplusPacket.parse(padded, padded.size)?.payload)
    }

    @Test
    fun `frame id and sensor are unsigned`() {
        val pkt = packet(channelId = 7, frameId = 0xFE, payload = payload, sensor = 0xFFFFFFFFL)
        val chunk = UseeplusPacket.parse(pkt, pkt.size)!!
        assertEquals(0xFE, chunk.frameId)
        assertEquals(0xFFFFFFFFL, chunk.sensorValue)
    }
}
