package com.technicallyvu.scope.desktop

import com.technicallyvu.scope.core.fixture.LoggedPacket
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object TestPackets {
    fun packet(channelId: Int, frameId: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        val length = 7 + payload.size
        val out = ByteArray(12 + payload.size)
        out[0] = 0xAA.toByte(); out[1] = 0xBB.toByte(); out[2] = channelId.toByte()
        out[3] = (length and 0xFF).toByte(); out[4] = ((length shr 8) and 0xFF).toByte()
        out[5] = frameId.toByte(); out[6] = 0; out[7] = flags.toByte()
        payload.copyInto(out, 12)
        return out
    }

    /** A real, decodable 32x24 JPEG. */
    fun realJpeg(color: Color = Color.GREEN): ByteArray {
        val img = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = color; g.fillRect(0, 0, 32, 24); g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    /** [frameCount] real JPEG frames split head/tail, 66 ms apart, button flag on [buttonOn] frames, plus a flush packet. */
    fun stream(frameCount: Int, buttonOn: Set<Int> = emptySet(), buttonMask: Int): List<LoggedPacket> {
        val packets = ArrayList<LoggedPacket>()
        var ts = 0L
        for (f in 1..frameCount) {
            val jpeg = realJpeg()
            val half = jpeg.size / 2
            val flags = if (f in buttonOn) buttonMask else 0
            packets += LoggedPacket(ts, packet(7, f, jpeg.copyOfRange(0, half), flags)); ts += 33_000_000
            packets += LoggedPacket(ts, packet(11, f, jpeg.copyOfRange(half, jpeg.size), flags)); ts += 33_000_000
        }
        packets += LoggedPacket(ts, packet(7, frameCount + 1, byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        return packets
    }
}
