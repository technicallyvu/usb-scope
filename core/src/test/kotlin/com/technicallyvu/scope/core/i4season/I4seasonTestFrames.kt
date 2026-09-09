package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.fixture.LoggedPacket

object I4seasonTestFrames {
    /** The real 56 leading bytes of Anthony's unit's info block (rest of the 480 bytes is zero). */
    val REAL_INFO_PREFIX: ByteArray = (
        "01 69 34 73 65 61 73 6f 6e 00 00 00 00 00 00 00 00 73 75 34 70 2d 30 30 32 00 00 00 00 00 00 00 " +
        "00 35 2e 30 2e 31 33 00 00 02 00 58 02 00 40 01 f0 00 00 00 00 00 00 00"
    ).split(" ").map { it.toInt(16).toByte() }.toByteArray()

    fun realInfo(): ByteArray = REAL_INFO_PREFIX + ByteArray(480 - REAL_INFO_PREFIX.size)

    /** An info block advertising a tiny [width]x[height] picture, for small synthetic streams. */
    fun info(width: Int, height: Int): ByteArray = realInfo().also {
        it[46] = (width and 0xFF).toByte(); it[47] = ((width shr 8) and 0xFF).toByte()
        it[48] = (height and 0xFF).toByte(); it[49] = ((height shr 8) and 0xFF).toByte()
    }

    private fun header(flags: Int): ByteArray {
        val header = ByteArray(511)
        header[0] = 0xDD.toByte(); header[1] = 0xCC.toByte(); header[2] = 0x01
        header[4] = 0x58; header[5] = 0x02; header[7] = flags.toByte()
        for (i in 9 until 511) header[i] = (i * 7).toByte()          // constant vendor blob
        return header
    }

    /** One stream frame: 511-byte header (DD CC 01 00 58 02 00 flags 00 ...) + YUYV payload filled with [fill]. */
    fun frame(width: Int, height: Int, flags: Int = 0, fill: Byte = 0x40): ByteArray =
        header(flags) + ByteArray(width * height * 2) { fill }

    /** A short stream frame: the 511-byte header followed by a truncated (or otherwise short) [payload]. */
    fun shortFrame(width: Int, height: Int, payload: ByteArray, flags: Int = 0): ByteArray =
        header(flags) + payload

    /** Splits a byte stream into bulk reads of [size] bytes, 1 ms apart. */
    fun chunked(stream: ByteArray, size: Int): List<LoggedPacket> =
        stream.toList().chunked(size).mapIndexed { i, c -> LoggedPacket(i * 1_000_000L, c.toByteArray()) }
}
