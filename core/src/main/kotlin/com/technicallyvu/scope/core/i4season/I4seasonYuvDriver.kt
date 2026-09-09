package com.technicallyvu.scope.core.i4season

import com.technicallyvu.scope.core.driver.DeviceDriver
import com.technicallyvu.scope.core.driver.Frame
import com.technicallyvu.scope.core.driver.FrameSource
import com.technicallyvu.scope.core.driver.PacketSink
import com.technicallyvu.scope.core.driver.StreamStats
import com.technicallyvu.scope.core.usb.UsbDeviceInfo
import com.technicallyvu.scope.core.usb.UsbException
import com.technicallyvu.scope.core.usb.UsbTransport
import com.technicallyvu.scope.core.useeplus.FpsMeter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Driver for the single-interface "YUV" personality of the 2CE3:3828 / 0329:2022 endoscopes
 * (i4season firmware). Protocol per spec §12: class control requests to start/stop, raw YUYV frames
 * with a 511-byte DD CC header on bulk IN 0x82.
 */
class I4seasonYuvDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
) : DeviceDriver {

    override val id = "i4season-yuv"
    override val displayName = "YUV endoscope (single interface)"
    /** Anthony's unit shows upright at 180 (acceptance 2026-09-09). */
    override val defaultRotation: Int get() = 180

    override fun matches(info: UsbDeviceInfo): Boolean {
        if ((info.vendorId to info.productId) !in SUPPORTED_IDS) return false
        val only = info.interfaces.singleOrNull() ?: return false
        return only.usbClass == 0xFF && only.subclass == 0xF0 && only.protocol == 1
    }

    override fun withPacketSink(sink: PacketSink): DeviceDriver = I4seasonYuvDriver(clock, sleep, ioDispatcher, sink)

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        repeat(OPEN_ATTEMPTS) {
            try {
                val info = handshake(transport)
                return I4seasonFrameSource(transport, info, clock, ioDispatcher, packetSink)
            } catch (e: UsbException) {
                lastError = e
                // No device reset here: on libusb resetDevice() invalidates the handle we are about to
                // retry with, and Android has no reset at all. A plain wait is portable and sufficient.
                sleep(RETRY_WAIT_MS)
            }
        }
        throw UsbException("i4season handshake failed after $OPEN_ATTEMPTS attempts", cause = lastError)
    }

    internal fun handshake(t: UsbTransport): CameraInfo {
        t.claimInterface(INTERFACE)
        val raw = ByteArray(INFO_LENGTH)
        val n = t.controlTransfer(REQTYPE_IN, REQ_INFO, WVALUE, 0, raw, CMD_TIMEOUT_MS)
        val info = I4seasonInfoBlock.parse(raw, n)
        t.controlTransfer(REQTYPE_OUT, REQ_START, WVALUE, 0, ByteArray(START_LENGTH), CMD_TIMEOUT_MS)
        return info
    }

    companion object {
        val SUPPORTED_IDS = setOf(0x2CE3 to 0x3828, 0x0329 to 0x2022)
        const val INTERFACE = 0
        const val EP_IN = 0x82
        const val CHUNK_SIZE = 16384
        /** Short on purpose: a timeout costs nothing (n == 0 -> continue) and lets the loop notice close/cancel. */
        const val READ_TIMEOUT_MS = 500
        const val REQTYPE_IN = 0xA0
        const val REQTYPE_OUT = 0x20
        const val REQ_INFO = 0
        const val REQ_START = 1
        const val REQ_STOP = 2
        const val WVALUE = 5
        const val INFO_LENGTH = 512
        const val START_LENGTH = 64
        const val CMD_TIMEOUT_MS = 1000
        const val OPEN_ATTEMPTS = 3
        const val RETRY_WAIT_MS = 1500L
    }
}

class I4seasonFrameSource(
    private val transport: UsbTransport,
    val info: CameraInfo,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val packetSink: PacketSink?,
) : FrameSource {
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats

    @Volatile private var closed = false

    /**
     * Held around the native bulk read and around the stop/release/close in [close], so that closing
     * waits for an in-flight transfer to return before the interface and the transport go away.
     * Without it a shutdown can free the USB context under a running libusb transfer and abort the JVM.
     */
    private val ioLock = ReentrantLock()

    override val frames: Flow<Frame> = flow {
        val parser = I4seasonFrameParser(info.width, info.height)
        val fps = FpsMeter()
        val buf = ByteArray(I4seasonYuvDriver.CHUNK_SIZE)
        while (!closed) {
            // Blocking USB read on the IO dispatcher; emit on the collector's context (no internal buffer).
            val n = withContext(dispatcher) {
                ioLock.withLock {
                    if (closed) 0
                    else transport.bulkRead(I4seasonYuvDriver.EP_IN, buf, I4seasonYuvDriver.READ_TIMEOUT_MS)
                }
            }
            if (n == 0) continue
            val now = clock()
            packetSink?.onPacket(buf, n, now)
            for (frame in parser.accept(buf, n, now)) {
                _stats.value = StreamStats(fps.tick(now), parser.framesEmitted, parser.framesDropped, parser.framesPartial, parser.bytesReceived)
                emit(frame)
            }
        }
    }

    /**
     * Non-suspending. Worst case blocks for READ_TIMEOUT_MS (an in-flight read returning) plus
     * CMD_TIMEOUT_MS (the stop control transfer issued right after it).
     */
    override fun close() {
        closed = true
        ioLock.withLock {
            runCatching { transport.controlTransfer(I4seasonYuvDriver.REQTYPE_OUT, I4seasonYuvDriver.REQ_STOP, I4seasonYuvDriver.WVALUE, 0, ByteArray(0), I4seasonYuvDriver.CMD_TIMEOUT_MS) }
            runCatching { transport.releaseInterface(I4seasonYuvDriver.INTERFACE) }
            runCatching { transport.close() }
        }
    }
}
