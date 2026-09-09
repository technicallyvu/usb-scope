package com.technicallyvu.scope.desktop.media

import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object ImageTransforms {
    fun decodeJpeg(bytes: ByteArray): BufferedImage? =
        try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null }

    fun normalize(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360

    /** Mirror horizontally (if asked), then rotate clockwise by 0/90/180/270. Output is TYPE_3BYTE_BGR for the encoder. */
    fun apply(src: BufferedImage, rotationDegrees: Int, mirror: Boolean): BufferedImage {
        val rot = normalize(rotationDegrees)
        val w = src.width
        val h = src.height
        val (ow, oh) = if (rot == 90 || rot == 270) h to w else w to h
        val out = BufferedImage(ow, oh, BufferedImage.TYPE_3BYTE_BGR)
        val t = AffineTransform()
        t.translate(ow / 2.0, oh / 2.0)
        t.rotate(Math.toRadians(rot.toDouble()))
        if (mirror) t.scale(-1.0, 1.0)
        t.translate(-w / 2.0, -h / 2.0)
        val g = out.createGraphics()
        try {
            g.drawImage(src, t, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
