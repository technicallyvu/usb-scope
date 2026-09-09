package com.technicallyvu.scope.core.useeplus

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Driver for the Geek szitman "supercamera" family (useeplus protocol). Spec §5.1.
 * Reimplemented from public protocol documentation; see README "Prior work".
 */
class UseeplusDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
) : DeviceDriver {

    override val id = "useeplus"
    override val displayName = "useeplus endoscope"
    override val defaultRotation: Int get() = 90

    override fun matches(info: UsbDeviceInfo): Boolean {
        if ((info.vendorId to info.productId) !in SUPPORTED_IDS) return false
        if (info.interfaces.isEmpty()) return true   // layout unknown: assume the documented two-interface variant
        val vendorF0 = info.interfaces.filter { it.usbClass == 0xFF && it.subclass == 0xF0 }
        return vendorF0.any { it.protocol == 0 } && vendorF0.any { it.protocol == 1 }
    }

    override fun withPacketSink(sink: PacketSink): DeviceDriver =
        UseeplusDriver(clock, sleep, ioDispatcher, sink)

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        repeat(OPEN_ATTEMPTS) {
            try {
                handshake(transport)
                return UseeplusFrameSource(transport, clock, ioDispatcher, packetSink)
            } catch (e: UsbException) {
                lastError = e
                runCatching { transport.resetDevice() }
                sleep(RESET_WAIT_MS)
            }
        }
        throw UsbException("useeplus handshake failed after $OPEN_ATTEMPTS attempts", cause = lastError)
    }

    internal fun handshake(t: UsbTransport) {
        t.claimInterface(IFACE_CONTROL)
        t.claimInterface(IFACE_VIDEO)
        val scratch = ByteArray(64)
        for (i in 0 until HEARTBEAT_DRAIN_MAX) {
            if (t.bulkRead(EP_CONTROL_IN, scratch, HEARTBEAT_TIMEOUT_MS) == 0) break
        }
        t.setAltSetting(IFACE_VIDEO, 1)
        t.clearHalt(EP_VIDEO_OUT)
        t.bulkWrite(EP_CONTROL_OUT, MAGIC_INIT, CMD_TIMEOUT_MS)
        t.bulkWrite(EP_VIDEO_OUT, CMD_CONNECT, CMD_TIMEOUT_MS)
    }

    companion object {
        val SUPPORTED_IDS = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)
        const val IFACE_CONTROL = 0
        const val IFACE_VIDEO = 1
        const val EP_VIDEO_OUT = 0x01
        const val EP_VIDEO_IN = 0x81
        const val EP_CONTROL_OUT = 0x02
        const val EP_CONTROL_IN = 0x82
        val MAGIC_INIT = byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10)
        val CMD_CONNECT = byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 0x05, 0x00, 0x00)
        const val PACKET_SIZE = 1024
        const val READ_TIMEOUT_MS = 5000
        const val HEARTBEAT_DRAIN_MAX = 30
        const val HEARTBEAT_TIMEOUT_MS = 100
        const val CMD_TIMEOUT_MS = 1000
        const val OPEN_ATTEMPTS = 3
        const val RESET_WAIT_MS = 1500L
        const val DISCARD_FRAMES = 2
    }
}

class UseeplusFrameSource(
    private val transport: UsbTransport,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val packetSink: PacketSink?,
) : FrameSource {
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats

    @Volatile private var closed = false

    // Note: the blocking bulkRead is offloaded per-call via withContext rather than wrapping the
    // whole flow in flowOn(dispatcher). flowOn interposes a buffered channel between producer and
    // collector, which lets this loop race ahead of a downstream take(n)'s cancellation and attempt
    // reads the collector no longer wants. Offloading per-call keeps emit() a direct, backpressured
    // handoff to the collector so cancellation is observed before the next read is issued.
    override val frames: Flow<Frame> = flow {
        val reassembler = FrameReassembler()
        val fps = FpsMeter()
        val buf = ByteArray(UseeplusDriver.PACKET_SIZE)
        var discarded = 0
        while (!closed) {
            val n = withContext(dispatcher) {
                transport.bulkRead(UseeplusDriver.EP_VIDEO_IN, buf, UseeplusDriver.READ_TIMEOUT_MS)
            }
            if (n == 0) continue
            val now = clock()
            packetSink?.onPacket(buf, n, now)
            val chunk = UseeplusPacket.parse(buf, n) ?: continue
            val frame = reassembler.accept(chunk, now) ?: continue
            if (discarded < UseeplusDriver.DISCARD_FRAMES) { discarded++; continue }
            _stats.value = StreamStats(
                fps = fps.tick(now),
                framesEmitted = reassembler.framesEmitted - UseeplusDriver.DISCARD_FRAMES,
                framesDropped = reassembler.framesDropped,
                bytesReceived = reassembler.bytesReceived,
            )
            emit(frame)
        }
    }

    override fun close() {
        closed = true
        runCatching { transport.releaseInterface(UseeplusDriver.IFACE_VIDEO) }
        runCatching { transport.releaseInterface(UseeplusDriver.IFACE_CONTROL) }
        runCatching { transport.close() }
    }
}
