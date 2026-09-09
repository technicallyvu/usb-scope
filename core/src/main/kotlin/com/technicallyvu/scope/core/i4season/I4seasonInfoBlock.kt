package com.technicallyvu.scope.core.i4season

/** The 480-byte reply to the info request. Layout verified on hardware (spec §12). */
data class CameraInfo(val vendor: String, val product: String, val firmware: String, val width: Int, val height: Int)

object I4seasonInfoBlock {
    const val DEFAULT_WIDTH = 320
    const val DEFAULT_HEIGHT = 240
    private const val MAX_DIMENSION = 4096

    fun parse(bytes: ByteArray, len: Int): CameraInfo {
        val vendor = cString(bytes, len, 1, 16)
        val product = cString(bytes, len, 17, 16)
        val firmware = cString(bytes, len, 33, 8)
        var width = le16(bytes, len, 46)
        var height = le16(bytes, len, 48)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            width = DEFAULT_WIDTH
            height = DEFAULT_HEIGHT
        }
        return CameraInfo(vendor, product, firmware, width, height)
    }

    private fun cString(b: ByteArray, len: Int, offset: Int, max: Int): String {
        if (offset >= len) return ""
        val end = minOf(len, offset + max)
        var stop = offset
        while (stop < end && b[stop] != 0.toByte()) stop++
        return String(b, offset, stop - offset, Charsets.US_ASCII)
    }

    private fun le16(b: ByteArray, len: Int, offset: Int): Int =
        if (offset + 1 >= len) 0 else (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)
}
