package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharpenerTest {
    private val w = 8
    private val h = 4

    /** YUYV with luma [luma] at every pixel and neutral chroma. */
    private fun flat(v: Int): FrameData.Yuyv422 {
        val b = ByteArray(w * h * 2)
        for (p in 0 until w * h) { b[p * 2] = v.toByte(); b[p * 2 + 1] = 128.toByte() }
        return FrameData.Yuyv422(w, h, b)
    }

    /** A vertical step edge: luma [dark] for the left half, [bright] for the right, chroma 0x77/0x99. */
    private fun edge(dark: Int = 50, bright: Int = 200): FrameData.Yuyv422 {
        val b = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 2
            b[i] = (if (x < w / 2) dark else bright).toByte()
            // Deliberately non-neutral and not the same on both samples of a pair, so a sharpener
            // that touched chroma at all would show up.
            b[i + 1] = (if (x % 2 == 0) 0x77 else 0x99).toByte()
        }
        return FrameData.Yuyv422(w, h, b)
    }

    private fun luma(f: FrameData.Yuyv422, x: Int, y: Int): Int = f.bytes[(y * f.width + x) * 2].toInt() and 0xFF

    @Test
    fun `a flat field is unchanged`() {
        val f = flat(120)
        val before = f.bytes.copyOf()
        Sharpener(1.0f).apply(f)
        assertTrue(f.bytes.contentEquals(before), "a picture with no local contrast has nothing to sharpen")
    }

    @Test
    fun `a step edge overshoots on both sides, by more at a higher strength`() {
        fun overshoot(strength: Float): Pair<Int, Int> {
            val f = edge()
            Sharpener(strength).apply(f)
            // x = 3 is the last dark column, x = 4 the first bright one; the unsharp mask must push
            // the first below the original minimum and the second above the original maximum.
            return (50 - luma(f, 3, 1)) to (luma(f, 4, 1) - 200)
        }
        val (darkLow, brightHigh) = overshoot(1.0f)
        assertTrue(darkLow > 0, "dark side of the edge should undershoot below 50, went $darkLow past it")
        assertTrue(brightHigh > 0, "bright side of the edge should overshoot above 200, went $brightHigh past it")

        val (darkLowMild, brightHighMild) = overshoot(0.5f)
        assertTrue(darkLowMild in 1 until darkLow, "undershoot should scale with strength: $darkLowMild vs $darkLow")
        assertTrue(brightHighMild in 1 until brightHigh, "overshoot should scale with strength: $brightHighMild vs $brightHigh")
    }

    @Test
    fun `a flat region away from the edge keeps its value`() {
        val f = edge()
        Sharpener(1.0f).apply(f)
        assertEquals(50, luma(f, 0, 1), "two columns from the edge there is no local contrast to amplify")
        assertEquals(200, luma(f, 7, 1))
    }

    @Test
    fun `chroma bytes are untouched`() {
        val f = edge()
        val chromaBefore = (1 until f.bytes.size step 2).map { f.bytes[it] }
        Sharpener(1.0f).apply(f)
        val chromaAfter = (1 until f.bytes.size step 2).map { f.bytes[it] }
        assertEquals(chromaBefore, chromaAfter, "luma-only: U/V must survive untouched, or edges fringe")
    }

    @Test
    fun `sharpening into a new frame leaves the source untouched`() {
        // The frame path hands the sharpener buffers it does not own -- the driver's read buffer,
        // or the array the denoiser keeps as its previous-frame state. Neither may come back
        // changed, so the picture the driver produced must survive a sharpen pass byte for byte.
        val source = edge()
        val before = source.bytes.copyOf()
        val out = Sharpener(1.0f).sharpened(source)
        assertTrue(source.bytes.contentEquals(before), "the source frame must not be written")
        assertTrue(out.bytes !== source.bytes, "the result must be an array of its own")
        assertTrue(!out.bytes.contentEquals(before), "...and it must actually be sharpened, or this proves nothing")
        assertEquals(before.size, out.bytes.size)

        val dst = ByteArray(source.bytes.size)
        Sharpener(1.0f).applyTo(source, dst)
        assertTrue(source.bytes.contentEquals(before), "applyTo must not write its source either")
        assertTrue(dst.contentEquals(out.bytes), "applyTo and sharpened must produce the same picture")
    }

    @Test
    fun `sharpening the denoiser's output does not alter the denoiser's history`() {
        // Two identical frames: with the history intact, frame 2 out == frame 1 out and a third
        // identical frame gives the same again. Were the sharpener writing in place on the array
        // TemporalDenoiser.apply returned, that array *is* the history, so the next blend would
        // start from the sharpened picture and the output would ring instead of settling.
        val d = TemporalDenoiser(1.0f)
        d.apply(edge())
        val second = d.apply(edge())
        val sharp = Sharpener(1.0f).sharpened(second)
        assertTrue(!sharp.bytes.contentEquals(second.bytes), "the edge must really be sharpened, or this proves nothing")
        val third = d.apply(edge())
        assertTrue(
            third.bytes.contentEquals(second.bytes),
            "a static scene must converge, not ring: got ${third.bytes.joinToString()} vs ${second.bytes.joinToString()}",
        )
    }

    @Test
    fun `the argb variant sharpens luma and leaves alpha alone`() {
        val px = IntArray(w * h) { i ->
            val x = i % w
            val v = if (x < w / 2) 50 else 200
            // Half-transparent on purpose: alpha must come back exactly as it went in.
            0x80000000.toInt() or (v * 0x010101)
        }
        val before = px.copyOf()
        Sharpener(1.0f).applyArgb(px, w, h)
        fun blue(i: Int) = px[i] and 0xFF
        assertTrue(blue(1 * w + 3) < 50, "dark side should undershoot, got ${blue(1 * w + 3)}")
        assertTrue(blue(1 * w + 4) > 200, "bright side should overshoot, got ${blue(1 * w + 4)}")
        assertEquals(before[1 * w + 0], px[1 * w + 0], "a flat region must be left alone")
        assertTrue(px.all { (it ushr 24) == 0x80 }, "alpha 0x80 in must be alpha 0x80 out")
        // Grey in, grey out: the same delta goes onto all three channels.
        assertTrue(px.all { ((it shr 16) and 0xFF) == (it and 0xFF) && ((it shr 8) and 0xFF) == (it and 0xFF) })
    }

    @Test
    fun `strength is clamped into its range`() {
        assertEquals(Sharpener.MIN_STRENGTH, Sharpener(0f).strength)
        assertEquals(Sharpener.MIN_STRENGTH, Sharpener(-3f).strength)
        assertEquals(Sharpener.MAX_STRENGTH, Sharpener(9f).strength)
        assertEquals(Sharpener.DEFAULT_STRENGTH, Sharpener().strength)
        val s = Sharpener()
        s.strength = 42f
        assertEquals(Sharpener.MAX_STRENGTH, s.strength)
        s.strength = -1f
        assertEquals(Sharpener.MIN_STRENGTH, s.strength)
    }

    @Test
    fun `degenerate sizes do not crash`() {
        val one = FrameData.Yuyv422(1, 1, byteArrayOf(90.toByte(), 128.toByte()))
        Sharpener(1.0f).apply(one)
        assertEquals(90, one.bytes[0].toInt() and 0xFF, "a 1x1 picture is its own blur")

        val twoBytes = ByteArray(2 * 2 * 2)
        for (p in 0 until 4) { twoBytes[p * 2] = (if (p % 2 == 0) 40 else 210).toByte(); twoBytes[p * 2 + 1] = 128.toByte() }
        val two = FrameData.Yuyv422(2, 2, twoBytes)
        Sharpener(1.0f).apply(two)

        val onePx = intArrayOf(0xFF203040.toInt())
        Sharpener(1.0f).applyArgb(onePx, 1, 1)
        assertEquals(0xFF203040.toInt(), onePx[0])
        Sharpener(1.0f).applyArgb(IntArray(4) { 0xFF808080.toInt() }, 2, 2)
    }

    @Test
    fun `an already-clipped edge stays in range`() {
        val f = edge(dark = 0, bright = 255)
        Sharpener(1.0f).apply(f)
        assertTrue(f.bytes.indices.step(2).all { (f.bytes[it].toInt() and 0xFF) in 0..255 })
        assertEquals(0, luma(f, 3, 1), "an undershoot below black clamps at black")
        assertEquals(255, luma(f, 4, 1), "an overshoot above white clamps at white")
    }
}
