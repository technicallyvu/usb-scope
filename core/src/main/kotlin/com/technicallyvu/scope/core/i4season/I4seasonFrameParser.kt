package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData

/**
 * Splits the bulk stream into frames: [511-byte header][width*height*2 bytes YUYV].
 * The header starts with DD CC 01 00; byte 7 carries the button flags. Once aligned, frames are
 * consumed by size, so magic-like bytes inside pixel data cannot break framing. Not thread-safe.
 */
class I4seasonFrameParser(val width: Int, val height: Int) {
    private val payloadSize = width * height * 2
    private val frameSize = HEADER_SIZE + payloadSize
    private var buf = ByteArray(frameSize + 2 * MAX_READ)
    private var used = 0

    var framesEmitted: Long = 0; private set

    /** Counts resynchronisation events (times the parser had to re-hunt for the magic), not lost frames. */
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
                    if (used > MAGIC_SIZE - 1) discard(used - (MAGIC_SIZE - 1))   // keep a possible split magic at the end
                    break
                }
                framesDropped++
                discard(i)
            }
            if (used < frameSize) break
            // A real frame is immediately followed by the next frame's magic (when enough has arrived
            // to check). If it isn't, this "frame" is a false lock inside a desynchronised stream:
            // drop one byte and resync instead of emitting garbage.
            if (used >= frameSize + MAGIC_SIZE && !isMagicAt(frameSize)) {
                framesDropped++
                discard(1)
                continue
            }
            val flags = buf[BUTTON_OFFSET].toInt() and 0xFF
            val payload = buf.copyOfRange(HEADER_SIZE, frameSize)
            out += Frame(FrameData.Yuyv422(width, height, payload), nowNanos, buttonPressed = flags != 0, cameraNumber = 0, sensorValue = 0)
            framesEmitted++
            discard(frameSize)
        }
        return out
    }

    private fun alignedAtMagic() = used >= MAGIC_SIZE && isMagicAt(0)

    private fun indexOfMagic(): Int {
        for (i in 0..used - MAGIC_SIZE) if (isMagicAt(i)) return i
        return -1
    }

    private fun isMagicAt(i: Int) = buf[i] == MAGIC0 && buf[i + 1] == MAGIC1 && buf[i + 2] == MAGIC2 && buf[i + 3] == MAGIC3

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
        const val MAGIC2 = 0x01.toByte()
        const val MAGIC3 = 0x00.toByte()
        const val MAGIC_SIZE = 4
    }
}
