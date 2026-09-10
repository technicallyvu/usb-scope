package com.technicallyvu.scope.core.image

import com.technicallyvu.scope.core.driver.FrameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YuyvConverterTest {
    private fun yuyv(w: Int, h: Int, y: Int, u: Int, v: Int): FrameData.Yuyv422 {
        val b = ByteArray(w * h * 2)
        for (i in b.indices step 4) { b[i] = y.toByte(); b[i + 1] = u.toByte(); b[i + 2] = y.toByte(); b[i + 3] = v.toByte() }
        return FrameData.Yuyv422(w, h, b)
    }

    @Test
    fun `white black and red`() {
        val out = IntArray(8)
        YuyvConverter.toArgb(yuyv(4, 2, 235, 128, 128), out)
        assertEquals(0xFFFFFFFF.toInt(), out[7])
        YuyvConverter.toArgb(yuyv(4, 2, 16, 128, 128), out)
        assertEquals(0xFF000000.toInt(), out[0])
        YuyvConverter.toArgb(yuyv(2, 1, 81, 90, 240), out)
        val p = out[0]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        assertTrue(r > 200 && g < 40 && b < 40, "expected red, got %08X".format(p))
        assertEquals(0xFF, (p ushr 24))
    }

    @Test
    fun `output must fit the frame`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { YuyvConverter.toArgb(yuyv(4, 2, 128, 128, 128), IntArray(7)) }
    }
}
