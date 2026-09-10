package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData

/** Packed Y0 U Y1 V (BT.601 limited range) to ARGB_8888 ints, one per pixel. Pure Kotlin; shared by shells. */
object YuyvConverter {
    fun toArgb(frame: FrameData.Yuyv422, out: IntArray) {
        val pixels = frame.width * frame.height
        require(out.size >= pixels) { "out has ${out.size} ints, need $pixels" }
        val src = frame.bytes
        var si = 0
        var di = 0
        // di + 1 < pixels: each iteration writes two pixels, so an odd-width frame must not run past the end.
        while (si + 3 < src.size && di + 1 < pixels) {
            val y0 = src[si].toInt() and 0xFF
            val u = src[si + 1].toInt() and 0xFF
            val y1 = src[si + 2].toInt() and 0xFF
            val v = src[si + 3].toInt() and 0xFF
            out[di++] = argb(y0, u, v)
            out[di++] = argb(y1, u, v)
            si += 4
        }
    }

    private fun argb(y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        val r = clamp((298 * c + 409 * e + 128) shr 8)
        val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp((298 * c + 516 * d + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
