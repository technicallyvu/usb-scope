package com.technicallyvu.scope.core

object TestPackets {
    /** Builds one useeplus bulk packet: 5-byte USB header + 7-byte camera header + payload. */
    fun packet(
        channelId: Int,
        frameId: Int,
        payload: ByteArray,
        cameraNumber: Int = 0,
        flags: Int = 0,
        sensor: Long = 0,
    ): ByteArray {
        val length = 7 + payload.size
        val out = ByteArray(12 + payload.size)
        out[0] = 0xAA.toByte(); out[1] = 0xBB.toByte()
        out[2] = channelId.toByte()
        out[3] = (length and 0xFF).toByte(); out[4] = ((length shr 8) and 0xFF).toByte()
        out[5] = frameId.toByte(); out[6] = cameraNumber.toByte(); out[7] = flags.toByte()
        out[8] = (sensor and 0xFF).toByte(); out[9] = ((sensor shr 8) and 0xFF).toByte()
        out[10] = ((sensor shr 16) and 0xFF).toByte(); out[11] = ((sensor shr 24) and 0xFF).toByte()
        payload.copyInto(out, 12)
        return out
    }

    val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** A fake "JPEG": SOI + filler + EOI. Not decodable, but passes marker checks. */
    fun fakeJpeg(fillerBytes: Int = 20): ByteArray = SOI + ByteArray(fillerBytes) { 0x11 } + EOI

    /** Splits [frame] into a head (channel 7) and tail (channel 11) packet for [frameId]. */
    fun framePackets(frameId: Int, frame: ByteArray, flags: Int = 0): List<ByteArray> {
        val half = frame.size / 2
        return listOf(
            packet(7, frameId, frame.copyOfRange(0, half), flags = flags),
            packet(11, frameId, frame.copyOfRange(half, frame.size), flags = flags),
        )
    }
}

/** JPEG bytes of a frame that must carry JPEG data. */
fun com.technicallyvu.scope.core.driver.Frame.jpegBytes(): ByteArray =
    (data as com.technicallyvu.scope.core.driver.FrameData.Jpeg).bytes
