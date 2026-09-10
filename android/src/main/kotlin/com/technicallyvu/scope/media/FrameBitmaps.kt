package com.technicallyvu.scope.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.technicallyvu.scope.core.driver.FrameData
import com.technicallyvu.scope.core.image.TemporalDenoiser
import com.technicallyvu.scope.core.image.YuyvConverter

/** Converts frames to bitmaps and applies the view transform. Reuses its pixel buffer; use from one thread. */
class FrameBitmaps {
    private var argb = IntArray(0)
    private var jpegPixels = IntArray(0)

    fun toBitmap(data: FrameData, argbDenoiser: TemporalDenoiser? = null): Bitmap? = when (data) {
        is FrameData.Jpeg -> {
            val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = BitmapFactory.decodeByteArray(data.bytes, 0, data.bytes.size, opts)
            if (bmp != null && argbDenoiser != null) {
                val n = bmp.width * bmp.height
                if (jpegPixels.size < n) jpegPixels = IntArray(n)
                bmp.getPixels(jpegPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                argbDenoiser.applyArgb(jpegPixels, bmp.width, bmp.height)
                bmp.setPixels(jpegPixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            }
            bmp
        }
        is FrameData.Yuyv422 -> {
            val n = data.width * data.height
            if (argb.size < n) argb = IntArray(n)
            YuyvConverter.toArgb(data, argb)
            Bitmap.createBitmap(argb, data.width, data.height, Bitmap.Config.ARGB_8888)
        }
    }

    /** Mirror horizontally (if asked), then rotate clockwise. Returns [src] itself when nothing changes. */
    fun transform(src: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0 && !mirror) return src
        val m = Matrix()
        if (mirror) m.preScale(-1f, 1f)
        m.postRotate(rot.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
