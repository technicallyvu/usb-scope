package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData

/**
 * Splits the bulk stream into frames: [511-byte header][width*height*2 bytes YUYV].
 * The header starts with DD CC 01 00; byte 7 carries the button flags. Once aligned, frames are
 * consumed by size, so magic-like bytes inside pixel data cannot break framing.
 *
 * About one frame in six arrives SHORT on real hardware: the next header's magic begins before a
 * full payload has been delivered (some trailing rows are simply missing). Rather than discard
 * these as a false lock, the parser pads the missing tail by repeating the payload's last complete
 * row (or zero-fills it if not even one full row arrived) and emits the frame anyway, counting it
 * in [framesPartial]. Not thread-safe.
 */
class I4seasonFrameParser(val width: Int, val height: Int) {
    private val rowSize = width * 2
    private val payloadSize = width * height * 2
    private val frameSize = HEADER_SIZE + payloadSize
    private var buf = ByteArray(frameSize + 2 * MAX_READ)
    private var used = 0

    var framesEmitted: Long = 0; private set

    /** Counts resynchronisation events (times the parser had to re-hunt for the magic), not lost frames. */
    var framesDropped: Long = 0; private set

    /** Counts frames emitted with a padded (short) payload; included in [framesEmitted]. */
    var framesPartial: Long = 0; private set
    var bytesReceived: Long = 0; private set

    fun accept(chunk: ByteArray, len: Int, nowNanos: Long): List<Frame> {
        bytesReceived += len
        if (used + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, used + len))
        System.arraycopy(chunk, 0, buf, used, len)
        used += len
        val out = ArrayList<Frame>(2)

        fun emit(payload: ByteArray, partial: Boolean) {
            val flags = buf[BUTTON_OFFSET].toInt() and 0xFF
            out += Frame(FrameData.Yuyv422(width, height, payload), nowNanos, buttonPressed = flags != 0, cameraNumber = 0, sensorValue = 0)
            framesEmitted++
            if (partial) framesPartial++
        }

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
            if (used < HEADER_SIZE + MAGIC_SIZE) break   // need more data before we can search the payload
            // Look for the next frame's magic inside this frame's payload window. A real frame is
            // immediately followed by the next frame's magic; a short frame's magic arrives early.
            val next = indexOfMagicInPayload()
            when {
                next in HEADER_SIZE until frameSize -> {
                    // Short frame: the next header started before a full payload arrived.
                    emit(shortFramePayload(next - HEADER_SIZE), partial = true)
                    discard(next)
                }
                next == frameSize -> {
                    emit(buf.copyOfRange(HEADER_SIZE, frameSize), partial = false)
                    discard(frameSize)
                }
                used < frameSize -> break   // full payload hasn't arrived yet
                used < frameSize + MAGIC_SIZE -> {
                    // Not enough data yet to check for the magic right after the payload; assume a
                    // full frame, as before.
                    emit(buf.copyOfRange(HEADER_SIZE, frameSize), partial = false)
                    discard(frameSize)
                }
                else -> {
                    // No magic anywhere in [HEADER_SIZE, frameSize], and the byte right after the
                    // payload isn't the magic either: this "frame" is a false lock inside a
                    // desynchronised stream. Step past its magic and let the misaligned branch above
                    // do the (single) resync once it finds the next real magic.
                    discard(MAGIC_SIZE)
                    continue
                }
            }
        }
        return out
    }

    /** Pads a short payload of [actualLen] bytes to [payloadSize] by repeating its last complete row. */
    private fun shortFramePayload(actualLen: Int): ByteArray {
        val completeRows = actualLen / rowSize
        val completeBytes = completeRows * rowSize
        val payload = ByteArray(payloadSize)
        System.arraycopy(buf, HEADER_SIZE, payload, 0, completeBytes)
        var pos = completeBytes
        while (pos < payloadSize && completeRows > 0) {
            System.arraycopy(payload, completeBytes - rowSize, payload, pos, rowSize)
            pos += rowSize
        }
        return payload
    }

    private fun alignedAtMagic() = used >= MAGIC_SIZE && isMagicAt(0)

    private fun indexOfMagic(): Int {
        for (i in 0..used - MAGIC_SIZE) if (isMagicAt(i)) return i
        return -1
    }

    /** Searches [HEADER_SIZE, frameSize] (inclusive) for the next magic, limited to bytes on hand. */
    private fun indexOfMagicInPayload(): Int {
        val limit = minOf(frameSize, used - MAGIC_SIZE)
        var i = HEADER_SIZE
        while (i <= limit) {
            if (isMagicAt(i)) return i
            i++
        }
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
