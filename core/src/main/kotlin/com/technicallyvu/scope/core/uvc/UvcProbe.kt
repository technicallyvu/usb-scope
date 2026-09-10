package com.technicallyvu.scope.core.uvc

import com.technicallyvu.scope.core.usb.UsbTransport

/**
 * The fields of the UVC `VS_PROBE_CONTROL` / `VS_COMMIT_CONTROL` structure this driver cares about.
 *
 * The struct itself is 26, 34 or 48 bytes depending on the UVC version (see [UvcProbe.lengthFor]);
 * every field we do not use is left zero, which tells the device "no preference".
 */
data class UvcProbeControl(
    val formatIndex: Int,
    val frameIndex: Int,
    /** `dwFrameInterval`, in 100 ns units (333333 = 30 fps). */
    val frameInterval100ns: Int,
    /** `dwMaxVideoFrameSize`: bytes in one complete frame. */
    val maxVideoFrameSize: Int,
    /** `dwMaxPayloadTransferSize`: bytes the device may put in one payload transfer. */
    val maxPayloadTransferSize: Int,
)

/**
 * Probe/commit negotiation (UVC 1.1 §4.3.1.1). The host proposes a format/frame/interval with a
 * SET_CUR on the probe control, reads back what the device is actually willing to do with a GET_CUR,
 * and then locks that in with a SET_CUR on the commit control. Streaming may only start afterwards.
 */
object UvcProbe {

    // Requests and control selectors (spec 1.1; plan Global Constraints).
    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val REQTYPE_OUT = 0x21
    const val REQTYPE_IN = 0xA1
    const val VS_PROBE_CONTROL = 0x0100
    const val VS_COMMIT_CONTROL = 0x0200
    const val TIMEOUT_MS = 1000

    // Field offsets inside the struct.
    private const val OFF_HINT = 0
    private const val OFF_FORMAT_INDEX = 2
    private const val OFF_FRAME_INDEX = 3
    private const val OFF_FRAME_INTERVAL = 4
    private const val OFF_MAX_FRAME_SIZE = 18
    private const val OFF_MAX_PAYLOAD = 22

    /** `bmHint` bit 0: dwFrameInterval is fixed, the device may vary the other fields. */
    private const val HINT_FRAME_INTERVAL = 0x0001

    /** Struct length for a `bcdUVC`: 26 bytes for 1.0, 34 for 1.1/1.2, 48 for 1.5 and later. */
    fun lengthFor(bcdUvc: Int): Int = when {
        bcdUvc < 0x0110 -> 26
        bcdUvc < 0x0150 -> 34
        else -> 48
    }

    fun encode(p: UvcProbeControl, length: Int): ByteArray {
        val out = ByteArray(length)
        out.le16(OFF_HINT, HINT_FRAME_INTERVAL)
        if (length > OFF_FORMAT_INDEX) out[OFF_FORMAT_INDEX] = p.formatIndex.toByte()
        if (length > OFF_FRAME_INDEX) out[OFF_FRAME_INDEX] = p.frameIndex.toByte()
        out.le32(OFF_FRAME_INTERVAL, p.frameInterval100ns, length)
        out.le32(OFF_MAX_FRAME_SIZE, p.maxVideoFrameSize, length)
        out.le32(OFF_MAX_PAYLOAD, p.maxPayloadTransferSize, length)
        return out
    }

    /**
     * Reads the same offsets back out of [bytes], honouring [n] (the byte count the device actually
     * returned): a field the reply does not reach comes back as 0, never as garbage or an exception.
     */
    fun decode(bytes: ByteArray, n: Int): UvcProbeControl {
        val len = n.coerceIn(0, bytes.size)
        fun u8(off: Int) = if (off < len) bytes[off].toInt() and 0xFF else 0
        fun u32(off: Int) = if (off + 4 <= len) {
            u8(off) or (u8(off + 1) shl 8) or (u8(off + 2) shl 16) or (u8(off + 3) shl 24)
        } else {
            0
        }
        return UvcProbeControl(
            formatIndex = u8(OFF_FORMAT_INDEX),
            frameIndex = u8(OFF_FRAME_INDEX),
            frameInterval100ns = u32(OFF_FRAME_INTERVAL),
            maxVideoFrameSize = u32(OFF_MAX_FRAME_SIZE),
            maxPayloadTransferSize = u32(OFF_MAX_PAYLOAD),
        )
    }

    /**
     * SET_CUR probe -> GET_CUR probe -> SET_CUR commit, all on [vsInterface]. Returns the control the
     * device agreed to, which is what the caller must use for buffer sizes.
     *
     * A device that answers the GET_CUR with fewer bytes than the mandatory 26 has told us nothing
     * usable, so [request] is committed unchanged rather than a struct of zeros.
     */
    fun negotiate(t: UsbTransport, vsInterface: Int, bcdUvc: Int, request: UvcProbeControl): UvcProbeControl {
        val length = lengthFor(bcdUvc)
        t.controlTransfer(REQTYPE_OUT, SET_CUR, VS_PROBE_CONTROL, vsInterface, encode(request, length), TIMEOUT_MS)

        val reply = ByteArray(length)
        val n = t.controlTransfer(REQTYPE_IN, GET_CUR, VS_PROBE_CONTROL, vsInterface, reply, TIMEOUT_MS)
        val negotiated = if (n >= 26) decode(reply, n) else request

        t.controlTransfer(REQTYPE_OUT, SET_CUR, VS_COMMIT_CONTROL, vsInterface, encode(negotiated, length), TIMEOUT_MS)
        return negotiated
    }

    private fun ByteArray.le16(off: Int, v: Int) {
        if (off + 2 > size) return
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.le32(off: Int, v: Int, limit: Int) {
        if (off + 4 > minOf(limit, size)) return
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = ((v shr 8) and 0xFF).toByte()
        this[off + 2] = ((v shr 16) and 0xFF).toByte()
        this[off + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
