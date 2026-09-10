package com.technicallyvu.scope.desktop.media

import com.technicallyvu.scope.core.driver.FrameData
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import javax.imageio.ImageIO

class SnapshotWriterTest {
    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    @Test
    fun `exif orientation table`() {
        assertEquals(1, SnapshotWriter.exifOrientation(0, false))
        assertEquals(2, SnapshotWriter.exifOrientation(0, true))
        assertEquals(6, SnapshotWriter.exifOrientation(90, false))
        assertEquals(7, SnapshotWriter.exifOrientation(90, true))
        assertEquals(3, SnapshotWriter.exifOrientation(180, false))
        assertEquals(4, SnapshotWriter.exifOrientation(180, true))
        assertEquals(8, SnapshotWriter.exifOrientation(270, false))
        assertEquals(5, SnapshotWriter.exifOrientation(270, true))
    }

    @Test
    fun `writes original pixels with the orientation tag and a timestamped name`(@TempDir dir: Path) {
        val path = SnapshotWriter.write(jpeg(), 90, false, dir, LocalDateTime.of(2026, 9, 8, 14, 5, 9))
        assertEquals("SCOPE_20260908_140509.jpg", path.fileName.toString())
        val bytes = Files.readAllBytes(path)
        val img = requireNotNull(ImageIO.read(path.toFile()))
        assertEquals(16, img.width)   // pixels untouched; viewer applies the tag
        val meta = requireNotNull(Imaging.getMetadata(bytes) as? JpegImageMetadata)
        assertEquals(6, meta.findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION).intValue)
    }

    @Test
    fun `two snapshots in the same second get distinct names`(@TempDir dir: Path) {
        val t = LocalDateTime.of(2026, 9, 8, 14, 5, 9)
        val a = SnapshotWriter.write(jpeg(), 0, false, dir, t)
        val b = SnapshotWriter.write(jpeg(), 0, false, dir, t)
        assertTrue(a != b)
        assertTrue(Files.exists(a) && Files.exists(b))
    }

    @Test
    fun `write(BufferedImage) produces a readable JPEG without an orientation tag`(@TempDir dir: Path) {
        // The denoise route: the rotation/mirror the user chose are already in these pixels, so a
        // viewer must not rotate them a second time on the strength of an EXIF tag.
        val img = BufferedImage(9, 21, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 21) for (x in 0 until 9) img.setRGB(x, y, if (x < 4) 0xFFFFFF else 0x000000)

        val path = SnapshotWriter.write(img, dir, LocalDateTime.of(2026, 9, 8, 16, 30, 0))
        assertEquals("SCOPE_20260908_163000.jpg", path.fileName.toString())

        val read = requireNotNull(ImageIO.read(path.toFile()))
        assertEquals(9, read.width)
        assertEquals(21, read.height)
        assertTrue((read.getRGB(1, 1) and 0xFF) > 200, "left half should be near white")
        assertTrue((read.getRGB(7, 1) and 0xFF) < 60, "right half should be near black")

        val meta = Imaging.getMetadata(Files.readAllBytes(path)) as? JpegImageMetadata
        assertNull(
            meta?.findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION),
            "the transform is in the pixels; an orientation tag would apply it twice",
        )
    }

    @Test
    fun `yuyv frames are jpeg-encoded and tagged`(@TempDir dir: Path) {
        val b = ByteArray(16 * 8 * 2)
        for (i in b.indices step 4) { b[i] = 235.toByte(); b[i + 1] = 128.toByte(); b[i + 2] = 235.toByte(); b[i + 3] = 128.toByte() }
        val path = SnapshotWriter.write(FrameData.Yuyv422(16, 8, b), 90, false, dir, LocalDateTime.of(2026, 9, 8, 15, 0, 0))
        assertEquals("SCOPE_20260908_150000.jpg", path.fileName.toString())
        val img = requireNotNull(ImageIO.read(path.toFile()))
        assertEquals(16, img.width)
        assertTrue((img.getRGB(3, 3) and 0xFF) > 240, "should be near white")
        val meta = requireNotNull(Imaging.getMetadata(Files.readAllBytes(path)) as? JpegImageMetadata)
        assertEquals(6, meta.findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION).intValue)
    }
}
