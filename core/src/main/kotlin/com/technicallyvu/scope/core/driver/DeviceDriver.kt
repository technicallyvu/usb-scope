package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Pixel payload of one frame. Shells decode whichever variant a driver produces. */
sealed interface FrameData {
    class Jpeg(val bytes: ByteArray) : FrameData

    /** Packed 4:2:2, byte order Y0 U Y1 V, exactly width*height*2 bytes. */
    class Yuyv422(val width: Int, val height: Int, val bytes: ByteArray) : FrameData {
        init {
            require(bytes.size == width * height * 2) { "YUYV payload ${bytes.size} != ${width}x${height}x2" }
        }
    }
}

class Frame(
    val data: FrameData,
    val timestampNanos: Long,
    val buttonPressed: Boolean,
    val cameraNumber: Int,
    val sensorValue: Long,
)

data class StreamStats(
    val fps: Double = 0.0,
    val framesEmitted: Long = 0,
    val framesDropped: Long = 0,
    val framesPartial: Long = 0,
    val bytesReceived: Long = 0,
)

/** Receives every raw bulk-IN packet before parsing. Used for fixture capture. */
fun interface PacketSink {
    fun onPacket(packet: ByteArray, len: Int, timestampNanos: Long)
}

interface FrameSource : AutoCloseable {
    val frames: Flow<Frame>
    val stats: StateFlow<StreamStats>
}

interface DeviceDriver {
    val id: String
    val displayName: String
    /** Clockwise rotation that shows this device's picture upright by default. */
    val defaultRotation: Int get() = 0
    fun matches(info: UsbDeviceInfo): Boolean
    /**
     * Performs the device handshake. Throws [com.technicallyvu.scope.core.usb.UsbException] if the device
     * cannot be started.
     *
     * Blocking: up to about 4.5 s (3 attempts with a 1.5 s wait between them), so it must never be called
     * on a UI thread — on Android that means a coroutine on `Dispatchers.IO`, not the main looper.
     */
    fun open(transport: UsbTransport): FrameSource
    /** A copy of this driver that also feeds raw packets to [sink] (fixture capture). */
    fun withPacketSink(sink: PacketSink): DeviceDriver
}
