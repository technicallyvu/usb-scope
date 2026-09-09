package com.technicallyvu.scope.desktop.media

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageTransformsTest {
    private val red = 0xFF0000
    private val blue = 0x0000FF

    /** 2x1 image: red on the left, blue on the right. */
    private fun twoPixels(): BufferedImage = BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, red); setRGB(1, 0, blue)
    }

    private fun rgb(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y) and 0xFFFFFF

    @Test
    fun `no-op transform keeps pixels`() {
        val out = ImageTransforms.apply(twoPixels(), 0, false)
        assertEquals(red, rgb(out, 0, 0)); assertEquals(blue, rgb(out, 1, 0))
    }

    @Test
    fun `mirror swaps left and right`() {
        val out = ImageTransforms.apply(twoPixels(), 0, true)
        assertEquals(blue, rgb(out, 0, 0)); assertEquals(red, rgb(out, 1, 0))
    }

    @Test
    fun `rotate 90 clockwise puts the left pixel on top`() {
        val out = ImageTransforms.apply(twoPixels(), 90, false)
        assertEquals(1, out.width); assertEquals(2, out.height)
        assertEquals(red, rgb(out, 0, 0)); assertEquals(blue, rgb(out, 0, 1))
    }

    @Test
    fun `rotate 270 clockwise puts the left pixel at the bottom`() {
        val out = ImageTransforms.apply(twoPixels(), 270, false)
        assertEquals(blue, rgb(out, 0, 0)); assertEquals(red, rgb(out, 0, 1))
    }

    @Test
    fun `rotate 180 reverses`() {
        val out = ImageTransforms.apply(twoPixels(), 180, false)
        assertEquals(blue, rgb(out, 0, 0)); assertEquals(red, rgb(out, 1, 0))
    }

    @Test
    fun `decodes a jpeg and rejects garbage`() {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val img = requireNotNull(ImageTransforms.decodeJpeg(bytes))
        assertEquals(8, img.width)
        assertNull(ImageTransforms.decodeJpeg(byteArrayOf(1, 2, 3)))
    }

    private fun yuyv(w: Int, h: Int, y: Int, u: Int, v: Int): FrameData.Yuyv422 {
        val b = ByteArray(w * h * 2)
        for (i in b.indices step 4) { b[i] = y.toByte(); b[i + 1] = u.toByte(); b[i + 2] = y.toByte(); b[i + 3] = v.toByte() }
        return FrameData.Yuyv422(w, h, b)
    }

    @Test
    fun `yuyv white and black`() {
        val white = ImageTransforms.yuyvToImage(yuyv(4, 2, 235, 128, 128))
        assertEquals(4, white.width); assertEquals(2, white.height)
        assertEquals(0xFFFFFF, rgb(white, 3, 1))
        val black = ImageTransforms.yuyvToImage(yuyv(4, 2, 16, 128, 128))
        assertEquals(0x000000, rgb(black, 0, 0))
    }

    @Test
    fun `yuyv red is red`() {
        val img = ImageTransforms.yuyvToImage(yuyv(2, 1, 81, 90, 240))
        val p = rgb(img, 0, 0)
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        assertTrue(r > 200 && g < 40 && b < 40, "expected red, got %06X".format(p))
    }

    @Test
    fun `decode dispatches on frame data type`() {
        val jpegBytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        assertEquals(8, ImageTransforms.decode(FrameData.Jpeg(jpegBytes))!!.width)
        assertEquals(4, ImageTransforms.decode(yuyv(4, 2, 128, 128, 128))!!.width)
        assertNull(ImageTransforms.decode(FrameData.Jpeg(byteArrayOf(1, 2))))
    }
}
