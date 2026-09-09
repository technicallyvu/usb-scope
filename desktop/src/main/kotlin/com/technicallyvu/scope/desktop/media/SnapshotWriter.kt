package com.technicallyvu.scope.desktop.media

import com.technicallyvu.scope.core.driver.FrameData
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Saves the sensor's original JPEG bytes plus an EXIF orientation tag for the chosen view transform. */
object SnapshotWriter {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun write(jpeg: ByteArray, rotationDegrees: Int, mirror: Boolean, dir: Path, now: LocalDateTime = LocalDateTime.now()): Path {
        Files.createDirectories(dir)
        val file = uniquePath(dir, "SCOPE_" + now.format(stamp), ".jpg")
        val outputSet = TiffOutputSet()
        outputSet.getOrCreateRootDirectory().add(
            TiffTagConstants.TIFF_TAG_ORIENTATION,
            exifOrientation(rotationDegrees, mirror).toShort(),
        )
        Files.newOutputStream(file).use { os -> ExifRewriter().updateExifMetadataLossless(jpeg, os, outputSet) }
        return file
    }

    /** EXIF Orientation values 1-8 for "mirror horizontally, then rotate clockwise by N". */
    fun exifOrientation(rotationDegrees: Int, mirror: Boolean): Int = when (ImageTransforms.normalize(rotationDegrees)) {
        0 -> if (mirror) 2 else 1
        90 -> if (mirror) 7 else 6
        180 -> if (mirror) 4 else 3
        270 -> if (mirror) 5 else 8
        else -> 1
    }

    fun write(data: FrameData, rotationDegrees: Int, mirror: Boolean, dir: Path, now: LocalDateTime = LocalDateTime.now()): Path {
        val jpeg = when (data) {
            is FrameData.Jpeg -> data.bytes
            is FrameData.Yuyv422 -> encodeJpeg(ImageTransforms.yuyvToImage(data))
        }
        return write(jpeg, rotationDegrees, mirror, dir, now)
    }

    private fun encodeJpeg(img: BufferedImage, quality: Float = 0.92f): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(img, null, null), params)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    internal fun uniquePath(dir: Path, base: String, ext: String): Path {
        var candidate = dir.resolve(base + ext)
        var n = 1
        while (Files.exists(candidate)) candidate = dir.resolve("${base}_$n$ext").also { n++ }
        return candidate
    }
}
