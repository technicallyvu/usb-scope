package com.technicallyvu.scope.core.uvc

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
 * A configuration this driver understands the shape of but cannot drive (isochronous-only endpoints,
 * no MJPEG/YUY2 format, not a UVC device at all). Permanent, so [UvcBulkDriver.open] does not retry
 * it: the answer would be identical three times over, and the user would wait 4.5 s for it.
 */
class UvcUnsupportedException(message: String) : UsbException(message)

/**
 * Generic USB Video Class driver for cameras that stream over a **bulk** endpoint.
 *
 * Vendor-neutral: it matches on the standard interface classes rather than a USB id, so it is the
 * fallback for any UVC camera the vendor-specific drivers do not claim (it is last in
 * [com.technicallyvu.scope.core.driver.DriverRegistry]). Isochronous cameras -- the majority of
 * webcams -- are refused with an explanation rather than a mystery failure, because isochronous
 * transfers need a packet-scheduling API neither backend exposes yet.
 */
class UvcBulkDriver(
    private val clock: () -> Long = System::nanoTime,
    private val sleep: (millis: Long) -> Unit = Thread::sleep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val packetSink: PacketSink? = null,
) : DeviceDriver {

    override val id = "uvc-bulk"
    override val displayName = "UVC camera"
    override val defaultRotation: Int get() = 0

    /** A UVC function is a VideoControl interface plus at least one VideoStreaming interface. */
    override fun matches(info: UsbDeviceInfo): Boolean {
        val video = info.interfaces.filter { it.usbClass == UVC_CLASS }
        return video.any { it.subclass == VC_SUBCLASS } && video.any { it.subclass == VS_SUBCLASS }
    }

    override fun withPacketSink(sink: PacketSink): DeviceDriver =
        UvcBulkDriver(clock, sleep, ioDispatcher, sink)

    override fun open(transport: UsbTransport): FrameSource {
        var lastError: UsbException? = null
        repeat(OPEN_ATTEMPTS) {
            try {
                val setup = handshake(transport)
                return UvcFrameSource(transport, setup, clock, ioDispatcher, packetSink)
            } catch (e: UvcUnsupportedException) {
                throw e // permanent: retrying cannot change the descriptors
            } catch (e: UsbException) {
                lastError = e
                // No device reset here: on libusb resetDevice() invalidates the handle we are about to
                // retry with, and Android has no reset at all. A plain wait is portable and sufficient.
                sleep(RETRY_WAIT_MS)
            }
        }
        throw UsbException("UVC handshake failed after $OPEN_ATTEMPTS attempts", cause = lastError)
    }

    /** Everything [UvcFrameSource] needs, resolved from the descriptors and the probe/commit exchange. */
    internal data class Setup(
        val controlInterface: Int,
        val streamingInterface: Int,
        val altSetting: Int,
        val endpoint: Int,
        val kind: UvcFormatKind,
        val width: Int,
        val height: Int,
        val maxPayloadSize: Int,
    )

    internal fun handshake(t: UsbTransport): Setup {
        val config = t.readConfigDescriptor()
        val device = UvcDescriptors.parse(config) ?: throw UvcUnsupportedException("not a UVC device")
        val vs = device.streaming.firstOrNull()
            ?: throw UvcUnsupportedException("this camera declares no video streaming interface")

        // Prefer alt 0: on a bulk camera the endpoint lives there and no alt switch is really needed.
        val (alt, endpoint) = vs.altSettings
            .sortedBy { it.alt }
            .firstNotNullOfOrNull { setting ->
                setting.endpoints
                    .firstOrNull { it.isBulk && (it.address and 0x80) != 0 }
                    ?.let { setting.alt to it.address }
            }
            ?: throw UvcUnsupportedException(
                "This camera streams over isochronous endpoints, which is not supported yet",
            )

        val format = vs.formats.firstOrNull { it.kind == UvcFormatKind.MJPEG }
            ?: vs.formats.firstOrNull { it.kind == UvcFormatKind.YUY2 }
            ?: throw UvcUnsupportedException("this camera offers no MJPEG/YUY2 format")
        val frame = format.frames.firstOrNull { it.index == format.defaultFrameIndex }
            ?: format.frames.firstOrNull()
            ?: throw UvcUnsupportedException("this camera's format declares no frame sizes")

        t.claimInterface(device.controlInterface)
        t.claimInterface(vs.number)

        val negotiated = UvcProbe.negotiate(
            t,
            vsInterface = vs.number,
            bcdUvc = device.bcdUvc,
            request = UvcProbeControl(
                formatIndex = format.index,
                frameIndex = frame.index,
                frameInterval100ns = frame.defaultInterval100ns,
                maxVideoFrameSize = frame.maxFrameBufferSize,
                // 0 = "no preference": the device fills in what it can actually sustain.
                maxPayloadTransferSize = 0,
            ),
        )

        // Always issued, alt 0 included: a camera that was left in another alt setting by a previous
        // session (or another app) will not start streaming until it is put back.
        t.setAltSetting(vs.number, alt)

        return Setup(
            controlInterface = device.controlInterface,
            streamingInterface = vs.number,
            altSetting = alt,
            endpoint = endpoint,
            kind = format.kind,
            width = frame.width,
            height = frame.height,
            maxPayloadSize = negotiated.maxPayloadTransferSize.takeIf { it > 0 } ?: DEFAULT_MAX_PAYLOAD,
        )
    }

    companion object {
        const val UVC_CLASS = 0x0E
        const val VC_SUBCLASS = 0x01
        const val VS_SUBCLASS = 0x02
        const val CHUNK_SIZE = 16384
        /** Short on purpose: a timeout costs nothing (n == 0 -> continue) and lets the loop notice close/cancel. */
        const val READ_TIMEOUT_MS = 500
        /** Used when the device commits a `dwMaxPayloadTransferSize` of 0, which some firmware does. */
        const val DEFAULT_MAX_PAYLOAD = 16384
        const val OPEN_ATTEMPTS = 3
        const val RETRY_WAIT_MS = 1500L
    }
}

