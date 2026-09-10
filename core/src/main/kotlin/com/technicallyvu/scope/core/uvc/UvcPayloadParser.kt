package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.useeplus.FrameReassembler
import java.io.ByteArrayOutputStream

/**
 * Turns a UVC bulk-transport byte stream into frames.
 *
 * The stream is a sequence of *payload transfers*, each `bHeaderLength` header bytes (byte 0) plus
 * data, and each no longer than the negotiated `dwMaxPayloadTransferSize` ([maxPayloadSize]). A
 * payload ends at that size or, earlier, at a short USB packet. Because a payload may be larger than
 * one bulk read, [accept] tracks how much of the current payload is still outstanding and parses a
 * header only when a new payload begins.
 *
 * Frame boundaries come from `bmHeaderInfo` (byte 1): bit 0 is the frame id, which toggles between
 * consecutive frames; bit 1 (EOF) marks the last payload of a frame; bit 6 (ERR) means the device
 * knows the frame is damaged, so we drop it. A frame id toggle also ends a frame, which is how a
 * stream with a lost EOF still makes progress.
 *
 * Not thread-safe; owned by one read loop.
 */
class UvcPayloadParser(
    private val kind: UvcFormatKind,
    private val width: Int,
    private val height: Int,
    private val maxPayloadSize: Int,
) {
    var framesEmitted: Long = 0; private set
    var framesDropped: Long = 0; private set
    var bytesReceived: Long = 0; private set

    private val frameBuf = ByteArrayOutputStream(256 * 1024)

    /** Bytes still expected in the payload being read; 0 means the next chunk starts a new payload. */
    private var remainingInPayload = 0
    private var currentFid = -1
    private var frameErrored = false

    /** The current payload's header said EOF; the frame ends once its data has all arrived. */
    private var eofPending = false

    /**
     * Consumes [n] bytes of [chunk] read from the bulk endpoint with a buffer of [chunkCapacity]
     * bytes (a read shorter than the buffer is a short packet, and so ends the payload). Returns the
     * frames completed by this chunk -- normally none or one.
     */
    fun accept(chunk: ByteArray, n: Int, chunkCapacity: Int, nowNanos: Long): List<Frame> {
        if (n <= 0) return emptyList()
        bytesReceived += n
        val out = mutableListOf<Frame>()

        if (remainingInPayload > 0) {
            // Continuation of the payload already in progress: all of it is data.
            frameBuf.write(chunk, 0, n)
            remainingInPayload -= n
            if (n < chunkCapacity || remainingInPayload <= 0) endPayload(nowNanos, out)
            return out
        }

        val headerLength = chunk[0].toInt() and 0xFF
        if (headerLength < 2 || headerLength > n) {
            // Not a payload header. Drop this transfer and wait for the next one to resynchronise;
            // the partially built frame is kept, and its own validity check will catch the damage.
            framesDropped++
            remainingInPayload = 0
            return out
        }

        val flags = chunk[1].toInt() and 0xFF
        val fid = flags and FID
        if (fid != currentFid && frameBuf.size() > 0) finish(nowNanos, out)
        currentFid = fid
        if (flags and ERR != 0) frameErrored = true
        if (headerLength < n) frameBuf.write(chunk, headerLength, n - headerLength)
        eofPending = flags and EOF != 0

        remainingInPayload = maxPayloadSize - n
        if (n < chunkCapacity || remainingInPayload <= 0) endPayload(nowNanos, out)
        return out
    }

    /**
     * The payload transfer is over. EOF is honoured here rather than at the header so that a frame
     * whose last payload spans several reads is emitted whole instead of truncated at the header.
     */
    private fun endPayload(nowNanos: Long, out: MutableList<Frame>) {
        remainingInPayload = 0
        if (eofPending) finish(nowNanos, out)
    }

    private fun finish(nowNanos: Long, out: MutableList<Frame>) {
        val bytes = frameBuf.toByteArray()
        frameBuf.reset()
        val errored = frameErrored
        frameErrored = false
        eofPending = false
        if (bytes.isEmpty()) return
        if (errored) {
            framesDropped++
            return
        }

        val data = when (kind) {
            UvcFormatKind.MJPEG -> if (FrameReassembler.isJpeg(bytes)) FrameData.Jpeg(bytes) else null
            UvcFormatKind.YUY2 -> if (bytes.size == width * height * 2) FrameData.Yuyv422(width, height, bytes) else null
            UvcFormatKind.OTHER -> null
        }
        if (data == null) {
            framesDropped++
            return
        }
        framesEmitted++
        out += Frame(
            data = data,
            timestampNanos = nowNanos,
            // UVC carries no vendor button/sensor side channel; those stay at their neutral values.
            buttonPressed = false,
            cameraNumber = 0,
            sensorValue = 0,
        )
    }

    private companion object {
        const val FID = 0x01
        const val EOF = 0x02
        const val ERR = 0x40
    }
}
