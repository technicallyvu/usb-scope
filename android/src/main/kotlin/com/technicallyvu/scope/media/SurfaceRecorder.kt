package com.technicallyvu.scope.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * H.264/MP4 clip recorder fed by drawing bitmaps onto the encoder's input surface. Frame timing
 * comes from the surface (variable frame rate is fine). Not thread-safe; callers serialize.
 */
class SurfaceRecorder(context: Context, fd: ParcelFileDescriptor, width: Int, height: Int) : AutoCloseable {
    private val w = width and 1.inv()    // H.264 needs even dimensions
    private val h = height and 1.inv()
    private val recorder: MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
    private val surface: Surface

    var framesWritten: Int = 0
        private set

    init {
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(fd.fileDescriptor)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(w, h)
        recorder.setVideoFrameRate(15)
        recorder.setVideoEncodingBitRate(4_000_000)
        try {
            recorder.prepare()
            surface = recorder.surface
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }

    fun record(bitmap: Bitmap) {
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, w, h), null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        framesWritten++
    }

    override fun close() {
        try {
            recorder.stop()          // throws RuntimeException when no frame was ever written
        } catch (e: RuntimeException) {
            // nothing usable was recorded; the caller discards the file when framesWritten == 0
        } finally {
            surface.release()
            recorder.release()
        }
    }
}
