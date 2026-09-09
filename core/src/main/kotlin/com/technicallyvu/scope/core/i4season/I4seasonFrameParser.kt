package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData

/**
 * Splits the bulk stream into frames: [511-byte header][width*height*2 bytes YUYV].
 * The header starts with DD CC; byte 7 carries the button flags. Once aligned, frames are consumed
 * by size, so magic-like bytes inside pixel data cannot break framing. Not thread-safe.
 */
class I4seasonFrameParser(val width: Int, val height: Int) {
    private val payloadSize = width * height * 2
    private val frameSize = HEADER_SIZE + payloadSize
    private var buf = ByteArray(frameSize + 2 * MAX_READ)
    private var used = 0

    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    fun accept(chunk: ByteArray, len: Int, nowNanos: Long): List<Frame> {
        bytesReceived += len
        if (used + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, used + len))
        System.arraycopy(chunk, 0, buf, used, len)
        used += len
        val out = ArrayList<Frame>(2)
        while (true) {
            if (!alignedAtMagic()) {
                val i = indexOfMagic()
                if (i < 0) {
                    if (used > 1) discard(used - 1)   // keep a possible leading DD of a split magic
                    break
                }
                framesDropped++
                discard(i)
            }
            if (used < frameSize) break
            val flags = buf[BUTTON_OFFSET].toInt() and 0xFF
            val payload = buf.copyOfRange(HEADER_SIZE, frameSize)
            out += Frame(FrameData.Yuyv422(width, height, payload), nowNanos, buttonPressed = flags != 0, cameraNumber = 0, sensorValue = 0)
            framesEmitted++
            discard(frameSize)
        }
        return out
    }

    private fun alignedAtMagic() = used >= 2 && buf[0] == MAGIC0 && buf[1] == MAGIC1

    private fun indexOfMagic(): Int {
        for (i in 0 until used - 1) if (buf[i] == MAGIC0 && buf[i + 1] == MAGIC1) return i
        return -1
    }

    private fun discard(n: Int) {
        System.arraycopy(buf, n, buf, 0, used - n)
        used -= n
    }

    companion object {
        const val HEADER_SIZE = 511
        const val BUTTON_OFFSET = 7
        const val MAX_READ = 16384
        const val MAGIC0 = 0xDD.toByte()
        const val MAGIC1 = 0xCC.toByte()
    }
}
