package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class TemporalDenoiserTest {
    private val w = 32
    private val h = 16

    /** Gradient luma, neutral chroma. */
    private fun base(): ByteArray {
        val b = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 2
            b[i] = (40 + x * 4).toByte(); b[i + 1] = 128.toByte()
        }
        return b
    }

    private fun noisy(rnd: Random, amp: Int): FrameData.Yuyv422 {
        val b = base()
        for (i in b.indices step 2) b[i] = ((b[i].toInt() and 0xFF) + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255).toByte()
        return FrameData.Yuyv422(w, h, b)
    }

    private fun lumaError(f: FrameData.Yuyv422, ref: ByteArray): Double {
        var sum = 0L; var n = 0
        for (i in f.bytes.indices step 2) { sum += abs((f.bytes[i].toInt() and 0xFF) - (ref[i].toInt() and 0xFF)); n++ }
        return sum.toDouble() / n
    }

    @Test
    fun `static noisy scene converges toward the clean image`() {
        val d = TemporalDenoiser(strength = 0.6f)
        val rnd = Random(7)
        var out: FrameData.Yuyv422? = null
        var rawErr = 0.0
        repeat(8) { val f = noisy(rnd, 20); rawErr = lumaError(f, base()); out = d.apply(f) }
        val err = lumaError(requireNotNull(out), base())
        // Steady-state noise ratio for a recursive filter at the intended static weight (~0.52) is
        // a/(2-a) ≈ 0.6; use 0.7 as the bound to leave headroom for the finite 8-frame warm-up and
        // block-level granularity without masking a real regression in the motion metric.
        assertTrue(err < rawErr * 0.7, "denoised error $err vs raw $rawErr")
        assertEquals(w * h * 2, out!!.bytes.size)
    }

    @Test
    fun `first frame passes through unchanged and input is never modified`() {
        val d = TemporalDenoiser()
        val f = noisy(Random(1), 20)
        val copy = f.bytes.copyOf()
        val out = d.apply(f)
        assertTrue(out.bytes.contentEquals(copy))
        d.apply(noisy(Random(2), 20))
        assertTrue(f.bytes.contentEquals(copy))
    }

    @Test
    fun `a moving bright bar is not smeared`() {
        val d = TemporalDenoiser(strength = 0.9f)
        var last: FrameData.Yuyv422? = null
        for (k in 0 until 6) {
            val b = ByteArray(w * h * 2) { i -> if (i % 2 == 0) 20 else 128.toByte() }
            val x0 = k * 4
            for (y in 0 until h) for (x in x0 until x0 + 4) b[(y * w + x) * 2] = 235.toByte()
            last = d.apply(FrameData.Yuyv422(w, h, b))
        }
        val out = requireNotNull(last).bytes
        val barX = 5 * 4 + 1
        val oldX = 4 * 4 + 1
        val atBar = out[(3 * w + barX) * 2].toInt() and 0xFF
        val atOld = out[(3 * w + oldX) * 2].toInt() and 0xFF
        assertTrue(atBar > 220, "bar position should be bright, got $atBar")
        assertTrue(atOld < 40, "old bar position should have no ghost, got $atOld")
    }

    @Test
    fun `size change resets history`() {
        val d = TemporalDenoiser()
        d.apply(noisy(Random(3), 20))
        val small = FrameData.Yuyv422(8, 4, ByteArray(64) { 100 })
        val out = d.apply(small)
        assertTrue(out.bytes.contentEquals(small.bytes))
    }

    @Test
    fun `argb variant reduces noise and keeps alpha opaque`() {
        val d = TemporalDenoiser(strength = 0.6f)
        val rnd = Random(9)
        val ref = IntArray(w * h) { i -> 0xFF000000.toInt() or ((60 + (i % w) * 3) * 0x010101) }
        var px = IntArray(0)
        var rawErr = 0.0
        repeat(8) {
            px = IntArray(w * h) { i ->
                val v = ((ref[i] and 0xFF) + rnd.nextInt(-20, 21)).coerceIn(0, 255)
                0xFF000000.toInt() or (v * 0x010101)
            }
            rawErr = px.indices.sumOf { abs((px[it] and 0xFF) - (ref[it] and 0xFF)) }.toDouble() / px.size
            d.applyArgb(px, w, h)
        }
        val err = px.indices.sumOf { abs((px[it] and 0xFF) - (ref[it] and 0xFF)) }.toDouble() / px.size
        // Same steady-state reasoning as the YUYV test above: expected ratio ~0.6, bound at 0.7.
        assertTrue(err < rawErr * 0.7, "argb denoised error $err vs raw $rawErr")
        assertTrue(px.all { (it ushr 24) == 0xFF })
    }
}
