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
 * `1 - 0.8*strength` where motion is 0, linear in between.
 *
 * The block mean alone has a blind spot: a small high-contrast feature moving *inside* one block
 * (or a bright bar sliding across a block boundary) can leave the block's mean unchanged, so the
 * filter would treat it as static and smear it. Alongside the mean, each block therefore counts the
 * samples whose absolute luma difference reaches `2 * motionThreshold`; **two or more** of them
 * force the block to full pass-through (`a = 256`) regardless of the mean. The doubled threshold
 * keeps noise immunity (frame-to-frame sensor noise of +-20 rarely reaches 48) and requiring a pair
 * rules out the lone Gaussian outlier, which a single-sample maximum would have mistaken for
 * motion. A real moving feature always produces at least two: the position it left and the one it
 * arrived at.
 *
 * Not thread-safe: one instance per stream.
 */
class TemporalDenoiser(strength: Float = 0.6f, val motionThreshold: Int = 24, val blockSize: Int = 4) {
    // Written from a settings screen on an arbitrary thread while apply() reads it on the session's
    // worker thread; volatile so the worker sees the new weight on its next frame rather than
    // whenever its cache happens to be refreshed. (Not "thread-safe" in any wider sense -- see above.)
    @Volatile var strength: Float = strength.coerceIn(0f, 1f)
        set(value) { field = value.coerceIn(0f, 1f) }

    private var prevYuyv: ByteArray? = null
    private var prevArgb: IntArray? = null
    private var prevW = 0
    private var prevH = 0

    init { require(blockSize > 0) { "blockSize must be > 0, was $blockSize" } }

    fun reset() { prevYuyv = null; prevArgb = null; prevW = 0; prevH = 0 }

    private fun staticWeight256(): Int = ((1f - 0.8f * strength) * 256f).toInt().coerceIn(0, 256)

    /**
     * The blend weight (0..256) for a block, from its mean-luma motion and [strongDiffs], the number
     * of samples in the block whose absolute luma difference reached `2 * motionThreshold`.
     */
    private fun weight256(motion: Int, strongDiffs: Int, aStatic: Int): Int {
        if (strongDiffs >= MIN_STRONG_DIFFS) return 256
        val m = maxOf(0, motion - NOISE_FLOOR)
        if (m >= motionThreshold) return 256
        return aStatic + (256 - aStatic) * m / motionThreshold
    }

    companion object {
        /** Subtracted from the block-mean motion metric before thresholding, to absorb residual noise. */
        const val NOISE_FLOOR = 4

        /**
         * Samples at or past `2 * motionThreshold` needed before a block is forced to pass through.
         * Two, not one: a single such sample is as likely to be a noise outlier as motion, while a
         * feature that moved always shows up twice (where it was, and where it now is).
         */
        const val MIN_STRONG_DIFFS = 2
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
        val strongDiff = 2 * motionThreshold
        val rowBytes = w * 2
        var by = 0
        while (by < h) {
            val bh = minOf(blockSize, h - by)
            var bx = 0
            while (bx < w) {
                val bw = minOf(blockSize, w - bx)
                var curSum = 0; var prevSum = 0; var count = 0; var strongDiffs = 0
                for (y in by until by + bh) {
                    var i = y * rowBytes + bx * 2
                    for (x in 0 until bw) {
                        val c = src[i].toInt() and 0xFF
                        val p = prev[i].toInt() and 0xFF
                        curSum += c; prevSum += p
                        if (abs(c - p) >= strongDiff) strongDiffs++
                        i += 2; count++
                    }
                }
                val a = weight256(abs(curSum - prevSum) / count, strongDiffs, aStatic)
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
        require(pixels.size >= width * height) { "pixels (${pixels.size}) shorter than ${width}x$height" }
        val prev = prevArgb
        if (prev == null || prevW != width || prevH != height || prev.size != pixels.size) {
            prevArgb = pixels.copyOf(); prevYuyv = null; prevW = width; prevH = height
            return
        }
        val aStatic = staticWeight256()
        val strongDiff = 2 * motionThreshold
        var by = 0
        while (by < height) {
            val bh = minOf(blockSize, height - by)
            var bx = 0
            while (bx < width) {
                val bw = minOf(blockSize, width - bx)
                var curSum = 0; var prevSum = 0; var count = 0; var strongDiffs = 0
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val i = y * width + x
                    val c = luma(pixels[i]); val p = luma(prev[i])
                    curSum += c; prevSum += p
                    if (abs(c - p) >= strongDiff) strongDiffs++
                    count++
                }
                val a = weight256(abs(curSum - prevSum) / count, strongDiffs, aStatic)
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