class UvcFrameSource internal constructor(
    private val transport: UsbTransport,
    private val setup: UvcBulkDriver.Setup,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val packetSink: PacketSink?,
) : FrameSource {
    private val _stats = MutableStateFlow(StreamStats())
    override val stats: StateFlow<StreamStats> = _stats

    @Volatile private var closed = false

    /**
     * Held around the native bulk read and around the release/close in [close], so that closing waits
     * for an in-flight transfer to return before the interface and the transport go away. Without it a
     * shutdown can free the USB context under a running libusb transfer and abort the JVM.
     */
    private val ioLock = ReentrantLock()

    // As in the other drivers, the blocking read is offloaded per-call with withContext rather than
    // flowOn: flowOn's buffer would let this loop race ahead of a downstream take(n)'s cancellation.
    override val frames: Flow<Frame> = flow {
        val parser = UvcPayloadParser(setup.kind, setup.width, setup.height, setup.maxPayloadSize)
        val fps = FpsMeter()
        val buf = ByteArray(UvcBulkDriver.CHUNK_SIZE)
        while (!closed) {
            val n = withContext(dispatcher) {
                ioLock.withLock {
                    if (closed) 0
                    else transport.bulkRead(setup.endpoint, buf, UvcBulkDriver.READ_TIMEOUT_MS)
                }
            }
            if (n == 0) continue
            val now = clock()
            packetSink?.onPacket(buf, n, now)
            for (frame in parser.accept(buf, n, buf.size, now)) {
                _stats.value = StreamStats(
                    fps = fps.tick(now),
                    framesEmitted = parser.framesEmitted,
                    framesDropped = parser.framesDropped,
                    bytesReceived = parser.bytesReceived,
                )
                emit(frame)
            }
        }
    }

    /** Non-suspending. Blocks only for as long as an in-flight read needs to return (READ_TIMEOUT_MS). */
    override fun close() {
        closed = true
        ioLock.withLock {
            // Parking the streaming interface at alt 0 tells the device to stop sending.
            runCatching { transport.setAltSetting(setup.streamingInterface, 0) }
            runCatching { transport.releaseInterface(setup.streamingInterface) }
            runCatching { transport.releaseInterface(setup.controlInterface) }
            runCatching { transport.close() }
        }
    }
}
