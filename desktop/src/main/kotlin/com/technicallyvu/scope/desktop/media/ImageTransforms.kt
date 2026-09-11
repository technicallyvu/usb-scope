package com.technicallyvu.scope.desktop.media

import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.image.TemporalDenoiser
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object ImageTransforms {
    fun decodeJpeg(bytes: ByteArray): BufferedImage? =
        try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (e: Exception) { null }

    fun decode(data: FrameData): BufferedImage? = decode(data, null, null)

    fun decode(data: FrameData, yuvDenoiser: TemporalDenoiser?, argbDenoiser: TemporalDenoiser?): BufferedImage? = when (data) {
        is FrameData.Jpeg -> {
            val img = decodeJpeg(data.bytes)
            if (img != null && argbDenoiser != null) {
                val w = img.width; val h = img.height
                val pixels = img.getRGB(0, 0, w, h, null, 0, w)
                argbDenoiser.applyArgb(pixels, w, h)
                img.setRGB(0, 0, w, h, pixels, 0, w)
            }
            img
        }
        is FrameData.Yuyv422 -> yuyvToImage(yuvDenoiser?.apply(data) ?: data)
    }

    /** Packed Y0 U Y1 V (BT.601 limited range) to TYPE_3BYTE_BGR. */
    fun yuyvToImage(f: FrameData.Yuyv422): BufferedImage {
        val img = BufferedImage(f.width, f.height, BufferedImage.TYPE_3BYTE_BGR)
        val out = (img.raster.dataBuffer as DataBufferByte).data
        val src = f.bytes
        var si = 0
        var di = 0
        while (si + 3 < src.size) {
            val y0 = src[si].toInt() and 0xFF
            val u = src[si + 1].toInt() and 0xFF
            val y1 = src[si + 2].toInt() and 0xFF
            val v = src[si + 3].toInt() and 0xFF
            di = putPixel(out, di, y0, u, v)
            di = putPixel(out, di, y1, u, v)
            si += 4
        }
        return img
    }

    private fun putPixel(out: ByteArray, di: Int, y: Int, u: Int, v: Int): Int {
        val c = y - 16
        val d = u - 128
        val e = v - 128
        out[di] = clamp((298 * c + 516 * d + 128) shr 8).toByte()          // B
        out[di + 1] = clamp((298 * c - 100 * d - 208 * e + 128) shr 8).toByte()   // G
        out[di + 2] = clamp((298 * c + 409 * e + 128) shr 8).toByte()      // R
        return di + 3
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

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
