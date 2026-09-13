package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData

/**
 * Luma-only unsharp mask: `y' = clamp(y + amount * (y - blur(y)))`, where `blur` is a 3x3 box blur
 * with neighbour coordinates clamped to the picture (so the border replicates rather than darkening
 * against an imaginary black surround). [strength] 0..1 maps to `amount = strength * 1.0`, which is
 * mild by design — this camera sends 320x240 and nothing more, and sharpening is a cosmetic aid, not
 * a way to recover detail that was never sampled. It makes edges read as crisper on screen and in
 * saved files; it adds no information.
 *
 * Luma only, on purpose. The YUYV variant rewrites the Y samples and leaves U/V exactly as they
 * were, and the ARGB variant computes one luma delta per pixel and adds it to R, G and B alike, so
 * neither can shift hue or throw a coloured halo along an edge — the usual giveaway of a sharpener
 * run on the colour channels.
 *
 * Applied *after* the temporal denoiser, never before: an unsharp mask amplifies exactly the
 * high-frequency content that sensor grain lives in, so sharpening first would sharpen the noise and
 * hand the denoiser a harder picture.
 *
 * Not thread-safe: it reuses one scratch buffer between calls, so one instance per stream. Only
 * [strength] may be written from another thread.
 */
class Sharpener(strength: Float = DEFAULT_STRENGTH) {
    // Written from a settings screen on an arbitrary thread while apply() reads it on the session's
    // worker thread; volatile so the worker picks the new amount up on its next frame.
    @Volatile var strength: Float = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        set(value) { field = value.coerceIn(MIN_STRENGTH, MAX_STRENGTH) }

    /** The unfiltered luma plane of the frame being processed; grown on demand, reused after that. */
    private var luma = IntArray(0)

    /** `amount` as a 1/256 fixed-point multiplier, so the inner loop stays in integer arithmetic. */
    private fun amount256(): Int = (strength * AMOUNT_PER_STRENGTH * 256f).toInt()

    /**
     * Sharpens the Y samples of [frame] **in place**; U/V are left alone. The caller owns the
     * buffer: pass a frame whose bytes may be written (the denoiser's output is a fresh array, a
     * driver's own read buffer is not).
     */
    fun apply(frame: FrameData.Yuyv422) {
        val w = frame.width
        val h = frame.height
        val bytes = frame.bytes
        val n = w * h
        if (w <= 0 || h <= 0 || bytes.size < n * 2) return
        val src = scratch(n)
        for (p in 0 until n) src[p] = bytes[p * 2].toInt() and 0xFF
        val amount = amount256()
        var p = 0
        for (y in 0 until h) for (x in 0 until w) {
            val v = src[p]
            val d = (amount * (v - blur(src, w, h, x, y)) + ROUND) shr SHIFT
            if (d != 0) bytes[p * 2] = clamp(v + d).toByte()
            p++
        }
    }

    /**
     * In-place ARGB_8888 variant (for decoded JPEG frames). One luma delta per pixel, added to all
     * three colour channels; alpha is copied through untouched.
     */
    fun applyArgb(pixels: IntArray, width: Int, height: Int) {
        require(pixels.size >= width * height) { "pixels (${pixels.size}) shorter than ${width}x$height" }
        if (width <= 0 || height <= 0) return
        val n = width * height
        val src = scratch(n)
        for (i in 0 until n) src[i] = lumaOf(pixels[i])
        val amount = amount256()
        var i = 0
        for (y in 0 until height) for (x in 0 until width) {
            val d = (amount * (src[i] - blur(src, width, height, x, y)) + ROUND) shr SHIFT
            if (d != 0) {
                val c = pixels[i]
                pixels[i] = (c and ALPHA_MASK) or
                    (clamp(((c shr 16) and 0xFF) + d) shl 16) or
                    (clamp(((c shr 8) and 0xFF) + d) shl 8) or
                    clamp((c and 0xFF) + d)
            }
            i++
        }
    }

    private fun scratch(n: Int): IntArray {
        if (luma.size < n) luma = IntArray(n)
        return luma
    }

    /** Mean of the 3x3 neighbourhood of (x, y), coordinates clamped to the picture. */
    private fun blur(src: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        var sum = 0
        for (dy in -1..1) {
            val row = (y + dy).coerceIn(0, h - 1) * w
            for (dx in -1..1) sum += src[row + (x + dx).coerceIn(0, w - 1)]
        }
        return (sum + TAPS / 2) / TAPS
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun lumaOf(argb: Int): Int =
        (((argb shr 16) and 0xFF) * 77 + ((argb shr 8) and 0xFF) * 150 + (argb and 0xFF) * 29) shr 8

    companion object {
        /** Mild by design: at full strength the mask adds one whole difference-from-blur, no more. */
        const val AMOUNT_PER_STRENGTH = 1.0f
        const val DEFAULT_STRENGTH = 0.5f
        const val MIN_STRENGTH = 0.1f
        const val MAX_STRENGTH = 1.0f

        private const val ALPHA_MASK = 0xFF shl 24
        private const val TAPS = 9
        private const val SHIFT = 8
        private const val ROUND = 1 shl (SHIFT - 1)
    }
}
