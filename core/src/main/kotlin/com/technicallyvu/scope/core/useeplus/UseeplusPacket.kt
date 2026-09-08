package com.technicallyvu.scope.core.useeplus

/** One parsed bulk-IN packet: the camera header fields plus the JPEG payload slice. */
data class UseeplusChunk(
    val channelId: Int,
    val frameId: Int,
    val cameraNumber: Int,
    val flags: Int,
    val sensorValue: Long,
    val payload: ByteArray,
)

/**
 * useeplus packet layout (spec §5.1):
 *   0-1  magic AA BB        2 channel id (7 head / 11 tail)   3-4 length LE (camera header + payload)
 *   5    frame id           6 camera number                   7   flags
 *   8-11 g-sensor uint32 LE 12.. JPEG payload
 */
object UseeplusPacket {
    const val USB_HEADER_LEN = 5
    const val CAMERA_HEADER_LEN = 7
    const val PAYLOAD_OFFSET = USB_HEADER_LEN + CAMERA_HEADER_LEN
    const val MAGIC0 = 0xAA
    const val MAGIC1 = 0xBB
    const val CHANNEL_HEAD = 7
    const val CHANNEL_TAIL = 11

    /** Which bit of the flags byte means "cable button pressed". Verified against a real capture in Task 8. */
    const val BUTTON_MASK = 0x02

    fun parse(buf: ByteArray, len: Int = buf.size): UseeplusChunk? {
        if (len < PAYLOAD_OFFSET) return null
        if (u8(buf, 0) != MAGIC0 || u8(buf, 1) != MAGIC1) return null
        val channel = u8(buf, 2)
        if (channel != CHANNEL_HEAD && channel != CHANNEL_TAIL) return null
        val length = u8(buf, 3) or (u8(buf, 4) shl 8)
        if (length < CAMERA_HEADER_LEN) return null
        val end = USB_HEADER_LEN + length
        if (end > len) return null
        val sensor = u8(buf, 8).toLong() or
            (u8(buf, 9).toLong() shl 8) or
            (u8(buf, 10).toLong() shl 16) or
            (u8(buf, 11).toLong() shl 24)
        return UseeplusChunk(
            channelId = channel,
            frameId = u8(buf, 5),
            cameraNumber = u8(buf, 6),
            flags = u8(buf, 7),
            sensorValue = sensor,
            payload = buf.copyOfRange(PAYLOAD_OFFSET, end),
        )
    }

    private fun u8(buf: ByteArray, i: Int): Int = buf[i].toInt() and 0xFF
}
