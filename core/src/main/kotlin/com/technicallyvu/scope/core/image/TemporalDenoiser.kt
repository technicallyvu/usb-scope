package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import kotlin.math.abs

/**
 * Adaptive recursive temporal filter. Each frame is blended into the previous *output*; the motion
 * metric for a 4x4 block is the absolute difference of the block's *mean* luma between the current
 * and previous frame (not a per-pixel mean-absolute-difference), which cuts the contribution of
 * per-pixel sensor noise by roughly the size of the block while still responding strongly to a real
 * moving edge. A small [NOISE_FLOOR] is then subtracted from that motion value (floored at 0) so
 * residual noise in the block mean doesn't nudge the blend away from the static weight. The weight
 * of the current frame is 1.0 where the floored motion is >= [motionThreshold] and
 * `1 - 0.8*strength` where motion is 0, linear in between. Not thread-safe: one instance per stream.
 */
class TemporalDenoiser(strength: Float = 0.6f, val motionThreshold: Int = 24, val blockSize: Int = 4) {
    var strength: Float = strength.coerceIn(0f, 1f)
        set(value) { field = value.coerceIn(0f, 1f) }

    private var prevYuyv: ByteArray? = null
    private var prevArgb: IntArray? = null
    private var prevW = 0
    private var prevH = 0

    fun reset() { prevYuyv = null; prevArgb = null; prevW = 0; prevH = 0 }

    private fun staticWeight256(): Int = ((1f - 0.8f * strength) * 256f).toInt().coerceIn(32, 256)

    companion object {
        /** Subtracted from the block-mean motion metric before thresholding, to absorb residual noise. */
        const val NOISE_FLOOR = 4
    }

    /** Returns a new, denoised frame. The input is never modified. */
    fun apply(frame: FrameData.Yuyv422): FrameData.Yuyv422 {
        val w = frame.width; val h = frame.height; val src = frame.bytes
        val prev = prevYuyv
        if (prev == null || prevW != w || prevH != h || prev.size != src.size) {
            prevYuyv = src.copyOf(); prevArgb = null; prevW = w; prevH = h
            return FrameData.Yuyv422(w, h, src.copyOf())
        }
        val out = ByteArray(src.size)
        val aStatic = staticWeight256()
        val rowBytes = w * 2
        var by = 0
        while (by < h) {
            val bh = minOf(blockSize, h - by)
            var bx = 0
            while (bx < w) {
                val bw = minOf(blockSize, w - bx)
                var curSum = 0; var prevSum = 0; var count = 0
                for (y in by until by + bh) {
                    var i = y * rowBytes + bx * 2
                    for (x in 0 until bw) {
                        curSum += (src[i].toInt() and 0xFF)
                        prevSum += (prev[i].toInt() and 0xFF)
                        i += 2; count++
                    }
                }
                val motion = abs(curSum - prevSum) / count
                val m = maxOf(0, motion - NOISE_FLOOR)
                val a = if (m >= motionThreshold) 256 else aStatic + (256 - aStatic) * m / motionThreshold
                for (y in by until by + bh) {
                    val start = y * rowBytes + bx * 2
                    val end = start + bw * 2
                    for (i in start until end) {
                        val c = src[i].toInt() and 0xFF
                        val p = prev[i].toInt() and 0xFF
                        out[i] = (p + (((c - p) * a + 128) shr 8)).coerceIn(0, 255).toByte()
                    }
                }
                bx += blockSize
            }
            by += blockSize
        }
        prevYuyv = out
        return FrameData.Yuyv422(w, h, out)
    }

    /** In-place ARGB_8888 variant (for decoded JPEG frames). */
    fun applyArgb(pixels: IntArray, width: Int, height: Int) {
        val prev = prevArgb
        if (prev == null || prevW != width || prevH != height || prev.size != pixels.size) {
            prevArgb = pixels.copyOf(); prevYuyv = null; prevW = width; prevH = height
            return
        }
        val aStatic = staticWeight256()
        var by = 0
        while (by < height) {
            val bh = minOf(blockSize, height - by)
            var bx = 0
            while (bx < width) {
                val bw = minOf(blockSize, width - bx)
                var curSum = 0; var prevSum = 0; var count = 0
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val i = y * width + x
                    curSum += luma(pixels[i]); prevSum += luma(prev[i]); count++
                }
                val motion = abs(curSum - prevSum) / count
                val m = maxOf(0, motion - NOISE_FLOOR)
                val a = if (m >= motionThreshold) 256 else aStatic + (256 - aStatic) * m / motionThreshold
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val i = y * width + x
                    val c = pixels[i]; val p = prev[i]
                    val r = blend((c shr 16) and 0xFF, (p shr 16) and 0xFF, a)
                    val g = blend((c shr 8) and 0xFF, (p shr 8) and 0xFF, a)
                    val b = blend(c and 0xFF, p and 0xFF, a)
                    val v = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    pixels[i] = v; prev[i] = v
                }
                bx += blockSize
            }
            by += blockSize
        }
    }

    private fun blend(c: Int, p: Int, a: Int): Int = (p + (((c - p) * a + 128) shr 8)).coerceIn(0, 255)
    private fun luma(argb: Int): Int = (((argb shr 16) and 0xFF) * 77 + ((argb shr 8) and 0xFF) * 150 + (argb and 0xFF) * 29) shr 8
}
