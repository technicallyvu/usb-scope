package com.technicallyvu.scope.desktop.media

import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.junit.jupiter.api.Assertions.assertEquals
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
}
