package com.technicallyvu.scope.desktop.media

import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

class Mp4RecorderTest {
    @Test
    fun `writes a playable mp4 with the recorded frame count`(@TempDir dir: Path) {
        val file = dir.resolve("clip.mp4")
        Mp4Recorder(file, 64, 48).use { rec ->
            for (i in 0 until 30) {
                val img = BufferedImage(64, 48, BufferedImage.TYPE_3BYTE_BGR)
                val g = img.createGraphics(); g.color = if (i % 2 == 0) Color.RED else Color.BLUE; g.fillRect(0, 0, 64, 48); g.dispose()
                rec.record(img, i * 66_000_000L)   // ~15 fps
            }
            assertEquals(30, rec.framesWritten)
        }
        assertTrue(Files.size(file) > 1_000, "file too small")
        FFmpegFrameGrabber(file.toFile()).use { g ->
            g.start()
            assertEquals(64, g.imageWidth)
            assertEquals(48, g.imageHeight)
            var n = 0
            while (g.grabImage() != null) n++
            assertTrue(n >= 28, "decoded only $n frames")
        }
    }
}
