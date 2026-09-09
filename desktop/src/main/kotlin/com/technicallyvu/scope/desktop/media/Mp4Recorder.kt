package com.technicallyvu.scope.desktop.media

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Encodes frames to an MP4 file. Uses the MPEG-4 Part 2 encoder, which is always present in the
 * LGPL ffmpeg build and plays in Windows Media Player, VLC and browsers. Frame timing follows the
 * capture timestamps (variable frame rate).
 * Not thread-safe: callers serialize [record] and [close] (the view model holds a lock).
 */
class Mp4Recorder(file: Path, width: Int, height: Int, nominalFps: Double = 15.0) : AutoCloseable {
    private val recorder = FFmpegFrameRecorder(file.toFile(), width, height).apply {
        format = "mp4"
        videoCodec = avcodec.AV_CODEC_ID_MPEG4
        pixelFormat = avutil.AV_PIX_FMT_YUV420P
        frameRate = nominalFps
        videoBitrate = 4_000_000
    }
    private val converter = Java2DFrameConverter()
    private var firstTimestampNanos = -1L

    var framesWritten: Int = 0
        private set

    init {
        try {
            recorder.start()
        } catch (e: Throwable) {
            runCatching { recorder.release() }
            runCatching { converter.close() }
            throw e
        }
    }

    fun record(image: BufferedImage, timestampNanos: Long) {
        if (firstTimestampNanos < 0) firstTimestampNanos = timestampNanos
        val micros = (timestampNanos - firstTimestampNanos) / 1_000
        if (micros > recorder.timestamp) recorder.timestamp = micros
        recorder.record(converter.convert(image))
        framesWritten++
    }

    override fun close() {
        try {
            recorder.stop()
        } finally {
            try {
                recorder.release()
            } finally {
                converter.close()
            }
        }
    }
}
