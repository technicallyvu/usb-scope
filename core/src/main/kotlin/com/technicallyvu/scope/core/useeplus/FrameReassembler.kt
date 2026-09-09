package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.driver.Frame
import java.io.ByteArrayOutputStream

/**
 * Accumulates payload chunks while the frame id is unchanged. When the id changes, the
 * accumulated bytes are emitted as a [Frame] if they carry JPEG SOI/EOI markers, else dropped.
 * Not thread-safe; owned by one read loop.
 */
class FrameReassembler {
    private val buffer = ByteArrayOutputStream(64 * 1024)
    private var currentFrameId = -1
    private var flagsOr = 0
    private var cameraNumber = 0
    private var sensorValue = 0L

    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    /** Returns the completed previous frame, if this chunk started a new one and the previous was valid. */
    fun accept(chunk: UseeplusChunk, nowNanos: Long): Frame? {
        bytesReceived += chunk.payload.size
        var completed: Frame? = null
        if (chunk.frameId != currentFrameId) {
            if (buffer.size() > 0) completed = finish(nowNanos)
            currentFrameId = chunk.frameId
            flagsOr = 0
        }
        buffer.write(chunk.payload)
        flagsOr = flagsOr or chunk.flags
        cameraNumber = chunk.cameraNumber
        sensorValue = chunk.sensorValue
        return completed
    }

    private fun finish(nowNanos: Long): Frame? {
        val bytes = buffer.toByteArray()
        buffer.reset()
        return if (isJpeg(bytes)) {
            framesEmitted++
            Frame(
                jpeg = bytes,
                timestampNanos = nowNanos,
                buttonPressed = (flagsOr and UseeplusPacket.BUTTON_MASK) != 0,
                cameraNumber = cameraNumber,
                sensorValue = sensorValue,
            )
        } else {
            framesDropped++
            null
        }
    }

    companion object {
        fun isJpeg(b: ByteArray): Boolean =
            b.size >= 4 &&
                b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
                b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
    }
}
