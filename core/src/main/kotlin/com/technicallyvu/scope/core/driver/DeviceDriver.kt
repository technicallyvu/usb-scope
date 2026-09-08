package com.technicallyvu.scope.core.driver

import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class Frame(
    val jpeg: ByteArray,
    val timestampNanos: Long,
    val buttonPressed: Boolean,
    val cameraNumber: Int,
    val sensorValue: Long,
)

data class StreamStats(
    val fps: Double = 0.0,
    val framesEmitted: Long = 0,
    val framesDropped: Long = 0,
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
    fun matches(info: UsbDeviceInfo): Boolean
    /** Performs the device handshake. Throws [com.technicallyvu.scope.core.usb.UsbException] if the device cannot be started. */
    fun open(transport: UsbTransport): FrameSource
}
