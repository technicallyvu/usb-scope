package com.technicallyvu.scope.desktop.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
